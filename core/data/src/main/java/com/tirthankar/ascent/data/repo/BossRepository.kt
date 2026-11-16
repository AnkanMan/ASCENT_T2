package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.NoticeKind
import com.tirthankar.ascent.data.TemplateTitles
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.ArcEntity
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.XpSource
import com.tirthankar.ascent.data.db.BossEntity
import com.tirthankar.ascent.data.db.BossPhaseEntity
import com.tirthankar.ascent.data.db.BossStatus
import com.tirthankar.ascent.data.db.BossTaskEntity
import com.tirthankar.ascent.data.db.InventoryItemEntity
import com.tirthankar.ascent.data.db.LoreTarget
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.model.Boss
import com.tirthankar.ascent.data.model.BossTask
import com.tirthankar.ascent.engine.BaselineKey
import com.tirthankar.ascent.engine.BossRules
import com.tirthankar.ascent.engine.BossTaskState
import com.tirthankar.ascent.engine.DerivedStats
import com.tirthankar.ascent.engine.DropSource
import com.tirthankar.ascent.engine.LoreKinds
import com.tirthankar.ascent.engine.MasteryGrowth
import com.tirthankar.ascent.engine.QuestTags
import com.tirthankar.ascent.engine.RewardCalculator
import com.tirthankar.ascent.engine.RewardContext
import com.tirthankar.ascent.engine.SafetyRules
import com.tirthankar.ascent.engine.TemplateLibrary
import com.tirthankar.ascent.engine.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class NewBossTask(val title: String, val hours: Double)

data class NewBossPhase(val name: String, val tasks: List<NewBossTask>)

data class NewBoss(
    val title: String,
    val phases: List<NewBossPhase>,
    val deadlineDay: Long? = null,
    val arcId: Long? = null,
    val isRaid: Boolean = false,
    val source: QuestSource = QuestSource.MANUAL,
)

/** Why a boss couldn't be added. */
enum class BossRefusal { LIMIT, FORBIDDEN, TOO_MANY_PHASES }

/**
 * Bosses and Trials (SPEC "Bosses"). The HP bar is honest: it moves only when a task is
 * finished. Tasks pay XP (a crit doubles it, never the damage); defeat pays
 * 1.5 × damage × (1 + 0.05 L), a guaranteed Epic+ drop, a title and a lore line.
 */
