package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.TemplateTitles
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.InventoryItemEntity
import com.tirthankar.ascent.data.db.PlayerEntity
import com.tirthankar.ascent.data.db.QuestEntity
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.model.Quest
import com.tirthankar.ascent.data.model.toModel
import com.tirthankar.ascent.data.settings.SettingsRepository
import com.tirthankar.ascent.engine.DerivedStats
import com.tirthankar.ascent.engine.DifficultyController
import com.tirthankar.ascent.engine.DropSource
import com.tirthankar.ascent.engine.Effect
import com.tirthankar.ascent.engine.EmergencyRules
import com.tirthankar.ascent.engine.GameClock
import com.tirthankar.ascent.engine.KnownTags
import com.tirthankar.ascent.engine.Leveling
import com.tirthankar.ascent.engine.MasteryGrowth
import com.tirthankar.ascent.engine.PassiveRules
import com.tirthankar.ascent.engine.PowerHandlers
import com.tirthankar.ascent.engine.PowerRules
import com.tirthankar.ascent.engine.TemplateTags
import com.tirthankar.ascent.engine.PenaltyRules
import com.tirthankar.ascent.engine.QuestRewardInput
import com.tirthankar.ascent.engine.QuestTags
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.RewardCalculator
import com.tirthankar.ascent.engine.RewardContext
import com.tirthankar.ascent.engine.SafetyRules
import com.tirthankar.ascent.engine.HabitSeries
import com.tirthankar.ascent.engine.GrindGuard
import com.tirthankar.ascent.engine.GuardContent
import com.tirthankar.ascent.engine.QuestOrigin
import com.tirthankar.ascent.engine.QuestXp
import com.tirthankar.ascent.data.ContentRepository
import com.tirthankar.ascent.data.db.XpSource
import kotlinx.coroutines.flow.map
import com.tirthankar.ascent.engine.SideQuestRules
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.TargetUnit
import com.tirthankar.ascent.engine.TemplateMatcher
import com.tirthankar.ascent.engine.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** A quest to add by hand, from Claude, or from a template. */
data class NewQuest(
    val title: String,
    val realm: Realm,
    val stat: Stat,
    val tier: Tier,
    val target: Double,
    val unitLabel: String,
    val scope: QuestScope,
    val recurring: Boolean = false,
    val goalId: Long? = null,
    val source: QuestSource = QuestSource.MANUAL,
    /** Part of the Daily Quest. Quests you add yourself never are (D3). */
    val isDaily: Boolean = false,
    val templateKey: String? = null,
    val kind: QuestKind = QuestKind.NORMAL,
    /** Side quests: how many days they stay open (1–3). */
    val expiryDays: Int? = null,
    /** Emergency quests: the end of the window. */
    val expiresAtMillis: Long? = null,
    /** Phase 4 tags (social, paper, meditation, …); unknown ones are dropped. */
    val tags: Set<String> = emptySet(),
    /** The habit series to continue (the daily copy of a recurring quest). Null derives one ([seriesFor]). */
    val seriesId: String? = null,
)

/**
 * Today's grind guard (PHASE5.md section 2): self-added quests completed today,
 * how many of them paid full XP, and the slots they used.
 */
data class GuardStatus(
    val extrasToday: Int = 0,
    val fullXp: Int = 0,
    val usedSlots: Int = 0,
    val content: GuardContent = GuardContent(),
) {
    /** What a self-added quest of [tier] would pay if completed now. */
    fun multiplierFor(tier: Tier): Double = GrindGuard.pay(usedSlots, tier, QuestOrigin.SELF_ADDED, content).multiplier

    /** What the next Normal self-added quest pays, for "next pays NN%". */
    val nextMultiplier: Double get() = GrindGuard.nextMultiplier(usedSlots, content)
}

/** Changes Claude's `edit` op can make. Null fields are unchanged. */
data class QuestEdit(
    val title: String? = null,
    val tier: Tier? = null,
    val target: Double? = null,
    val unitLabel: String? = null,
    val recurring: Boolean? = null,
    val goalId: Long? = null,
)

