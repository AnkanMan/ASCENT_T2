package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.TemplateTitles
import com.tirthankar.ascent.data.backup.RestoreFinisher
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.BodyTargetEntity
import com.tirthankar.ascent.data.db.DailySnapshotEntity
import com.tirthankar.ascent.data.db.GoalStatus
import com.tirthankar.ascent.data.db.InventoryItemEntity
import com.tirthankar.ascent.data.db.QuestEntity
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.db.RealmDifficultyEntity
import com.tirthankar.ascent.data.settings.AscentSettings
import com.tirthankar.ascent.data.settings.SettingsRepository
import com.tirthankar.ascent.engine.BodyLoad
import com.tirthankar.ascent.engine.BodyTargetMemory
import com.tirthankar.ascent.engine.DayInput
import com.tirthankar.ascent.engine.DayResolver
import com.tirthankar.ascent.engine.PassiveRules
import com.tirthankar.ascent.engine.Periods
import com.tirthankar.ascent.engine.ShieldDecision
import com.tirthankar.ascent.engine.ShieldRules
import com.tirthankar.ascent.data.NoticeKind
import com.tirthankar.ascent.engine.DerivedStats
import com.tirthankar.ascent.engine.DifficultyController
import com.tirthankar.ascent.engine.Fatigue
import com.tirthankar.ascent.engine.GameClock
import com.tirthankar.ascent.engine.ObjectiveResult
import com.tirthankar.ascent.engine.PenaltyRules
import com.tirthankar.ascent.engine.PlannerInput
import com.tirthankar.ascent.engine.QuestTemplate
import com.tirthankar.ascent.engine.ReEntry
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.RealmDifficulty
import com.tirthankar.ascent.engine.SafetyRules
import com.tirthankar.ascent.engine.TemplatePlanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class CycleResult(
    val resolvedDays: Int,
    /** True when today's Daily Quest was issued by this run. */
    val issuedToday: Boolean,
    val todayObjectives: Int,
    /** System notices from this run (Gates closed, a penalty, a boss stirring), for notifications. */
    val notices: List<GameEvent.Notice> = emptyList(),
)

/** Today's Daily Quest progress, for the reminder notification. */
data class DailyProgress(val objectives: Int, val done: Int) {
    val complete: Boolean get() = objectives > 0 && done == objectives
}

/**
 * The daily reset (Task 27), run by WorkManager at the reset and on every app open:
 *
 * 1. Each game day before today is resolved once: streak, HP, MP, difficulty update,
 *    a DailySnapshot, open daily quests expire, repeating quests come back.
 * 2. Crossing a Sunday starts a new week: deload check, weekly quests and goals close.
 * 3. Today's Daily Quest is issued from templates if it hasn't been.
 *
 * Running it twice changes nothing; days missed while the phone was off are caught up.
 */
