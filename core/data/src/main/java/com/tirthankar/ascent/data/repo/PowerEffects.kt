package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.TemplateTitles
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.XpSource
import com.tirthankar.ascent.engine.HabitSeries
import com.tirthankar.ascent.data.db.CastStatus
import com.tirthankar.ascent.data.db.CastTargetKind
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.settings.SettingsRepository
import com.tirthankar.ascent.engine.CastCheck
import com.tirthankar.ascent.engine.ClarityRules
import com.tirthankar.ascent.engine.DayResolver
import com.tirthankar.ascent.engine.DerivedStats
import com.tirthankar.ascent.engine.DifficultyController
import com.tirthankar.ascent.engine.HyperfocusRules
import com.tirthankar.ascent.engine.PowerHandlers
import com.tirthankar.ascent.engine.PowerRules
import com.tirthankar.ascent.engine.QuestXp
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.SafetyRules
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.TargetUnit
import com.tirthankar.ascent.engine.TemplateLibrary
import com.tirthankar.ascent.engine.TemplateMatcher
import com.tirthankar.ascent.engine.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A running Focus Session: its cast, the linked quest and when it began. */
data class FocusSession(val castId: Long, val questId: Long?, val questTitle: String, val startedAt: Instant) {
    val endsAt: Instant get() = startedAt.plusSeconds(HyperfocusRules.SESSION_MINUTES * 60L)
}

/**
 * What each power does once cast (PHASE4.md section 1, Tasks 15–20). Every call casts
 * through [PowerRepository] (check, MP, use record, log) and applies the effect in the
 * same transaction, so a failed effect leaves no MP spent.
 *
 * - Second Wind: an open Body daily becomes Mobility 15 min (Easy); it still counts.
 * - Limit Break: an open Body quest rises to the weekly cap's limit (A1); ×3 XP on completion.
 * - Clarity: a 3-line journal kept on the phone; up to 3 extra quests for tomorrow.
 * - Insight and Mentor's Eye: the cast only; the caller shares the request (A7).
 * - Unbroken: yesterday's missed Daily counts as done at 50% XP, the streak and 15 HP come
 *   back, today's penalty quest goes (A9).
 * - Eigenstate Collapse, Overload: their multipliers are paid on completion.
 */
