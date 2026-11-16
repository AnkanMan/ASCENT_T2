package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.CastStatus
import com.tirthankar.ascent.data.db.CastTargetKind
import com.tirthankar.ascent.data.db.PlayerEntity
import com.tirthankar.ascent.data.db.PlayerSkillEntity
import com.tirthankar.ascent.data.db.PowerCastEntity
import com.tirthankar.ascent.data.db.QuestEntity
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.settings.SettingsRepository
import com.tirthankar.ascent.engine.BodyTargetMemory
import com.tirthankar.ascent.engine.CastCheck
import com.tirthankar.ascent.engine.CastTarget
import com.tirthankar.ascent.engine.DerivedStats
import com.tirthankar.ascent.engine.Effect
import com.tirthankar.ascent.engine.GameClock
import com.tirthankar.ascent.engine.LimitPeriod
import com.tirthankar.ascent.engine.LoreKinds
import com.tirthankar.ascent.engine.Periods
import com.tirthankar.ascent.engine.PowerHandlers
import com.tirthankar.ascent.engine.PowerRules
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.ShieldRules
import com.tirthankar.ascent.engine.SkillDef
import com.tirthankar.ascent.engine.SkillKind
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.StatBlock
import com.tirthankar.ascent.engine.TargetUnit
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** One power offered for a target: its check, MP cost, and Limit Break's new target. */
data class CastOption(
    val skill: SkillDef,
    val check: CastCheck,
    val mpCost: Int,
    val newTarget: Double? = null,
    /** Parallel Runs: the other open timed Mind quests it can link to (id, title). */
    val partners: List<Pair<Long, String>> = emptyList(),
)

/** An unlocked active and where it stands, for STATUS v3's Powers line. [charges] is (left, total) for Insight. */
data class PowerStanding(val skill: SkillDef, val check: CastCheck, val charges: Pair<Int, Int>? = null)

/** Powers on one completion, on top of the passive cap (A10, A16). */
data class PowerBoost(val xp: Double = 1.0, val mastery: Double = 1.0)

/** What a cast did: logged, or refused for the check's reason. */
sealed interface CastResult {
    data class Cast(val castId: Long, val mpSpent: Int) : CastResult
    data class Refused(val check: CastCheck) : CastResult
}

/**
 * The casting framework (PHASE4.md section 2): the cast check, MP, the period use
 * record and a `power_cast` row per cast, so effects survive restarts and the reset can
 * expire them. A cast whose target quest is removed the same day refunds its MP.
 * Each power's own effect (swaps, target changes, shares) is applied by its handler.
 */