@Singleton
class DailyCycle @Inject constructor(
    private val db: AscentDatabase,
    private val settings: SettingsRepository,
    private val gameDay: GameDay,
    private val titles: TemplateTitles,
    private val quests: QuestRepository,
    private val events: GameEvents,
    private val sideQuests: SideQuestService,
    private val gates: GateRepository,
    private val bosses: BossRepository,
    private val arcs: ArcRepository,
    private val achievements: AchievementRepository,
    private val inventory: InventoryRepository,
    private val lore: LoreRepository,
    private val skills: SkillRepository,
    private val powers: PowerRepository,
    private val trials: RankTrialRepository,
    private val habits: HabitRepository,
    private val restore: RestoreFinisher,
) {
    private val mutex = Mutex()
    private val playerDao = db.playerDao()
    private val questDao = db.questDao()

    suspend fun ensureUpToDate(): CycleResult = mutex.withLock {
        // A restore just swapped the files in: its gap days become neutral (D7).
        restore.finishIfPending()
        val neutralBefore = restore.neutralBefore() ?: Long.MIN_VALUE
        val s = settings.current()
        val today = gameDay.clock(s).gameDay(gameDay.now())
        var resolved = 0
        var issued = false
        val emitted = mutableListOf<GameEvent>()
        val dropped = mutableListOf<InventoryItemEntity>()
        var hasPlayer = false
        db.withTransaction {
            val player = playerDao.getPlayer() ?: return@withTransaction
            hasPlayer = true
            var day = LocalDate.ofEpochDay(player.currentDay)
            if (day.isBefore(today.minusDays(MAX_CATCH_UP_DAYS))) {
                // Too long away: skip straight to the catch-up window rather than replay months.
                day = today.minusDays(MAX_CATCH_UP_DAYS)
                playerDao.upsertPlayer(player.copy(currentDay = day.toEpochDay()))
            }
            while (day.isBefore(today)) {
                if (day.toEpochDay() < neutralBefore) {
                    resolveNeutral(day)
                } else {
                    issueIfNeeded(day, s)
                    resolve(day, s, emitted)
                }
                day = day.plusDays(1)
                resolved++
                powers.expireBefore(day.toEpochDay())
                gates.closeExpired(day.toEpochDay(), emitted, dropped)
                trials.closeExpired(day.toEpochDay(), emitted, dropped)
                bosses.dailyChecks(day.toEpochDay(), emitted)
                if (GameClock.isWeekStart(day)) startWeek(day, s)
            }
            issued = issueIfNeeded(today, s)
            // The first Phase 3 run (or a new player) gets this week's board at once.
            if (playerDao.getPlayer()?.lastBoardWeek == null) spawnBoard(GameClock.weekStart(today), s)
            sideQuests.issueForToday(today, s, { t -> engineTargetOn(today, s, t) }, emitted)
            lore.syncLevels(playerDao.getPlayer()?.level ?: 1, emitted)
            // Opens at once for a player already past a threshold (PHASE4.md section 4).
            trials.openIfAvailable(today, { t -> engineTargetOn(today, s, t) }, bodyMemory(), emitted)
        }
        val objectives = questDao.getDailyObjectives(today.toEpochDay()).size
        if (issued) events.emit(GameEvent.DailyIssued(today.toEpochDay(), objectives))
        emitted.forEach(events::emit)
        inventory.announce(dropped)
        if (hasPlayer) {
            // Series backfilled by the v4 migration get their cached strength.
            habits.warm()
            arcs.sync()
            achievements.evaluate()
        }
        CycleResult(resolved, issued, objectives, emitted.filterIsInstance<GameEvent.Notice>())
    }

    suspend fun todayProgress(): DailyProgress {
        val objectives = questDao.getDailyObjectives(gameDay.today().toEpochDay())
        return DailyProgress(objectives.size, objectives.count { it.status == QuestStatus.DONE })
    }

    /** True when today is a rest day or a recovery day. */
    suspend fun isRestDayToday(): Boolean {
        val s = settings.current()
        val today = gameDay.clock(s).gameDay(gameDay.now())
        val player = playerDao.getPlayer()
        return today.dayOfWeek in s.restDays || player?.recoveryDay == today.toEpochDay()
    }

    /**
     * The engine's own target for [template] today (difficulty, fatigue and Body caps),
     * used to clamp targets that arrive from Claude. Doesn't store anything.
     */
    suspend fun engineTarget(template: QuestTemplate): Double? {
        val s = settings.current()
        val today = gameDay.clock(s).gameDay(gameDay.now())
        return engineTargetOn(today, s, template)
    }

    private suspend fun engineTargetOn(day: LocalDate, s: AscentSettings, template: QuestTemplate): Double? {
        val input = plannerInput(day, s) ?: return null
        return TemplatePlanner.sizeTarget(template, input)
    }

    /** How much the +10% weekly cap still allows for [template] this week (for Claude's Gates). */
    suspend fun bodyHeadroom(template: QuestTemplate, currentTarget: Double?): Double =
        com.tirthankar.ascent.engine.GateRules.bodyHeadroom(
            bodyMemory()[template.key],
            currentTarget,
            GameClock.weekStart(gameDay.today()).toEpochDay(),
            template.unit,
        )

    /** Body caps for each template, for the Gate board's +10% check. */
    private suspend fun bodyMemory(): Map<String, BodyTargetMemory> =
        playerDao.getBodyTargets().associate { it.templateKey to BodyTargetMemory(it.weekStartDay, it.weekCap, it.lastTarget) }

    private suspend fun spawnBoard(weekStart: LocalDate, s: AscentSettings) {
        val day = maxOf(weekStart, LocalDate.ofEpochDay(playerDao.getPlayer()?.currentDay ?: weekStart.toEpochDay()))
        gates.spawnBoard(weekStart, { t -> engineTargetOn(day, s, t) }, bodyMemory())
    }

    private suspend fun resolve(day: LocalDate, s: AscentSettings, emitted: MutableList<GameEvent>) {
        val epoch = day.toEpochDay()
        val player = playerDao.getPlayer() ?: return
        val stats = playerDao.getAttributes().toStatBlock()
        val objectives = questDao.getDailyObjectives(epoch)
        val restDay = day.dayOfWeek in s.restDays || player.recoveryDay == epoch
        val equipped = inventory.fold(db.inventoryDao().equipped())
        val effects = skills.effects(player) + equipped.effects
        val outcome = DayResolver.resolve(
            DayInput(
                objectives = objectives.map { ObjectiveResult(it.realm, it.status == QuestStatus.DONE) },
                isRestDay = restDay,
                hp = player.hp,
                maxHp = DerivedStats.maxHp(stats),
                maxMp = DerivedStats.maxMp(stats),
                streakDays = player.streakDays,
                consecutiveMisses = player.consecutiveMisses,
            ),
        )

        val rows = difficultyRows()
        var engine = DifficultyController.updateAll(
            rows.mapValues { (_, row) -> RealmDifficulty(row.multiplier, row.completionEma) },
            outcome.completionByRealm,
        )
        if (outcome.triggersReEntry) engine = ReEntry.apply(engine)

        // Phase 3: a missed penalty quest costs 10 HP; gear HP regeneration restores some.
        val maxHp = DerivedStats.maxHp(stats)
        val missedPenalties = questDao.getKindOnDay(QuestKind.PENALTY, epoch).count { it.status == QuestStatus.OPEN }
        val regen = (equipped.hpRegen * maxHp).roundToInt()
        // Recovery Aura: a completed rest-day Daily restores 1.5× the HP.
        val aura = if (restDay && outcome.dailyComplete) {
            PassiveRules.restDayHp(DayResolver.REST_DAY_DAILY_HP, effects) - DayResolver.REST_DAY_DAILY_HP
        } else {
            0
        }
        val hpAfter = (outcome.hp + aura + missedPenalties * PenaltyRules.MISSED_HP + regen).coerceIn(0, maxHp)

        // Streak Shield (A9): a missed Daily keeps the streak while a weekly (or Iron
        // Lattice's monthly) shield is left. HP rules are unchanged. Unbroken needs an
        // unshielded miss, so the miss is recorded either way.
        val missed = outcome.dailyIssued && !outcome.dailyComplete
        val weekKey = Periods.weekKey(day)
        val monthKey = Periods.monthKey(day)
        val usedWeek = if (player.shieldWeekKey == weekKey) player.shieldsUsedWeek else 0
        val usedMonth = if (player.shieldMonthKey == monthKey) player.shieldsUsedMonth else 0
        val shield = if (missed) ShieldRules.resolveMiss(usedWeek, usedMonth, effects) else ShieldDecision(shielded = false)
        if (shield.shielded) emitted += GameEvent.Notice(NoticeKind.STREAK_SHIELDED, player.streakDays.toString())
        val streak = if (shield.shielded) player.streakDays else outcome.streakDays
        playerDao.upsertDifficulties(
            Realm.entries.map { realm ->
                rows.getValue(realm).copy(
                    multiplier = engine.getValue(realm).multiplier,
                    completionEma = engine.getValue(realm).completionEma,
                )
            },
        )

        fun count(realm: Realm, done: Boolean? = null) = objectives.count {
            it.realm == realm && (done == null || (it.status == QuestStatus.DONE) == done)
        }
        db.snapshotDao().insert(
            DailySnapshotEntity(
                day = epoch,
                level = player.level,
                totalXp = player.totalXp,
                str = stats.str, agi = stats.agi, int = stats.int,
                foc = stats.foc, wis = stats.wis, vit = stats.vit,
                hp = hpAfter,
                mp = outcome.mp,
                streakDays = streak,
                objectives = objectives.size,
                completed = objectives.count { it.status == QuestStatus.DONE },
                bodyDone = count(Realm.BODY, true), bodyTotal = count(Realm.BODY),
                mindDone = count(Realm.MIND, true), mindTotal = count(Realm.MIND),
                selfDone = count(Realm.SELF, true), selfTotal = count(Realm.SELF),
                dailyComplete = outcome.dailyComplete,
                restDay = restDay,
            ),
        )

        // Habit strength (PHASE5.md section 1): every issued daily series records the day,
        // done 1, otherwise 0, before open quests expire. Rest days don't count.
        if (!restDay) recordHabits(questDao.getDay(epoch))

        // Repeating quests come back tomorrow; everything still open closes.
        val repeating = questDao.getDay(epoch).filter { it.recurring }
        questDao.expireBefore(QuestScope.DAILY, epoch + 1)
        repeating.forEach { copyForward(it, day.plusDays(1)) }

        playerDao.upsertPlayer(
            player.copy(
                hp = hpAfter,
                mp = outcome.mp,
                streakDays = streak,
                bestStreak = maxOf(player.bestStreak, streak),
                consecutiveMisses = outcome.consecutiveMisses,
                recoveryDay = if (hpAfter == 0) epoch + 1 else player.recoveryDay?.takeIf { it > epoch },
                currentDay = epoch + 1,
                shieldWeekKey = if (shield.shielded && !shield.fromMonthly) weekKey else player.shieldWeekKey,
                shieldsUsedWeek = if (shield.shielded && !shield.fromMonthly) usedWeek + 1 else player.shieldsUsedWeek,
                shieldMonthKey = if (shield.fromMonthly) monthKey else player.shieldMonthKey,
                shieldsUsedMonth = if (shield.fromMonthly) usedMonth + 1 else player.shieldsUsedMonth,
                lastMissDay = if (missed) epoch else player.lastMissDay,
                lastMissShielded = if (missed) shield.shielded else player.lastMissShielded,
            ),
        )
    }

    private suspend fun startWeek(weekStart: LocalDate, s: AscentSettings) {
        val start = weekStart.toEpochDay()
        val body = difficultyRows().getValue(Realm.BODY)
        val load = SafetyRules.startWeek(BodyLoad(body.lastWeekMultiplier, body.risingWeeks, body.deload), body.multiplier)
        playerDao.upsertDifficulties(
            listOf(body.copy(lastWeekMultiplier = load.lastWeekMultiplier, risingWeeks = load.risingWeeks, deload = load.deload)),
        )

        // A week that ran through a restore gap is unknown, not missed: no weekly observation.
        val gap = db.snapshotDao().getRange(start - 7, start - 1).any { it.neutral }
        if (!gap) recordHabits(questDao.getWeek(start - 7))
        val repeating = questDao.getWeek(start - 7).filter { it.recurring }
        questDao.expireBefore(QuestScope.WEEKLY, start)
        repeating.forEach { copyForward(it, weekStart) }

        val totals = db.questDao().observeGoalTotals().first()
        db.goalDao().getWeeklyBefore(start).forEach { goal ->
            val progress = GoalRepository.progress(goal, totals)
            val done = goal.target != null && progress >= goal.target
            db.goalDao().update(goal.copy(status = if (done) GoalStatus.DONE else GoalStatus.CLOSED))
        }

        // D3: the Gate board spawns at the Sunday reset.
        spawnBoard(weekStart, s)
    }

    private suspend fun plannerInput(day: LocalDate, s: AscentSettings): PlannerInput? {
        val player = playerDao.getPlayer() ?: return null
        val stats = playerDao.getAttributes().toStatBlock()
        val rows = difficultyRows()
        val body = rows.getValue(Realm.BODY)
        return PlannerInput(
            day = day,
            isRestDay = day.dayOfWeek in s.restDays,
            isRecoveryDay = player.recoveryDay == day.toEpochDay(),
            fatigued = Fatigue.isFatigued(player.hp, DerivedStats.maxHp(stats)),
            difficulty = rows.mapValues { it.value.multiplier },
            bodyLoad = BodyLoad(body.lastWeekMultiplier, body.risingWeeks, body.deload),
            baselines = playerDao.getBaselines().associate { it.key to it.value },
            bodyMemory = playerDao.getBodyTargets().associate {
                it.templateKey to BodyTargetMemory(it.weekStartDay, it.weekCap, it.lastTarget)
            },
            trainingDayIndex = player.trainingDayIndex,
        )
    }

    /** Issues [day]'s Daily Quest from templates, once. Returns true if it issued. */
    private suspend fun issueIfNeeded(day: LocalDate, s: AscentSettings): Boolean {
        val epoch = day.toEpochDay()
        val player = playerDao.getPlayer() ?: return false
        if (player.lastIssuedDay != null && player.lastIssuedDay >= epoch) return false
        val plan = TemplatePlanner.plan(plannerInput(day, s) ?: return false)
        val lightsOut = SafetyRules.latestLightsOut(s.wakeTime)
        plan.quests.forEach { planned ->
            val template = planned.template
            quests.insert(
                NewQuest(
                    title = titles.title(template, lightsOut),
                    realm = template.realm,
                    stat = template.stat,
                    tier = template.tier,
                    target = planned.target,
                    unitLabel = titles.unitLabel(template),
                    scope = QuestScope.DAILY,
                    source = QuestSource.TEMPLATE,
                    isDaily = true,
                    templateKey = template.key,
                ),
                day,
            )
        }
        playerDao.upsertBodyTargets(
            plan.bodyMemory.map { (key, m) -> BodyTargetEntity(key, m.weekStartDay, m.weekCap, m.lastTarget) },
        )
        playerDao.upsertPlayer(player.copy(lastIssuedDay = epoch, trainingDayIndex = plan.nextTrainingDayIndex))
        return true
    }

    /**
     * A day between a backup and its restore (D7): unknown, not missed. Nothing is judged:
     * no penalty, no HP or MP change, the streak and difficulty hold, no strength update.
     * The snapshot is marked neutral (and rest-like, so chains neither grow nor break);
     * open quests close and repeating ones come back as usual.
     */
    private suspend fun resolveNeutral(day: LocalDate) {
        val epoch = day.toEpochDay()
        val player = playerDao.getPlayer() ?: return
        val stats = playerDao.getAttributes().toStatBlock()
        db.snapshotDao().insert(
            DailySnapshotEntity(
                day = epoch,
                level = player.level,
                totalXp = player.totalXp,
                str = stats.str, agi = stats.agi, int = stats.int,
                foc = stats.foc, wis = stats.wis, vit = stats.vit,
                hp = player.hp,
                mp = player.mp,
                streakDays = player.streakDays,
                objectives = 0,
                completed = 0,
                bodyDone = 0, bodyTotal = 0,
                mindDone = 0, mindTotal = 0,
                selfDone = 0, selfTotal = 0,
                dailyComplete = false,
                restDay = true,
                neutral = true,
            ),
        )
        val repeating = questDao.getDay(epoch).filter { it.recurring }
        questDao.expireBefore(QuestScope.DAILY, epoch + 1)
        repeating.forEach { copyForward(it, day.plusDays(1)) }
        playerDao.upsertPlayer(player.copy(currentDay = epoch + 1))
    }

    /** Final observations for a closing period: issued series quests that are done or still open. */
    private suspend fun recordHabits(quests: List<QuestEntity>) {
        val now = gameDay.now().toEpochMilli()
        quests.filter { it.seriesId != null && (it.status == QuestStatus.DONE || it.status == QuestStatus.OPEN) }
            .forEach { habits.record(it, done = it.status == QuestStatus.DONE, at = now) }
    }

    private suspend fun copyForward(quest: QuestEntity, day: LocalDate) {
        quests.insert(
            NewQuest(
                title = quest.title,
                realm = quest.realm,
                stat = quest.stat,
                tier = quest.tier,
                target = quest.target,
                unitLabel = quest.unitLabel,
                scope = quest.scope,
                recurring = true,
                goalId = quest.goalId,
                source = quest.source,
                isDaily = quest.isDaily,
                templateKey = quest.templateKey,
                tags = quest.tags.split(',').filter { it.isNotBlank() }.toSet(),
                // The copy continues the same habit series (PHASE5.md section 1).
                seriesId = quest.seriesId,
            ),
            day,
        )
    }

    private suspend fun difficultyRows(): Map<Realm, RealmDifficultyEntity> {
        val rows = playerDao.getDifficulties().associateBy { it.realm }
        return Realm.entries.associateWith { realm ->
            rows[realm] ?: RealmDifficultyEntity(
                realm = realm,
                multiplier = DifficultyController.INITIAL_MULTIPLIER,
                completionEma = DifficultyController.TARGET_COMPLETION,
                lastWeekMultiplier = DifficultyController.INITIAL_MULTIPLIER,
                risingWeeks = 0,
                deload = false,
            )
        }
    }

    companion object {
        /** Days replayed after a long absence; older days are skipped without penalty. */
        const val MAX_CATCH_UP_DAYS = 60L
    }
}