@Singleton
class BossRepository @Inject constructor(
    private val db: AscentDatabase,
    private val gameDay: GameDay,
    private val time: TimeSource,
    private val events: GameEvents,
    private val rewards: RewardCalculator,
    private val inventory: InventoryRepository,
    private val lore: LoreRepository,
    private val achievements: AchievementRepository,
    private val titles: TemplateTitles,
    private val powers: PowerRepository,
) {
    private val dao = db.bossDao()

    val active: Flow<List<Boss>> = combine(
        dao.observeActive(),
        dao.observeAllPhases(),
        dao.observeAllTasks(),
        db.playerDao().observePlayer(),
        gameDay.observeToday(),
    ) { bosses, phases, tasks, player, today ->
        val lastTrial = bosses.filter { it.isTrial }.mapNotNull { it.lastAttemptDay }.maxOrNull()
        bosses.map { boss ->
            boss.toModel(phases.filter { it.bossId == boss.id }, tasks.filter { it.bossId == boss.id }, player?.level, today.toEpochDay(), lastTrial)
        }
    }

    fun observe(bossId: Long): Flow<Boss?> = combine(
        dao.observeBoss(bossId),
        dao.observePhases(bossId),
        dao.observeTasks(bossId),
        db.playerDao().observePlayer(),
        gameDay.observeToday(),
    ) { boss, phases, tasks, player, today ->
        boss?.toModel(phases, tasks, player?.level, today.toEpochDay(), dao.lastTrialAttempt())
    }

    suspend fun activeList(): List<Boss> = active.first()

    suspend fun getByPublicId(publicId: String): BossEntity? = dao.getByPublicId(publicId)

    suspend fun getTaskByPublicId(publicId: String): BossTaskEntity? = dao.getTaskByPublicId(publicId)

    /** Can a new boss (or the raid) be added now? */
    suspend fun canAdd(isRaid: Boolean): Boolean {
        val active = dao.getActive().filter { !it.isTrial }
        return BossRules.canAdd(active.count { !it.isRaid }, active.any { it.isRaid }, isRaid)
    }

    /** Adds a boss. Returns its id, or the reason it was refused. */
    suspend fun create(boss: NewBoss): Result<Long> {
        if (SafetyRules.isForbidden(boss.title) || boss.phases.any { p -> p.tasks.any { SafetyRules.isForbidden(it.title) } }) {
            return Result.failure(BossException(BossRefusal.FORBIDDEN))
        }
        if (boss.phases.size > BossRules.MAX_PHASES) return Result.failure(BossException(BossRefusal.TOO_MANY_PHASES))
        return db.withTransaction {
            if (!canAdd(boss.isRaid)) return@withTransaction Result.failure(BossException(BossRefusal.LIMIT))
            val today = gameDay.today().toEpochDay()
            val id = insertBoss(boss.title, boss.deadlineDay, boss.arcId, isTrial = false, isRaid = boss.isRaid, source = boss.source, today = today)
            val phases = boss.phases.ifEmpty { listOf(NewBossPhase(titles.defaultPhaseName(1), emptyList())) }
            phases.forEachIndexed { index, phase ->
                dao.insertPhase(BossPhaseEntity(0, id, index, phase.name.trim()))
                phase.tasks.forEach { insertTask(id, index, it) }
            }
            Result.success(id)
        }
    }

    /** Adds a task to [phase] (0-based); the next phase number creates a new phase. */
    suspend fun addTask(bossId: Long, phase: Int, task: NewBossTask, phaseName: String? = null): Boolean {
        if (SafetyRules.isForbidden(task.title)) return false
        return db.withTransaction {
            val boss = dao.getBoss(bossId) ?: return@withTransaction false
            if (boss.status != BossStatus.ACTIVE || boss.isTrial) return@withTransaction false
            val phases = dao.phases(bossId)
            if (phase > phases.size || phase < 0) return@withTransaction false
            if (phase == phases.size) {
                if (phases.size >= BossRules.MAX_PHASES) return@withTransaction false
                dao.insertPhase(BossPhaseEntity(0, bossId, phase, phaseName?.trim() ?: titles.defaultPhaseName(phase + 1)))
            }
            insertTask(bossId, phase, task)
            true
        }
    }

    /** Changes an unfinished task's estimate (SPEC: you can edit estimates). */
    suspend fun editHours(taskId: Long, hours: Double): Boolean = db.withTransaction {
        val task = dao.getTask(taskId) ?: return@withTransaction false
        if (task.done) return@withTransaction false
        dao.updateTask(task.copy(hours = BossRules.clampHours(hours), damage = BossRules.damage(hours)))
        true
    }

    /** Finishes a task: an attack on the boss. */
    suspend fun hit(taskId: Long): Boolean {
        val emitted = mutableListOf<GameEvent>()
        val dropped = mutableListOf<InventoryItemEntity>()
        val ok = db.withTransaction {
            val task = dao.getTask(taskId) ?: return@withTransaction false
            val boss = dao.getBoss(task.bossId) ?: return@withTransaction false
            if (task.done || boss.status != BossStatus.ACTIVE || boss.isTrial) return@withTransaction false
            val phases = dao.phases(boss.id)
            val tasks = dao.tasks(boss.id)
            val states = tasks.map { it.state() }
            if (!BossRules.isUnlocked(task.state(), states, phases.size)) return@withTransaction false
            val player = db.playerDao().getPlayer() ?: return@withTransaction false
            val today = gameDay.today().toEpochDay()
            val enraged = BossRules.isEnraged(today, boss.deadlineDay)
            val gear = inventory.gearBonus()
            val critical = rewards.rollCritical(
                RewardContext(
                    level = player.level,
                    streakDays = player.streakDays,
                    difficulty = 1.0,
                    baseCritChance = DerivedStats.critChance(db.playerDao().getAttributes().toStatBlock()),
                    gearEffects = gear.effects,
                ),
            )
            // Eigenstate Collapse on this task: ×3 XP on top (A16); damage is unchanged.
            val power = powers.consumeTaskMultiplier(task.id, today)
            val xp = (BossRules.taskXp(task.hours, player.level, critical, enraged) * power).toLong()
            val damage = BossRules.damage(task.hours)
            db.grantXp(
                xp,
                emitted,
                XpLog(
                    XpSource.BOSS_TASK, today, time.now().toEpochMilli(), task.id,
                    multipliers("power" to power, "crit" to if (critical) 2.0 else 1.0, "enrage" to if (enraged) 0.75 else 1.0),
                ),
            )
            dao.updateTask(task.copy(done = true, doneDay = today, damage = damage, xpAwarded = xp, critical = critical, levelAtCompletion = player.level))
            dao.updateBoss(boss.copy(lastActivityDay = today))
            emitted += GameEvent.BossHit(boss.id, damage, xp, critical)

            val after = dao.tasks(boss.id).map { it.state() }
            val before = BossRules.currentPhase(states, phases.size)
            val now = BossRules.currentPhase(after, phases.size)
            if (BossRules.isDefeated(after)) {
                defeat(boss, after, today, enraged, emitted, dropped)
            } else if (now > before && now < phases.size) {
                val name = phases[now].name
                emitted += GameEvent.Notice(NoticeKind.BOSS_PHASE, name, boss.title)
                val seed = if (now == phases.lastIndex) lore.seed(LoreKinds.BOSS_FINAL_PHASE) else lore.seed(LoreKinds.BOSS_PHASE) { it.phase == now + 1 }
                lore.unlock(seed, boss.title, emitted, LoreTarget.BOSS to boss.id, CustomLoreEvents.phase(now + 1))
            }
            true
        }
        emitted.forEach(events::emit)
        inventory.announce(dropped)
        if (ok) achievements.evaluate()
        return ok
    }

    /** Takes back a hit made today, while the boss stands and no level was gained since. */
    suspend fun undoHit(taskId: Long): Boolean = db.withTransaction {
        val task = dao.getTask(taskId) ?: return@withTransaction false
        val boss = dao.getBoss(task.bossId) ?: return@withTransaction false
        val player = db.playerDao().getPlayer() ?: return@withTransaction false
        val today = gameDay.today().toEpochDay()
        if (!task.canUndo(boss, player.level, today)) return@withTransaction false
        db.takeXp(task.xpAwarded, XpLog(XpSource.BOSS_TASK, today, time.now().toEpochMilli(), task.id))
        powers.restoreForTask(task.id, today)
        dao.updateTask(task.copy(done = false, doneDay = null, xpAwarded = 0, critical = false, levelAtCompletion = null))
        true
    }

    /** Marks a defeated boss's paper as submitted (the Peer Reviewed achievement, D8). */
    suspend fun markPaperSubmitted(bossId: Long): Boolean {
        val ok = db.withTransaction {
            val boss = dao.getBoss(bossId) ?: return@withTransaction false
            if (boss.status != BossStatus.DEFEATED || boss.paperSubmitted) return@withTransaction false
            dao.updateBoss(boss.copy(paperSubmitted = true))
            true
        }
        if (ok) achievements.evaluate()
        return ok
    }

    suspend fun abandon(bossId: Long): Boolean = db.withTransaction {
        val boss = dao.getBoss(bossId) ?: return@withTransaction false
        if (boss.status != BossStatus.ACTIVE) return@withTransaction false
        dao.updateBoss(boss.copy(status = BossStatus.ABANDONED))
        true
    }

    // ---- Trials ----

    /** Opens a Body Trial against your record for [templateKey]. Null if one is already open. */
    suspend fun createTrial(templateKey: String): Long? = db.withTransaction {
        createTrialInTransaction(templateKey, null, gameDay.today().toEpochDay())
    }

    internal suspend fun createTrialInTransaction(templateKey: String, arcId: Long?, today: Long): Long? {
        val template = TemplateLibrary[templateKey] ?: return null
        if (dao.getActive().any { it.isTrial && it.trialTemplate == templateKey }) return null
        val record = recordFor(templateKey)
        val id = insertBoss(titles.trialTitle(template), null, arcId, isTrial = true, isRaid = false, source = QuestSource.MANUAL, today = today)
        dao.updateBoss(dao.getBoss(id)!!.copy(trialTemplate = templateKey, recordToBeat = record))
        return id
    }

    /** One session's result. Beat the record to win; otherwise the Trial reopens in 4 weeks. */
    suspend fun attemptTrial(bossId: Long, result: Double): Boolean {
        val emitted = mutableListOf<GameEvent>()
        val dropped = mutableListOf<InventoryItemEntity>()
        val ok = db.withTransaction {
            val boss = dao.getBoss(bossId) ?: return@withTransaction false
            if (!boss.isTrial || boss.status != BossStatus.ACTIVE) return@withTransaction false
            val today = gameDay.today().toEpochDay()
            if (!BossRules.canAttemptTrial(today, dao.lastTrialAttempt())) return@withTransaction false
            val record = boss.recordToBeat ?: 0.0
            if (BossRules.trialWon(result, record)) {
                val player = db.playerDao().getPlayer() ?: return@withTransaction false
                db.grantXp(BossRules.trialXp(player.level), emitted, XpLog(XpSource.TRIAL, today, time.now().toEpochMilli(), boss.id))
                payTrialMastery(boss.trialTemplate)
                inventory.rollAndGrant(DropSource.BOSS, "trial:${boss.id}", "trial:${boss.publicId}")?.let(dropped::add)
                dao.updateBoss(
                    boss.copy(status = BossStatus.WON, recordToBeat = result, lastAttemptDay = today, lastResult = result, defeatedDay = today, lastActivityDay = today),
                )
                emitted += GameEvent.Notice(NoticeKind.TRIAL_WON, boss.title)
                lore.unlock(lore.seed(LoreKinds.TRIAL_WON), boss.title, emitted)
            } else {
                dao.updateBoss(boss.copy(lastAttemptDay = today, lastResult = result, lastActivityDay = today))
                emitted += GameEvent.Notice(NoticeKind.TRIAL_FAILED, boss.title)
                lore.unlock(lore.seed(LoreKinds.TRIAL_FAILED), boss.title, emitted)
            }
            true
        }
        emitted.forEach(events::emit)
        inventory.announce(dropped)
        if (ok) achievements.evaluate()
        return ok
    }

    // ---- Used by the arc and daily cycle ----

    /** The arc's finale boss, created empty when the arc ends (you add its tasks). */
    internal suspend fun ensureFinaleBoss(arc: ArcEntity, today: Long) {
        if (dao.forArc(arc.id).isNotEmpty()) return
        val id = insertBoss(arc.finaleName ?: arc.title, arc.finaleDeadline, arc.id, isTrial = false, isRaid = false, source = arc.source, today = today)
        dao.insertPhase(BossPhaseEntity(0, id, 0, titles.defaultPhaseName(1)))
    }

    /** At each reset: a boss idle for 5 days stirs; one past its deadline enrages. */
    internal suspend fun dailyChecks(today: Long, emitted: MutableList<GameEvent>) {
        dao.getActive().filter { !it.isTrial }.forEach { boss ->
            var updated = boss
            if (BossRules.stirs(today, boss.lastActivityDay, boss.lastStirDay)) {
                updated = updated.copy(lastStirDay = today)
                emitted += GameEvent.Notice(NoticeKind.BOSS_STIRS, boss.title)
                lore.unlock(lore.seed(LoreKinds.BOSS_STIRS), boss.title, emitted)
            }
            if (!boss.enrageNoticed && BossRules.isEnraged(today, boss.deadlineDay)) {
                updated = updated.copy(enrageNoticed = true)
                emitted += GameEvent.Notice(NoticeKind.BOSS_ENRAGED, boss.title)
                lore.unlock(lore.seed(LoreKinds.BOSS_ENRAGE), boss.title, emitted)
            }
            if (updated != boss) dao.updateBoss(updated)
        }
    }

    private suspend fun defeat(
        boss: BossEntity,
        tasks: List<BossTaskState>,
        today: Long,
        enraged: Boolean,
        emitted: MutableList<GameEvent>,
        dropped: MutableList<InventoryItemEntity>,
    ) {
        val player = db.playerDao().getPlayer() ?: return
        val xp = BossRules.defeatXp(BossRules.maxHp(tasks), player.level, enraged)
        db.grantXp(
            xp,
            emitted,
            XpLog(XpSource.BOSS, today, time.now().toEpochMilli(), boss.id, multipliers("enrage" to if (enraged) 0.75 else 1.0)),
        )
        dao.updateBoss(boss.copy(status = BossStatus.DEFEATED, defeatedDay = today, xpAwarded = xp, lastActivityDay = today))
        val source = if (boss.isRaid) DropSource.RAID else DropSource.BOSS
        inventory.rollAndGrant(source, "boss:${boss.id}:defeat", "boss:${boss.publicId}")?.let(dropped::add)
        emitted += GameEvent.Notice(NoticeKind.BOSS_DEFEATED, boss.title)
        achievements.earn(titles.bossTitle(boss.title), "boss:${boss.publicId}", emitted)
        lore.unlock(lore.seed(LoreKinds.BOSS_DEFEAT), boss.title, emitted, LoreTarget.BOSS to boss.id, CustomLoreEvents.DEFEAT)
    }

    private suspend fun payTrialMastery(templateKey: String?) {
        val template = templateKey?.let { TemplateLibrary[it] } ?: return
        val gear = inventory.gearBonus()
        val mastery = MasteryGrowth.masteryFor(Tier.HARD, template.stat, gear.effects, setOf(QuestTags.TRIAL))
        val attribute = db.playerDao().getAttributes().firstOrNull { it.stat == template.stat } ?: return
        val growth = MasteryGrowth.apply(attribute.value, attribute.mastery, mastery)
        db.playerDao().upsertAttributes(listOf(attribute.copy(value = growth.value, mastery = growth.mastery)))
    }

    /** The record a new Trial must beat: your best so far, else the Awakening max (baseline ÷ 2). */
    private suspend fun recordFor(templateKey: String): Double {
        val past = dao.trialsFor(templateKey).flatMap { listOfNotNull(it.recordToBeat, it.lastResult) }
        val template = TemplateLibrary[templateKey]
        val baseline = template?.baseline?.let { key -> db.playerDao().getBaselines().firstOrNull { it.key == key }?.value }
        val awakening = when (template?.baseline) {
            BaselineKey.PUSH_UPS, BaselineKey.SQUATS, BaselineKey.PLANK -> baseline?.div(2)
            else -> baseline
        }
        return (past + listOfNotNull(awakening)).maxOrNull() ?: 0.0
    }

    private suspend fun insertBoss(
        title: String,
        deadline: Long?,
        arcId: Long?,
        isTrial: Boolean,
        isRaid: Boolean,
        source: QuestSource,
        today: Long,
    ): Long = dao.insertBoss(
        BossEntity(
            publicId = "b${dao.maxId() + 1}",
            title = title.trim(),
            arcId = arcId,
            deadlineDay = deadline,
            isTrial = isTrial,
            isRaid = isRaid,
            trialTemplate = null,
            recordToBeat = null,
            lastAttemptDay = null,
            lastResult = null,
            status = BossStatus.ACTIVE,
            source = source,
            createdDay = today,
            lastActivityDay = today,
            lastStirDay = null,
            enrageNoticed = false,
            defeatedDay = null,
            xpAwarded = 0,
            paperSubmitted = false,
            createdAtMillis = time.now().toEpochMilli(),
        ),
    )

    private suspend fun insertTask(bossId: Long, phase: Int, task: NewBossTask) {
        dao.insertTask(
            BossTaskEntity(
                publicId = "t${dao.maxTaskId() + 1}",
                bossId = bossId,
                phase = phase,
                title = task.title.trim(),
                hours = BossRules.clampHours(task.hours),
                done = false,
                doneDay = null,
                damage = BossRules.damage(task.hours),
                xpAwarded = 0,
                critical = false,
                levelAtCompletion = null,
            ),
        )
    }

    private fun BossTaskEntity.state() = BossTaskState(phase, hours, done)

    private fun BossTaskEntity.canUndo(boss: BossEntity, level: Int, today: Long) =
        done && boss.status == BossStatus.ACTIVE && doneDay == today && levelAtCompletion == level

    private fun BossEntity.toModel(
        phases: List<BossPhaseEntity>,
        tasks: List<BossTaskEntity>,
        level: Int?,
        today: Long,
        lastTrial: Long?,
    ): Boss {
        val states = tasks.map { it.state() }
        val phaseCount = phases.size
        return Boss(
            id = id,
            publicId = publicId,
            title = title,
            arcId = arcId,
            deadlineDay = deadlineDay,
            isTrial = isTrial,
            isRaid = isRaid,
            trialTemplate = trialTemplate,
            recordToBeat = recordToBeat,
            lastAttemptDay = lastAttemptDay,
            lastResult = lastResult,
            status = status,
            phases = phases.sortedBy { it.position }.map { it.name },
            tasks = tasks.map { t ->
                BossTask(
                    id = t.id,
                    publicId = t.publicId,
                    phase = t.phase,
                    title = t.title,
                    hours = t.hours,
                    damage = BossRules.damage(t.hours),
                    done = t.done,
                    unlocked = BossRules.isUnlocked(t.state(), states, phaseCount),
                    critical = t.critical,
                    canUndo = level != null && t.canUndo(this, level, today),
                )
            },
            maxHp = BossRules.maxHp(states),
            hp = BossRules.hp(states),
            currentPhase = BossRules.currentPhase(states, phaseCount),
            enraged = BossRules.isEnraged(today, deadlineDay),
            paperSubmitted = paperSubmitted,
            trialOpen = isTrial && status == BossStatus.ACTIVE && BossRules.canAttemptTrial(today, lastTrial),
        )
    }
}

class BossException(val reason: BossRefusal) : Exception(reason.name)