@Singleton
class QuestRepository @Inject constructor(
    private val db: AscentDatabase,
    private val gameDay: GameDay,
    private val time: TimeSource,
    private val events: GameEvents,
    private val rewards: RewardCalculator,
    private val random: Random,
    private val settings: SettingsRepository,
    private val inventory: InventoryRepository,
    private val gates: GateRepository,
    private val arcs: ArcRepository,
    private val achievements: AchievementRepository,
    private val skills: SkillRepository,
    private val powers: PowerRepository,
    private val trials: RankTrialRepository,
    private val content: ContentRepository,
    private val habits: HabitRepository,
) {
    private val questDao = db.questDao()
    private val playerDao = db.playerDao()

    /** The grind guard for game day [day], updated as quests are completed and undone. */
    fun observeGuard(day: LocalDate): Flow<GuardStatus> =
        questDao.observeCompletedOn(day.toEpochDay()).map { done -> guardStatus(done) }

    /** Today's grind guard, once. */
    suspend fun guardToday(): GuardStatus {
        val epoch = gameDay.today().toEpochDay()
        return guardStatus(questDao.completedBetween(epoch, epoch))
    }

    private fun guardStatus(completedToday: List<QuestEntity>): GuardStatus {
        val guard = content.content.guard
        val mine = completedToday.filter { it.guardOrigin() == QuestOrigin.SELF_ADDED }
        return GuardStatus(
            extrasToday = mine.size,
            fullXp = mine.count { it.guardMultiplier >= 1.0 },
            usedSlots = GrindGuard.usedSlots(mine.map { it.tier }, guard),
            content = guard,
        )
    }

    fun observeDay(day: LocalDate): Flow<List<Quest>> =
        combine(questDao.observeDay(day.toEpochDay()), playerDao.observePlayer()) { quests, player ->
            quests.map { it.toModel(canUndo = it.canUndo(player, day.toEpochDay(), GameClock.weekStart(day).toEpochDay())) }
        }

    fun observeWeek(day: LocalDate): Flow<List<Quest>> {
        val weekStart = GameClock.weekStart(day).toEpochDay()
        return combine(questDao.observeWeek(weekStart), playerDao.observePlayer()) { quests, player ->
            quests.map { it.toModel(canUndo = it.canUndo(player, day.toEpochDay(), weekStart)) }
        }
    }

    /** Side quests open today, plus those finished today. */
    fun observeSide(day: LocalDate): Flow<List<Quest>> =
        combine(questDao.observeSide(day.toEpochDay()), playerDao.observePlayer()) { quests, player ->
            quests.map { it.toModel(canUndo = it.canUndo(player, day.toEpochDay(), GameClock.weekStart(day).toEpochDay())) }
        }

    suspend fun getByPublicId(publicId: String): Quest? {
        val today = gameDay.today()
        val player = playerDao.getPlayer()
        return questDao.getByPublicId(publicId)?.let {
            it.toModel(it.canUndo(player, today.toEpochDay(), GameClock.weekStart(today).toEpochDay()))
        }
    }

    /** Today's quests, this week's, and open side quests. */
    suspend fun today(): List<Quest> {
        val today = gameDay.today()
        val player = playerDao.getPlayer()
        val weekStart = GameClock.weekStart(today).toEpochDay()
        val all = questDao.getDay(today.toEpochDay()) + questDao.getWeek(weekStart) + questDao.getOpenSide(today.toEpochDay())
        return all.map { it.toModel(it.canUndo(player, today.toEpochDay(), weekStart)) }
    }

    /** Adds a quest for today (daily) or this week (weekly). Returns null for forbidden quests. */
    suspend fun add(quest: NewQuest): Long? {
        if (SafetyRules.isForbidden(quest.title)) return null
        val today = gameDay.today()
        return db.withTransaction { insert(quest, today) }
    }

    /**
     * The habit series a new quest belongs to (PHASE5.md section 1): a recurring quest
     * starts its own chain; a template Daily objective joins its template's series;
     * anything else is a one-off.
     */
    internal fun seriesFor(quest: NewQuest, publicId: String): String? = when {
        quest.recurring && quest.kind == QuestKind.NORMAL -> HabitSeries.forRecurring(publicId)
        quest.source == QuestSource.TEMPLATE && quest.kind == QuestKind.NORMAL && quest.templateKey != null ->
            HabitSeries.forTemplate(quest.templateKey)
        else -> null
    }

    internal suspend fun insert(quest: NewQuest, today: LocalDate): Long {
        val day = when (quest.scope) {
            QuestScope.DAILY -> today.toEpochDay()
            QuestScope.WEEKLY -> GameClock.weekStart(today).toEpochDay()
        }
        val unit = TargetUnit.fromLabel(quest.unitLabel)
        val xpFactor = when (quest.kind) {
            QuestKind.SIDE -> SideQuestRules.XP_FACTOR
            QuestKind.EMERGENCY -> EmergencyRules.XP_FACTOR
            else -> 1.0
        }
        val publicId = PublicIds.newQuestId(random) { questDao.publicIdExists(it) }
        return questDao.insert(
            QuestEntity(
                publicId = publicId,
                title = quest.title.trim(),
                realm = quest.realm,
                stat = quest.stat,
                tier = quest.tier,
                target = quest.target.coerceAtLeast(unit.fineStep),
                unit = unit,
                unitLabel = quest.unitLabel.trim(),
                scope = quest.scope,
                isDaily = quest.isDaily && quest.scope == QuestScope.DAILY && quest.kind == QuestKind.NORMAL,
                recurring = quest.recurring && quest.kind == QuestKind.NORMAL,
                source = quest.source,
                templateKey = quest.templateKey,
                goalId = quest.goalId,
                day = day,
                status = QuestStatus.OPEN,
                createdAtMillis = time.now().toEpochMilli(),
                kind = quest.kind,
                expiresDay = if (quest.kind == QuestKind.SIDE) {
                    SideQuestRules.lastDay(day, quest.expiryDays ?: SideQuestRules.DEFAULT_CLAUDE_EXPIRY_DAYS)
                } else {
                    null
                },
                expiresAtMillis = quest.expiresAtMillis,
                xpFactor = xpFactor,
                tags = quest.tags.map { it.trim().lowercase() }.filter { it in KnownTags.ALL }.sorted().joinToString(","),
                seriesId = quest.seriesId ?: seriesFor(quest, publicId),
            ),
        )
    }

    /** Edits an open quest. Returns false if it's gone, closed or the new title is forbidden. */
    suspend fun edit(questId: Long, edit: QuestEdit): Boolean = db.withTransaction {
        val quest = questDao.getById(questId) ?: return@withTransaction false
        if (quest.status != QuestStatus.OPEN) return@withTransaction false
        if (edit.title != null && SafetyRules.isForbidden(edit.title)) return@withTransaction false
        val unitLabel = edit.unitLabel ?: quest.unitLabel
        questDao.update(
            quest.copy(
                title = edit.title?.trim() ?: quest.title,
                tier = edit.tier ?: quest.tier,
                target = edit.target ?: quest.target,
                unitLabel = unitLabel,
                unit = TargetUnit.fromLabel(unitLabel),
                recurring = edit.recurring ?: quest.recurring,
                goalId = edit.goalId ?: quest.goalId,
                // Made recurring: it starts its own series. A title edit keeps the series.
                seriesId = quest.seriesId ?: if (edit.recurring == true) HabitSeries.forRecurring(quest.publicId) else null,
            ),
        )
        true
    }

    /**
     * Removes an open quest. Completed quests keep their XP and stay in history. A power
     * cast on it today gives its MP back (PHASE4.md section 2).
     */
    suspend fun remove(questId: Long): Boolean {
        val today = gameDay.today().toEpochDay()
        return db.withTransaction {
            val quest = questDao.getById(questId) ?: return@withTransaction false
            if (quest.status != QuestStatus.OPEN) return@withTransaction false
            questDao.update(quest.copy(status = QuestStatus.REMOVED))
            powers.refundForQuest(quest.id, today)
            true
        }
    }

    /**
     * Completes a quest in one transaction (Task 21): XP via RewardCalculator (gear,
     * tags, crit), mastery and organic stat growth, level-ups with free and skill points.
     * Phase 3: side quests pay 0.75× and roll loot 10% of the time, emergency quests 1.5×
     * with a 20% roll, a penalty quest gives 10 HP back.
     * Phase 4: skill and class passives (chains, Resonance, Literature Sense, Bonds,
     * Equilibrium) under the +75% cap, and powers on top of it (A10, A16).
     */
    suspend fun complete(questId: Long): Boolean {
        val today = gameDay.today()
        val epoch = today.toEpochDay()
        val emitted = mutableListOf<GameEvent>()
        val dropped = mutableListOf<InventoryItemEntity>()
        val s = settings.current()
        var partner: Long? = null
        val done = db.withTransaction {
            val quest = questDao.getById(questId) ?: return@withTransaction false
            if (quest.status != QuestStatus.OPEN || !quest.isCurrent(today)) return@withTransaction false
            val player = playerDao.getPlayer() ?: return@withTransaction false
            val attributes = playerDao.getAttributes()
            val stats = attributes.toStatBlock()
            val d = playerDao.getDifficulties().firstOrNull { it.realm == quest.realm }?.multiplier
                ?: DifficultyController.INITIAL_MULTIPLIER
            val gear = inventory.gearBonus()
            val isRestDay = today.dayOfWeek in s.restDays || player.recoveryDay == epoch
            val passives = skills.effects(player)
            val effects = passives + equilibriumBonus(player, passives, epoch)
            val allEffects = gear.effects + effects
            val tags = tags(quest, today, isRestDay)
            val completedToday = questDao.completedBetween(epoch, epoch)
            val boost = powers.consumeBoost(quest, epoch)
            // Grind guard (PHASE5.md section 2): self-added quests past the 3rd slot today pay less.
            val guard = content.content.guard
            val guardPay = GrindGuard.pay(guardStatus(completedToday).usedSlots, quest.tier, quest.guardOrigin(), guard)

            val reward = rewards.questReward(
                QuestRewardInput(
                    realm = quest.realm,
                    tier = quest.tier,
                    stats = setOf(quest.stat),
                    tags = tags,
                    kindMultiplier = quest.xpFactor,
                    powerMultiplier = boost.xp,
                    guardMultiplier = guardPay.multiplier,
                ),
                RewardContext(
                    level = player.level,
                    streakDays = player.streakDays,
                    difficulty = d,
                    baseCritChance = DerivedStats.critChance(stats),
                    effects = effects,
                    gearEffects = gear.effects,
                    chains = chains(quest, epoch, isRestDay),
                    realmsCompletedToday = (completedToday.map { it.realm } + quest.realm).toSet().size,
                ),
            )
            // A full Hyperfocus session doubles INT mastery (Task 21).
            // The guard scales mastery as well as XP.
            val mastery = GrindGuard.scaleMastery(
                (MasteryGrowth.masteryFor(quest.tier, quest.stat, allEffects, tags) * boost.mastery).toInt(),
                guardPay.multiplier,
            )
            // Literature Sense: a paper quest pays the same mastery to each extra stat.
            val masteryStats = PassiveRules.masteryStats(quest.stat, tags, allEffects)
            // Long Walk (Wanderer, once a day): a walk of 30 min or more also pays Normal INT mastery.
            val walkInt = if (PowerRules.longWalkApplies(tags, quest.unit, quest.target) && powers.triggerAutomatic(PowerHandlers.LONG_WALK, today)) {
                GrindGuard.scaleMastery(MasteryGrowth.masteryFor(Tier.NORMAL, Stat.INT, allEffects), guardPay.multiplier)
            } else {
                0
            }
            val gains = masteryStats.associateWith { mastery } + (if (walkInt > 0) mapOf(Stat.INT to (if (Stat.INT in masteryStats) mastery else 0) + walkInt) else emptyMap())
            val grown = gains.map { (stat, gain) ->
                val attribute = attributes.first { it.stat == stat }
                attribute to MasteryGrowth.apply(attribute.value, attribute.mastery, gain)
            }
            val growth = grown.first().second
            val totalXp = player.totalXp + reward.xp
            val levels = Leveling.change(player.level, totalXp)
            val maxHp = DerivedStats.maxHp(stats)
            val hpGain = (if (quest.kind == QuestKind.PENALTY) PenaltyRules.DONE_HP else 0) + PassiveRules.hpOnComplete(tags, allEffects)
            // Equilibrium: finishing the rest-day Daily fills HP and MP, and +10% XP tomorrow.
            val restore = PassiveRules.restRestore(allEffects)?.takeIf {
                isRestDay && quest.isDaily &&
                    questDao.getDailyObjectives(epoch).all { it.id == quest.id || it.status == QuestStatus.DONE }
            }
            val newHp = if (restore != null) maxHp else (player.hp + hpGain).coerceAtMost(maxHp).coerceAtLeast(player.hp)
            val hp = newHp - player.hp
            val maxMp = DerivedStats.maxMp(stats)
            // Stillness (Ascetic, once a day): a meditation quest refills 25% of max MP.
            val stillness = if ("meditation" in tags && powers.triggerAutomatic(PowerHandlers.STILLNESS, today)) PowerRules.stillnessMp(maxMp) else 0

            playerDao.upsertAttributes(grown.map { (a, g) -> a.copy(value = g.value, mastery = g.mastery) })
            playerDao.upsertPlayer(
                player.copy(
                    totalXp = totalXp,
                    level = levels.toLevel,
                    freePoints = player.freePoints + levels.freePoints,
                    skillPoints = player.skillPoints + levels.skillPoints,
                    hp = newHp,
                    mp = if (restore != null) maxMp else (player.mp + stillness).coerceAtMost(maxOf(maxMp, player.mp)),
                    equilibriumDay = if (restore != null) epoch + 1 else player.equilibriumDay,
                ),
            )
            val loot = when (quest.kind) {
                // Past the 3rd slot a self-added side quest (a bounty) gets no roll.
                QuestKind.SIDE -> if (!guardPay.lootAllowed) null else inventory.rollAndGrant(DropSource.SIDE_QUEST, "quest:${quest.id}", "quest:${quest.publicId}")
                QuestKind.EMERGENCY -> inventory.rollAndGrant(DropSource.EMERGENCY, "quest:${quest.id}", "quest:${quest.publicId}")
                else -> null
            }
            loot?.let(dropped::add)
            questDao.update(
                quest.copy(
                    status = QuestStatus.DONE,
                    completedAtMillis = time.now().toEpochMilli(),
                    completedDay = today.toEpochDay(),
                    xpAwarded = reward.xp,
                    critical = reward.isCritical,
                    masteryAwarded = mastery,
                    // The level before this completion: if it levelled up, undo is no longer offered.
                    levelAtCompletion = player.level,
                    lootItemId = loot?.id,
                    hpAwarded = hp,
                    guardMultiplier = guardPay.multiplier,
                ),
            )
            val now = time.now().toEpochMilli()
            db.logXp(
                reward.xp,
                XpLog(
                    XpSource.QUEST, epoch, now, quest.id,
                    multipliers(
                        "guard" to guardPay.multiplier,
                        "kind" to quest.xpFactor,
                        "power" to boost.xp,
                        "crit" to if (reward.isCritical) QuestXp.CRIT_MULTIPLIER else 1.0,
                    ),
                ),
            )
            // Habit strength: the period counts at once; the reset confirms it. Rest days don't count.
            if (quest.seriesId != null && (quest.scope == QuestScope.WEEKLY || !isRestDay)) habits.record(quest, done = true, at = now)
            emitted += GameEvent.QuestCompleted(quest.id, reward.xp, reward.isCritical, quest.stat, growth.statGain)
            if (levels.leveledUp) emitted += GameEvent.LevelUp(levels.fromLevel, levels.toLevel, levels.freePoints)
            partner = powers.parallelPartnerToComplete(quest, epoch)
            true
        }
        emitted.forEach(events::emit)
        inventory.announce(dropped)
        // Parallel Runs: the longer quest done completes its linked partner too.
        partner?.let { complete(it) }
        if (done) {
            gates.closeCleared()
            trials.closeCleared()
            arcs.sync()
            achievements.evaluate()
        }
        return done
    }

    /** Reverses a completion exactly, same day only and only while no level was gained since. */
    suspend fun undo(questId: Long): Boolean {
        val today = gameDay.today()
        return db.withTransaction {
            val quest = questDao.getById(questId) ?: return@withTransaction false
            val player = playerDao.getPlayer() ?: return@withTransaction false
            if (!quest.canUndo(player, today.toEpochDay(), GameClock.weekStart(today).toEpochDay())) {
                return@withTransaction false
            }
            val attributes = playerDao.getAttributes()
            val allEffects = inventory.gearBonus().effects + skills.effects(player)
            val stats = PassiveRules.masteryStats(quest.stat, storedTags(quest), allEffects)
            playerDao.upsertAttributes(
                attributes.filter { it.stat in stats }.map { attribute ->
                    val back = MasteryGrowth.remove(attribute.value, attribute.mastery, quest.masteryAwarded)
                    attribute.copy(value = back.value, mastery = back.mastery)
                },
            )
            val epoch = today.toEpochDay()
            playerDao.upsertPlayer(
                player.copy(
                    totalXp = (player.totalXp - quest.xpAwarded).coerceAtLeast(0),
                    hp = (player.hp - quest.hpAwarded).coerceAtLeast(0),
                    // An undone rest-day Daily no longer earns Equilibrium's bonus.
                    equilibriumDay = player.equilibriumDay.takeUnless { quest.isDaily && it == epoch + 1 },
                ),
            )
            powers.restoreForQuest(quest.id, epoch)
            // Exactly what was paid comes back; nobody else is repaid (PHASE5.md section 2).
            db.logXp(-quest.xpAwarded, XpLog(XpSource.QUEST, epoch, time.now().toEpochMilli(), quest.id))
            habits.clear(quest)
            questDao.update(
                quest.copy(
                    status = QuestStatus.OPEN,
                    completedAtMillis = null,
                    completedDay = null,
                    xpAwarded = 0,
                    critical = false,
                    masteryAwarded = 0,
                    levelAtCompletion = null,
                    hpAwarded = 0,
                    guardMultiplier = 1.0,
                ),
            )
            true
        }
    }

    /** Equilibrium's +10% XP on the day after a completed rest-day Daily. */
    private fun equilibriumBonus(player: PlayerEntity, passives: List<Effect>, epoch: Long): List<Effect> {
        if (player.equilibriumDay != epoch) return emptyList()
        val restore = PassiveRules.restRestore(passives) ?: return emptyList()
        return listOf(Effect.XpMult(value = 1.0 + restore.nextDayXp))
    }

    /**
     * Chain lengths for Momentum and Focus Chain, counting this quest as done. Rest days
     * neither extend nor break a chain (A3).
     */
    private suspend fun chains(quest: QuestEntity, epoch: Long, isRestDay: Boolean): Map<String, Int> {
        val from = epoch - PassiveRules.LOOKBACK_DAYS
        val done = questDao.completedBetween(from, epoch) + quest.copy(completedDay = epoch)
        val restDays = db.snapshotDao().getRange(from, epoch - 1).filter { it.restDay }.map { it.day }.toMutableSet()
        if (isRestDay) restDays += epoch
        val body = PassiveRules.bodyDays(done.mapNotNull { q -> q.completedDay?.let { q.realm to it } })
        // Mind work: timed Mind quests, plus Focus Sessions ended early (a full one completes its quest).
        val sessions = powers.focusMinutes(from..epoch).map { (day, minutes) -> Triple(Realm.MIND, day, minutes) }
        val focus = PassiveRules.focusDays(
            done.mapNotNull { q -> q.completedDay?.let { Triple(q.realm, it, q.minutes()) } } + sessions,
        )
        return mapOf(
            PassiveRules.CHAIN_BODY to PassiveRules.chainLength(body, restDays, epoch),
            PassiveRules.CHAIN_MIND_FOCUS to PassiveRules.chainLength(focus, restDays, epoch),
        )
    }

    private fun QuestEntity.minutes(): Double = when (unit) {
        TargetUnit.MINUTES -> target
        TargetUnit.HOURS -> target * 60
        else -> 0.0
    }

    /** Tags stored on the quest (v3), its template's tags, plus `paper` for quests counted in papers. */
    private fun storedTags(quest: QuestEntity): Set<String> = buildSet {
        quest.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(::add)
        addAll(TemplateTags.of(quest.templateKey))
        if (quest.unitLabel.trim().lowercase().startsWith(PAPER)) add(PAPER)
    }

    /** Tags that let effects apply: stored ones, plus sleep, before 09:00, rest day, weakest realm. */
    private suspend fun tags(quest: QuestEntity, today: LocalDate, isRestDay: Boolean): Set<String> = buildSet {
        addAll(storedTags(quest))
        if (quest.templateKey == TemplateTitles.LIGHTS_OUT_KEY || TemplateMatcher.isSleep(quest.title)) add(QuestTags.SLEEP)
        if (time.now().atZone(time.zone()).toLocalTime().isBefore(NINE)) add(QuestTags.BEFORE_NINE)
        if (isRestDay) add(QuestTags.REST_DAY)
        val week = db.snapshotDao().getRange(today.toEpochDay() - 7, today.toEpochDay() - 1)
        if (week.isNotEmpty()) {
            val weakest = SideQuestRules.balanceRealm(
                done = mapOf(Realm.BODY to week.sumOf { it.bodyDone }, Realm.MIND to week.sumOf { it.mindDone }, Realm.SELF to week.sumOf { it.selfDone }),
                total = mapOf(Realm.BODY to week.sumOf { it.bodyTotal }, Realm.MIND to week.sumOf { it.mindTotal }, Realm.SELF to week.sumOf { it.selfTotal }),
            )
            if (weakest == quest.realm) add(QuestTags.WEAKEST_REALM)
        }
    }

    private fun QuestEntity.isCurrent(today: LocalDate): Boolean {
        val t = today.toEpochDay()
        return when {
            kind == QuestKind.SIDE -> t in day..(expiresDay ?: day)
            kind == QuestKind.EMERGENCY -> day == t && (expiresAtMillis == null || time.now().toEpochMilli() < expiresAtMillis)
            scope == QuestScope.DAILY -> day == t
            else -> day == GameClock.weekStart(today).toEpochDay()
        }
    }

    private companion object {
        val NINE: LocalTime = LocalTime.of(9, 0)
        const val PAPER = "paper"
    }
}

/**
 * Where a quest came from, for the grind guard: only quests you added yourself (your own,
 * bounties, Clarity's extras) use slots. Template, Claude, penalty and emergency quests don't.
 */
internal fun QuestEntity.guardOrigin(): QuestOrigin = when {
    kind == QuestKind.PENALTY -> QuestOrigin.PENALTY
    kind == QuestKind.EMERGENCY -> QuestOrigin.EMERGENCY
    source == QuestSource.TEMPLATE -> QuestOrigin.TEMPLATE
    source == QuestSource.CLAUDE -> QuestOrigin.CLAUDE
    isDaily -> QuestOrigin.TEMPLATE
    else -> QuestOrigin.SELF_ADDED
}

/**
 * Undo is offered the same game day (week for weekly quests), while the level hasn't
 * changed, and never for a completion that dropped an item.
 */
internal fun QuestEntity.canUndo(player: PlayerEntity?, today: Long, weekStart: Long): Boolean =
    status == QuestStatus.DONE &&
        player != null &&
        levelAtCompletion == player.level &&
        lootItemId == null &&
        when (scope) {
            QuestScope.DAILY -> (completedDay ?: day) == today
            QuestScope.WEEKLY -> day == weekStart
        }