@Singleton
class PowerEffects @Inject constructor(
    private val db: AscentDatabase,
    private val powers: PowerRepository,
    private val quests: QuestRepository,
    private val skills: SkillRepository,
    private val titles: TemplateTitles,
    private val settings: SettingsRepository,
    private val gameDay: GameDay,
    private val events: GameEvents,
    private val time: TimeSource,
) {
    private val questDao = db.questDao()
    private val playerDao = db.playerDao()

    /**
     * The running Focus Session (A6): an active Hyperfocus cast whose session hasn't
     * ended. Survives restarts; expires at the reset like any cast.
     */
    val focusSession: Flow<FocusSession?> = powers.activeCasts.map { casts ->
        val id = skills.idFor(PowerHandlers.HYPERFOCUS)
        casts.firstOrNull { it.skillId == id && it.note == null }?.let { cast ->
            val quest = cast.targetId?.let { questDao.getById(it) }
            FocusSession(cast.id, cast.targetId, quest?.title.orEmpty(), Instant.ofEpochMilli(cast.castAt))
        }
    }

    /**
     * Ends the session. A full 90 minutes: ×2 XP and ×2 INT mastery on the linked quest,
     * which completes now if it's untimed or 90 minutes or less; a longer quest keeps the
     * bonus until you tick it today. Ended early: the minutes count (Focus Chain, and a
     * timed quest they cover), no bonus, MP not refunded.
     */
    suspend fun endFocus(castId: Long): HyperfocusRules.Result? {
        val dao = db.skillDao()
        val (result, completeQuest) = db.withTransaction {
            val cast = dao.cast(castId)?.takeIf { it.status == CastStatus.ACTIVE && it.note == null } ?: return@withTransaction null
            val minutes = Duration.between(Instant.ofEpochMilli(cast.castAt), time.now()).toMinutes().toInt()
            val result = HyperfocusRules.result(minutes)
            val quest = cast.targetId?.let { questDao.getById(it) }?.takeIf { it.status == QuestStatus.OPEN }
            val questMinutes = quest?.let { minutesOf(it) }
            if (result.full) {
                dao.updateCast(cast.copy(note = PowerRepository.FOCUS_FULL))
                result to quest?.takeIf { questMinutes == null || questMinutes <= HyperfocusRules.SESSION_MINUTES }
            } else {
                dao.updateCast(cast.copy(status = CastStatus.COMPLETED, note = PowerRepository.FOCUS_MINUTES + result.progressMinutes))
                result to quest?.takeIf { questMinutes != null && result.progressMinutes >= questMinutes }
            }
        } ?: return null
        // Completing runs its own transaction (XP, loot, Gates); the full cast pays ×2 there.
        completeQuest?.let { quests.complete(it.id) }
        return result
    }

    /** A timed quest's target in minutes, or null for other units. */
    private fun minutesOf(q: com.tirthankar.ascent.data.db.QuestEntity): Double? = when (q.unit) {
        TargetUnit.MINUTES -> q.target
        TargetUnit.HOURS -> q.target * 60
        else -> null
    }

    /**
     * Casts a power. [input] is First Principles' derivation name or Parallel Runs'
     * partner quest id. Clarity and Insight have their own calls.
     */
    suspend fun cast(
        skillId: String,
        targetKind: CastTargetKind = CastTargetKind.NONE,
        targetId: Long? = null,
        input: String? = null,
    ): CastResult =
        when (skills.definition(skillId)?.handler) {
            PowerHandlers.CLARITY, PowerHandlers.INSIGHT -> CastResult.Refused(CastCheck.NeedsTarget)
            PowerHandlers.FIRST_PRINCIPLES -> withEffect(skillId, targetKind, targetId) { castId -> firstPrinciples(castId, targetId, input) }
            PowerHandlers.PARALLEL_RUNS -> withEffect(skillId, targetKind, targetId) { castId -> parallelRuns(castId, targetId, input) }
            PowerHandlers.SECOND_WIND -> withEffect(skillId, targetKind, targetId) { castId -> secondWind(castId, targetId) }
            PowerHandlers.LIMIT_BREAK -> withEffect(skillId, targetKind, targetId) { castId -> limitBreak(castId, targetId) }
            PowerHandlers.MENTORS_EYE -> withEffect(skillId, targetKind, targetId) { castId -> powers.finish(castId) }
            PowerHandlers.UNBROKEN -> withEvents(skillId, targetKind, targetId) { castId, emitted -> unbroken(castId, emitted) }
            else -> powers.cast(skillId, targetKind, targetId)
        }

    /**
     * Clarity: the three lines stay on the phone (in the cast's note, never in STATUS);
     * the third becomes up to three extra quests for tomorrow.
     */
    suspend fun clarity(skillId: String, moved: String, stuck: String, next: String): CastResult =
        withEffect(skillId, CastTargetKind.NONE, null) { castId ->
            val lines = listOf(moved, stuck, next).map { it.replace('\n', ' ').trim() }
            powers.finish(castId, lines.joinToString("\n"))
            val tomorrow = gameDay.today().plusDays(1)
            ClarityRules.questTitles(lines[2]).forEach { title ->
                val (realm, stat) = realmFor(title)
                quests.insert(
                    NewQuest(
                        title = title,
                        realm = realm,
                        stat = stat,
                        tier = Tier.NORMAL,
                        target = 1.0,
                        unitLabel = titles.unitLabel(TargetUnit.OTHER),
                        scope = QuestScope.DAILY,
                        source = QuestSource.MANUAL,
                    ),
                    tomorrow,
                )
            }
        }

    /** Insight: spends a charge; the caller shares `Request: Stuck: …` with the STATUS (A7). */
    suspend fun insight(skillId: String): CastResult =
        withEffect(skillId, CastTargetKind.NONE, null) { castId -> powers.finish(castId) }

    /** Clarity's past entries, newest first: (game day, three lines). On the phone only. */
    suspend fun journal(skillId: String, limit: Int = 30): List<Pair<Long, List<String>>> =
        db.skillDao().recentCasts(skillId, limit).mapNotNull { c -> c.note?.let { c.day to it.split('\n') } }

    private class Abort : Exception()

    /** Casts, then applies [effect]; if the effect can't land, the whole cast rolls back. */
    private suspend fun withEffect(
        skillId: String,
        targetKind: CastTargetKind,
        targetId: Long?,
        effect: suspend (castId: Long) -> Unit,
    ): CastResult = try {
        db.withTransaction {
            when (val result = powers.cast(skillId, targetKind, targetId)) {
                is CastResult.Refused -> result
                is CastResult.Cast -> {
                    effect(result.castId)
                    result
                }
            }
        }
    } catch (_: Abort) {
        CastResult.Refused(CastCheck.NeedsTarget)
    }

    /** As [withEffect], emitting the effect's events (level-ups) after it commits. */
    private suspend fun withEvents(
        skillId: String,
        targetKind: CastTargetKind,
        targetId: Long?,
        effect: suspend (castId: Long, emitted: MutableList<GameEvent>) -> Unit,
    ): CastResult {
        val emitted = mutableListOf<GameEvent>()
        val result = withEffect(skillId, targetKind, targetId) { castId -> effect(castId, emitted) }
        if (result is CastResult.Cast) emitted.forEach(events::emit)
        return result
    }

    /** Second Wind: the quest keeps its place (and counts as the day's Body objective). */
    private suspend fun secondWind(castId: Long, questId: Long?) {
        val quest = questId?.let { questDao.getById(it) } ?: throw Abort()
        val template = TemplateLibrary[PowerRules.SECOND_WIND_TEMPLATE] ?: throw Abort()
        val lightsOut = SafetyRules.latestLightsOut(settings.current().wakeTime)
        powers.finish(castId, "${quest.title}|${quest.target}|${quest.unitLabel}|${quest.templateKey.orEmpty()}")
        questDao.update(
            quest.copy(
                title = titles.title(template, lightsOut),
                stat = template.stat,
                tier = Tier.EASY,
                target = PowerRules.SECOND_WIND_MINUTES,
                unit = TargetUnit.MINUTES,
                unitLabel = titles.unitLabel(template),
                templateKey = template.key,
                // Today belongs to the Mobility series; the swapped-out series isn't updated (PHASE5.md section 1).
                seriesId = HabitSeries.forTemplate(template.key),
            ),
        )
    }

    /** First Principles (Theorist): an open Mind daily becomes "Derivation: …" you name, same tier; it still counts. */
    private suspend fun firstPrinciples(castId: Long, questId: Long?, name: String?) {
        val title = name?.trim()?.takeIf { it.isNotEmpty() && !SafetyRules.isForbidden(it) } ?: throw Abort()
        val quest = questId?.let { questDao.getById(it) } ?: throw Abort()
        powers.finish(castId, "${quest.title}|${quest.target}|${quest.unitLabel}|${quest.templateKey.orEmpty()}")
        questDao.update(
            quest.copy(
                title = titles.derivation(title),
                stat = Stat.INT,
                target = 1.0,
                unit = TargetUnit.OTHER,
                unitLabel = titles.unitLabel(TargetUnit.OTHER),
                templateKey = null,
                tags = "derivation",
                // A derivation is a one-off: the replaced series isn't updated today.
                seriesId = null,
            ),
        )
    }

    /** Parallel Runs (Computationalist): links two open timed Mind quests; the longer one done completes both. */
    private suspend fun parallelRuns(castId: Long, questId: Long?, partner: String?) {
        val id = questId ?: throw Abort()
        val partnerId = partner?.toLongOrNull() ?: throw Abort()
        if (powers.parallelPartners(id, gameDay.today()).none { it.id == partnerId }) throw Abort()
        db.skillDao().cast(castId)?.let { db.skillDao().updateCast(it.copy(note = "$id,$partnerId")) }
    }

    /** Limit Break: the cast stays active until the quest is done (×3 then, A16). */
    private suspend fun limitBreak(castId: Long, questId: Long?) {
        val quest = questId?.let { questDao.getById(it) } ?: throw Abort()
        val target = powers.limitBreakTarget(quest, gameDay.today()) ?: throw Abort()
        db.skillDao().cast(castId)?.let { db.skillDao().updateCast(it.copy(note = quest.target.toString())) }
        questDao.update(quest.copy(target = target))
    }

    /** Unbroken (A9): only reachable the day after an unshielded miss (the cast check). */
    private suspend fun unbroken(castId: Long, emitted: MutableList<GameEvent>) {
        val today = gameDay.today().toEpochDay()
        val yesterday = today - 1
        val snapshot = db.snapshotDao().get(yesterday) ?: throw Abort()
        val player = playerDao.getPlayer() ?: throw Abort()
        val missed = questDao.getDailyObjectives(yesterday).filter { it.status != QuestStatus.DONE }
        val difficulty = playerDao.getDifficulties().associate { it.realm to it.multiplier }
        val restored = (db.snapshotDao().get(yesterday - 1)?.streakDays ?: 0) + 1

        var total = 0L
        missed.forEach { q ->
            val full = QuestXp.xp(
                tier = q.tier,
                difficulty = difficulty[q.realm] ?: DifficultyController.INITIAL_MULTIPLIER,
                level = player.level,
                streakDays = restored - 1,
                isCritical = false,
            )
            val xp = PowerRules.unbrokenXp(full)
            total += xp
            questDao.update(q.copy(status = QuestStatus.DONE, completedDay = yesterday, xpAwarded = xp, levelAtCompletion = null))
        }
        questDao.getKindOnDay(QuestKind.PENALTY, today).filter { it.status == QuestStatus.OPEN }
            .forEach { questDao.update(it.copy(status = QuestStatus.REMOVED)) }

        val maxHp = DerivedStats.maxHp(playerDao.getAttributes().toStatBlock())
        playerDao.upsertPlayer(
            player.copy(
                streakDays = restored,
                bestStreak = maxOf(player.bestStreak, restored),
                consecutiveMisses = 0,
                hp = (player.hp - DayResolver.MISSED_DAILY_HP).coerceAtMost(maxHp),
                lastMissDay = null,
            ),
        )
        db.snapshotDao().insert(
            snapshot.copy(
                dailyComplete = true,
                completed = snapshot.objectives,
                bodyDone = snapshot.bodyTotal,
                mindDone = snapshot.mindTotal,
                selfDone = snapshot.selfTotal,
                streakDays = restored,
            ),
        )
        // The quests now read done, but strength keeps yesterday's misses (D3): no observation changes.
        db.grantXp(total, emitted, XpLog(XpSource.UNBROKEN, today, time.now().toEpochMilli(), castId))
        powers.finish(castId, "restored $restored")
    }

    /** Clarity's quests: a matching template's realm and stat, else Mind (INT). */
    private fun realmFor(title: String): Pair<Realm, Stat> {
        Realm.entries.forEach { realm ->
            TemplateMatcher.match(title, realm, TargetUnit.OTHER)?.let { return it.realm to it.stat }
        }
        return Realm.MIND to Stat.INT
    }
}