@Singleton
class PowerRepository @Inject constructor(
    private val db: AscentDatabase,
    private val skills: SkillRepository,
    private val inventory: InventoryRepository,
    private val settings: SettingsRepository,
    private val gameDay: GameDay,
    private val time: TimeSource,
    private val events: GameEvents,
    private val lore: LoreRepository,
) {
    private val dao = db.skillDao()
    private val playerDao = db.playerDao()
    private val questDao = db.questDao()

    val activeCasts: Flow<List<PowerCastEntity>> = dao.observeActiveCasts()

    suspend fun check(skillId: String, targetKind: CastTargetKind = CastTargetKind.NONE, targetId: Long? = null): CastCheck {
        val def = skills.definition(skillId) ?: return CastCheck.NotUnlocked
        val state = state() ?: return CastCheck.NotUnlocked
        return check(def, state, targetKind, targetId)
    }

    /** Every unlocked active you cast yourself, with its untargeted check (STATUS v3). */
    suspend fun standings(): List<PowerStanding> {
        val state = state() ?: return emptyList()
        return dao.skills().filter { it.unlocked }
            .mapNotNull { skills.definition(it.skillId) }
            .filter { it.kind == SkillKind.ACTIVE && !it.locked && it.handler !in PowerRules.AUTOMATIC }
            .map { def ->
                val limit = def.limit
                val charges = if (def.handler == PowerHandlers.INSIGHT && limit != null) {
                    val allowed = PowerRules.allowedUses(def, state.stats, state.effects)
                    Periods.usesLeft(limit.period, allowed, dao.skill(def.id)?.toUse(), state.today) to allowed
                } else {
                    null
                }
                PowerStanding(def, check(def, state, CastTargetKind.NONE, null), charges)
            }
    }

    /**
     * The held powers that take this kind of target, with their checks, for a long-press
     * on a quest or a boss task. Powers that don't fit this target (wrong realm, not a
     * daily) are left out; the rest show their one-line reason when they can't be cast.
     */
    suspend fun optionsFor(targetKind: CastTargetKind, targetId: Long): List<CastOption> {
        val state = state() ?: return emptyList()
        val accepted = when (targetKind) {
            CastTargetKind.QUEST -> QUEST_HANDLERS
            CastTargetKind.BOSS_TASK -> BOSS_TASK_HANDLERS
            CastTargetKind.NONE -> return emptyList()
        }
        return dao.skills().filter { it.unlocked }
            .mapNotNull { skills.definition(it.skillId) }
            .filter { it.handler in accepted }
            .mapNotNull { def ->
                val check = check(def, state, targetKind, targetId)
                if (check == CastCheck.NeedsTarget) return@mapNotNull null
                val quest = if (targetKind == CastTargetKind.QUEST) questDao.getById(targetId) else null
                CastOption(
                    skill = def,
                    check = check,
                    mpCost = PowerRules.mpCost(def, dao.skill(def.id)?.toUse(), state.today, state.effects),
                    newTarget = if (def.handler == PowerHandlers.LIMIT_BREAK && quest != null) limitBreakTarget(quest, state.today) else null,
                    partners = if (def.handler == PowerHandlers.PARALLEL_RUNS) parallelPartners(targetId, state.today).map { it.id to it.title } else emptyList(),
                )
            }
    }

    /** Casts after the check passes: spends MP, records the use, logs the cast. */
    suspend fun cast(
        skillId: String,
        targetKind: CastTargetKind = CastTargetKind.NONE,
        targetId: Long? = null,
        note: String? = null,
    ): CastResult {
        val emitted = mutableListOf<GameEvent>()
        val result = db.withTransaction {
            val def = skills.definition(skillId) ?: return@withTransaction CastResult.Refused(CastCheck.NotUnlocked)
            val state = state() ?: return@withTransaction CastResult.Refused(CastCheck.NotUnlocked)
            val check = check(def, state, targetKind, targetId)
            if (check != CastCheck.Ok) return@withTransaction CastResult.Refused(check)

            val row = dao.skill(def.id) ?: return@withTransaction CastResult.Refused(CastCheck.NotUnlocked)
            val cost = PowerRules.mpCost(def, row.toUse(), state.today, state.effects)
            val now = time.now()
            val use = Periods.recordUse(def.limit?.period ?: LimitPeriod.DAY, row.toUse(), state.today, now)
            dao.upsertSkill(row.copy(periodKey = use.periodKey, usesInPeriod = use.usesInPeriod, lastCastAt = now.toEpochMilli()))
            playerDao.upsertPlayer(state.player.copy(mp = state.player.mp - cost))
            val id = dao.insertCast(
                PowerCastEntity(
                    skillId = def.id,
                    castAt = now.toEpochMilli(),
                    day = state.today.toEpochDay(),
                    targetKind = targetKind,
                    targetId = targetId,
                    mpSpent = cost,
                    status = CastStatus.ACTIVE,
                    expiresAt = null,
                    note = note,
                ),
            )
            emitted += GameEvent.PowerCast(def.id, def.name)
            lore.unlockOnce(LoreKinds.POWER_FIRST_CAST, def.name, emitted)
            CastResult.Cast(id, cost)
        }
        emitted.forEach(events::emit)
        return result
    }

    /** A power whose effect landed at once: the cast is done, with its handler data. Call inside a transaction. */
    internal suspend fun finish(castId: Long, note: String? = null) {
        val cast = dao.cast(castId) ?: return
        dao.updateCast(cast.copy(status = CastStatus.COMPLETED, note = note ?: cast.note))
    }

    /** Casts from days before [today] expire at the reset. Call inside a transaction. */
    internal suspend fun expireBefore(today: Long) = dao.expireBefore(today)

    /** A quest removed the day it was targeted gives the cast's MP back. Call inside a transaction. */
    internal suspend fun refundForQuest(questId: Long, today: Long) {
        val casts = dao.castsOn(CastTargetKind.QUEST.name, questId, today).filter { it.status == CastStatus.ACTIVE }
        if (casts.isEmpty()) return
        val player = playerDao.getPlayer() ?: return
        val maxMp = DerivedStats.maxMp(playerDao.getAttributes().toStatBlock())
        casts.forEach { dao.updateCast(it.copy(status = CastStatus.REFUNDED)) }
        playerDao.upsertPlayer(player.copy(mp = (player.mp + casts.sumOf { it.mpSpent }).coerceAtMost(maxMp)))
    }

    /**
     * The powers on completing [quest] today, on top of the passive cap (A10, A16):
     * Limit Break ×3 and Eigenstate Collapse ×3 on their target, Overload ×2 on every Body
     * quest of its day, and a full Hyperfocus session ×2 XP and ×2 INT mastery. Quest
     * casts are used up. Call inside a transaction.
     */
    internal suspend fun consumeBoost(quest: QuestEntity, today: Long): PowerBoost {
        var xp = 1.0
        var mastery = 1.0
        dao.activeCasts().filter { it.day == today }.forEach { cast ->
            val onThis = cast.targetKind == CastTargetKind.QUEST && cast.targetId == quest.id
            when (skills.definition(cast.skillId)?.handler) {
                PowerHandlers.LIMIT_BREAK -> if (onThis) {
                    xp *= PowerRules.LIMIT_BREAK_XP
                    dao.updateCast(cast.copy(status = CastStatus.COMPLETED))
                }
                PowerHandlers.EIGENSTATE_COLLAPSE -> if (onThis) {
                    xp *= PowerRules.EIGENSTATE_XP
                    dao.updateCast(cast.copy(status = CastStatus.COMPLETED))
                }
                PowerHandlers.HYPERFOCUS -> if (onThis && cast.note == FOCUS_FULL) {
                    xp *= PowerRules.HYPERFOCUS_XP
                    if (quest.stat == Stat.INT) mastery *= PowerRules.HYPERFOCUS_MASTERY
                    dao.updateCast(cast.copy(status = CastStatus.COMPLETED))
                }
                PowerHandlers.OVERLOAD -> if (quest.realm == Realm.BODY) xp *= PowerRules.OVERLOAD_XP
            }
        }
        return PowerBoost(xp, mastery)
    }

    /**
     * Stillness and Long Walk trigger by themselves, once a day, while their class skill is
     * held. Returns true (and records the use) when [handler] fires now. Call inside a transaction.
     */
    internal suspend fun triggerAutomatic(handler: String, today: LocalDate): Boolean {
        val id = skills.idFor(handler) ?: return false
        val row = dao.skill(id)?.takeIf { it.unlocked } ?: return false
        if (Periods.usesLeft(LimitPeriod.DAY, 1, row.toUse(), today) <= 0) return false
        val use = Periods.recordUse(LimitPeriod.DAY, row.toUse(), today, time.now())
        dao.upsertSkill(row.copy(periodKey = use.periodKey, usesInPeriod = use.usesInPeriod, lastCastAt = time.now().toEpochMilli()))
        return true
    }

    /** Open timed Mind quests other than [questId], for Parallel Runs. */
    internal suspend fun parallelPartners(questId: Long, today: LocalDate): List<QuestEntity> {
        val t = today.toEpochDay()
        return (questDao.getDay(t) + questDao.getWeek(GameClock.weekStart(today).toEpochDay()))
            .filter { it.id != questId && it.status == QuestStatus.OPEN && it.realm == Realm.MIND && it.minutes() != null }
    }

    /**
     * Parallel Runs: when the longer of two linked quests completes, the shorter one does too.
     * Returns the partner to complete (and uses the cast up), or null. Call inside a transaction.
     */
    internal suspend fun parallelPartnerToComplete(quest: QuestEntity, today: Long): Long? {
        val id = skills.idFor(PowerHandlers.PARALLEL_RUNS) ?: return null
        val cast = dao.activeCasts().firstOrNull { c ->
            c.skillId == id && c.day == today && c.note?.split(',')?.mapNotNull { it.toLongOrNull() }?.contains(quest.id) == true
        } ?: return null
        val ids = cast.note.orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        val partner = questDao.getById(ids.firstOrNull { it != quest.id } ?: return null) ?: return null
        // Only the longer one (or an equal one) carries the other.
        if ((quest.minutes() ?: 0.0) < (partner.minutes() ?: 0.0) - 1e-9) return null
        dao.updateCast(cast.copy(status = CastStatus.COMPLETED))
        return partner.id.takeIf { partner.status == QuestStatus.OPEN }
    }

    /** Minutes of Focus Sessions ended early, per game day (they count for Focus Chain). */
    internal suspend fun focusMinutes(days: LongRange): Map<Long, Double> {
        val id = skills.idFor(PowerHandlers.HYPERFOCUS) ?: return emptyMap()
        return dao.recentCasts(id, FOCUS_LOOKBACK)
            .filter { it.day in days }
            .mapNotNull { c ->
                val note = c.note?.takeIf { it.startsWith(FOCUS_MINUTES) } ?: return@mapNotNull null
                note.removePrefix(FOCUS_MINUTES).toDoubleOrNull()?.let { c.day to it }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
    }

    /** Undo puts a used-up quest cast back, so a redo pays the same. Call inside a transaction. */
    internal suspend fun restoreForQuest(questId: Long, today: Long) = restore(CastTargetKind.QUEST, questId, today)

    /** Eigenstate Collapse on a boss task: ×3 XP (damage never changes). Used up. Call inside a transaction. */
    internal suspend fun consumeTaskMultiplier(taskId: Long, today: Long): Double {
        var multiplier = 1.0
        dao.castsOn(CastTargetKind.BOSS_TASK.name, taskId, today).filter { it.status == CastStatus.ACTIVE }.forEach { cast ->
            if (skills.definition(cast.skillId)?.handler == PowerHandlers.EIGENSTATE_COLLAPSE) {
                multiplier *= PowerRules.EIGENSTATE_XP
                dao.updateCast(cast.copy(status = CastStatus.COMPLETED))
            }
        }
        return multiplier
    }

    internal suspend fun restoreForTask(taskId: Long, today: Long) = restore(CastTargetKind.BOSS_TASK, taskId, today)

    private suspend fun restore(kind: CastTargetKind, id: Long, today: Long) {
        dao.castsOn(kind.name, id, today)
            .filter { it.status == CastStatus.COMPLETED }
            .forEach { dao.updateCast(it.copy(status = CastStatus.ACTIVE)) }
    }

    private class CastState(
        val player: PlayerEntity,
        val today: LocalDate,
        val isRestDay: Boolean,
        val stats: StatBlock,
        val effects: List<Effect>,
    )

    private suspend fun state(): CastState? {
        val s = settings.current()
        val today = gameDay.clock(s).gameDay(time.now())
        val player = playerDao.getPlayer() ?: return null
        val restDay = today.dayOfWeek in s.restDays || player.recoveryDay == today.toEpochDay()
        val effects = skills.effects(player) + inventory.gearBonus().effects
        return CastState(player, today, restDay, playerDao.getAttributes().toStatBlock(), effects)
    }

    private suspend fun check(def: SkillDef, state: CastState, targetKind: CastTargetKind, targetId: Long?): CastCheck {
        val row: PlayerSkillEntity? = dao.skill(def.id)
        val check = PowerRules.check(
            skill = def,
            unlocked = row?.unlocked == true,
            mp = state.player.mp,
            use = row?.toUse(),
            gameDay = state.today,
            now = time.now(),
            stats = state.stats,
            effects = state.effects,
            target = target(def, state, targetKind, targetId),
        )
        // A power whose handler isn't built yet can't be cast, so MP is never spent for nothing.
        return if (check == CastCheck.Ok && def.handler !in WIRED) CastCheck.NotCastable else check
    }

    /** What the handler's target looks like now. Each power accepts only its own kind of target. */
    private suspend fun target(def: SkillDef, state: CastState, targetKind: CastTargetKind, targetId: Long?): CastTarget {
        val epoch = state.today.toEpochDay()
        val quest = if (targetKind == CastTargetKind.QUEST && targetId != null) questDao.getById(targetId) else null
        val openQuest = quest?.takeIf { it.status == QuestStatus.OPEN && it.isCurrent(state.today) }
        val chosen = when (def.handler) {
            PowerHandlers.SECOND_WIND -> openQuest?.let { it.realm == Realm.BODY && it.isDaily } == true
            PowerHandlers.LIMIT_BREAK -> openQuest?.realm == Realm.BODY
            PowerHandlers.HYPERFOCUS -> openQuest?.realm == Realm.MIND
            PowerHandlers.PARALLEL_RUNS -> openQuest?.let {
                it.realm == Realm.MIND && it.minutes() != null && parallelPartners(it.id, state.today).isNotEmpty()
            } == true
            PowerHandlers.FIRST_PRINCIPLES -> openQuest?.let { it.realm == Realm.MIND && it.isDaily } == true
            PowerHandlers.EIGENSTATE_COLLAPSE -> when (targetKind) {
                CastTargetKind.QUEST -> openQuest != null
                CastTargetKind.BOSS_TASK -> targetId?.let { db.bossDao().getTask(it) }?.done == false
                CastTargetKind.NONE -> false
            }
            else -> true
        }
        val limitBreak = if (def.handler == PowerHandlers.LIMIT_BREAK && openQuest != null) {
            limitBreakTarget(openQuest, state.today)
        } else {
            null
        }
        return CastTarget(
            chosen = chosen,
            isRestDay = state.isRestDay,
            limitBreakTarget = limitBreak,
            unshieldedMissYesterday = ShieldRules.unbrokenEligible(state.player.lastMissDay, epoch, state.player.lastMissShielded),
        )
    }

    /** Limit Break's new target: the largest the +10% weekly cap allows (A1). */
    internal suspend fun limitBreakTarget(quest: QuestEntity, today: LocalDate): Double? {
        val key = quest.templateKey ?: return null
        if (quest.unit == TargetUnit.OTHER) return null
        val memory = playerDao.getBodyTargets().firstOrNull { it.templateKey == key }
            ?.let { BodyTargetMemory(it.weekStartDay, it.weekCap, it.lastTarget) }
        return PowerRules.limitBreakTarget(quest.target, memory, GameClock.weekStart(today).toEpochDay(), quest.unit)
    }

    companion object {
        /** Powers cast on a quest (long-press) and on a boss task (the Boss Arena). */
        val QUEST_HANDLERS: Set<String> = setOf(
            PowerHandlers.SECOND_WIND, PowerHandlers.LIMIT_BREAK, PowerHandlers.HYPERFOCUS,
            PowerHandlers.EIGENSTATE_COLLAPSE, PowerHandlers.FIRST_PRINCIPLES, PowerHandlers.PARALLEL_RUNS,
        )
        val BOSS_TASK_HANDLERS: Set<String> = setOf(PowerHandlers.EIGENSTATE_COLLAPSE)

        /** Hyperfocus cast notes: a full session (×2 waits for the quest), or minutes ended early. */
        const val FOCUS_FULL = "full"
        const val FOCUS_MINUTES = "m="
        private const val FOCUS_LOOKBACK = 120

        /**
         * Handlers whose effect is built. Phase 4 section H adds the rest one by one;
         * until then they report [CastCheck.NotCastable].
         */
        val WIRED: Set<String> = setOf(
            PowerHandlers.EIGENSTATE_COLLAPSE, PowerHandlers.OVERLOAD,
            // Section H (Tasks 15–20), applied by PowerEffects.
            PowerHandlers.SECOND_WIND, PowerHandlers.CLARITY, PowerHandlers.LIMIT_BREAK,
            PowerHandlers.INSIGHT, PowerHandlers.MENTORS_EYE, PowerHandlers.UNBROKEN,
            // Section I (Task 21): the Focus Session.
            PowerHandlers.HYPERFOCUS,
            // Section J (Task 23): class skills cast on a quest.
            PowerHandlers.FIRST_PRINCIPLES, PowerHandlers.PARALLEL_RUNS,
        )
    }

    private fun QuestEntity.minutes(): Double? = when (unit) {
        TargetUnit.MINUTES -> target
        TargetUnit.HOURS -> target * 60
        else -> null
    }

    private fun QuestEntity.isCurrent(today: LocalDate): Boolean {
        val t = today.toEpochDay()
        return when {
            kind == QuestKind.SIDE -> t in day..(expiresDay ?: day)
            scope == QuestScope.DAILY -> day == t
            else -> day == GameClock.weekStart(today).toEpochDay()
        }
    }
}
