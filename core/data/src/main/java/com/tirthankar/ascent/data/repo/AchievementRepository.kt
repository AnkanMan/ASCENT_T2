package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.ContentRepository
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.NoticeKind
import com.tirthankar.ascent.data.TemplateTitles
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.EarnedTitleEntity
import com.tirthankar.ascent.data.db.InventoryItemEntity
import com.tirthankar.ascent.data.db.PlayerAchievementEntity
import com.tirthankar.ascent.data.model.AchievementProgress
import com.tirthankar.ascent.engine.AchievementEvaluator
import com.tirthankar.ascent.engine.AchievementMetrics
import com.tirthankar.ascent.engine.LoreKinds
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.TargetUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Achievements and titles (SPEC "Achievements and titles"). Achievements are data:
 * one evaluator checks every definition against metrics computed from history, so
 * counters backfill from Phase 2 (D8) and nothing is special-cased.
 */
@Singleton
class AchievementRepository @Inject constructor(
    private val db: AscentDatabase,
    private val contentRepository: ContentRepository,
    private val inventory: InventoryRepository,
    private val lore: LoreRepository,
    private val titles: TemplateTitles,
    private val time: TimeSource,
    private val events: GameEvents,
) {
    private val dao = db.progressDao()
    private val mutex = Mutex()
    private val lastMetrics = MutableStateFlow<Map<String, Double>>(emptyMap())
    private val defs get() = contentRepository.content.achievements.achievements

    val achievements: Flow<List<AchievementProgress>> =
        combine(dao.observeAchievements(), lastMetrics) { held, metrics ->
            val tiers = held.associate { it.achievementId to it.tier }
            defs.map { def ->
                val tier = tiers[def.id] ?: 0
                AchievementProgress(
                    id = def.id,
                    name = def.name,
                    description = def.description,
                    hidden = def.hidden,
                    tier = tier,
                    tiers = def.thresholds.size,
                    progress = metrics[def.metric] ?: 0.0,
                    nextThreshold = def.thresholds.getOrNull(tier),
                )
            }
        }

    /** Titles you can wear: the starting title plus every title earned. */
    val titlesEarned: Flow<List<String>> =
        combine(dao.observeTitles(), lastMetrics) { earned, _ -> listOf(titles.startingTitle()) + earned.map { it.title } }

    /**
     * Recomputes every metric and pays any tier newly reached: titles, chests, named
     * items and Essence. Safe to call often; held tiers are never paid twice.
     */
    suspend fun evaluate() = mutex.withLock {
        val emitted = mutableListOf<GameEvent>()
        val dropped = mutableListOf<InventoryItemEntity>()
        db.withTransaction {
            val metrics = metrics() ?: return@withTransaction
            lastMetrics.value = metrics
            val held = dao.achievements().associate { it.achievementId to it.tier }
            AchievementEvaluator.newlyEarned(defs, metrics, held).forEach { earned ->
                val def = earned.def
                dao.upsertAchievement(PlayerAchievementEntity(def.id, earned.tier, time.now().toEpochMilli()))
                val origin = "achievement:${def.id}:${earned.tier}"
                earned.reward?.let { reward ->
                    reward.title?.let { earn(it, origin, emitted) }
                    reward.chest?.let { chest -> inventory.rollAndGrant(chest, origin, origin)?.let(dropped::add) }
                    reward.item?.let { id -> inventory.grantTemplate(id, origin)?.let(dropped::add) }
                    if (reward.essence > 0) {
                        db.playerDao().getPlayer()?.let { db.playerDao().upsertPlayer(it.copy(essence = it.essence + reward.essence)) }
                    }
                }
                val tierName = if (def.thresholds.size > 1) "${def.name} ${ROMAN.getOrElse(earned.tier - 1) { "${earned.tier}" }}" else def.name
                emitted += GameEvent.Notice(
                    if (def.hidden) NoticeKind.HIDDEN_ACHIEVEMENT else NoticeKind.ACHIEVEMENT,
                    tierName,
                    def.description,
                )
                if (earned.tier == 1) {
                    lore.unlock(lore.seed(LoreKinds.ACHIEVEMENT) { it.achievement == def.id }, def.name, emitted)
                }
            }
        }
        emitted.forEach(events::emit)
        inventory.announce(dropped)
    }

    /** Adds a title you can wear. Call inside a transaction. */
    internal suspend fun earn(title: String, origin: String, emitted: MutableList<GameEvent>) {
        if (dao.hasTitle(title)) return
        dao.insertTitle(EarnedTitleEntity(title, origin, time.now().toEpochMilli()))
        emitted += GameEvent.Notice(NoticeKind.TITLE_EARNED, title)
    }

    /** Wears [title] under your name. Only earned titles (or the starting one) are allowed. */
    suspend fun wear(title: String): Boolean = db.withTransaction {
        val player = db.playerDao().getPlayer() ?: return@withTransaction false
        if (title != titles.startingTitle() && !dao.hasTitle(title)) return@withTransaction false
        db.playerDao().upsertPlayer(player.copy(title = title))
        true
    }

    private suspend fun metrics(): Map<String, Double>? {
        val player = db.playerDao().getPlayer() ?: return null
        val quests = db.questDao()
        val stats = db.playerDao().getAttributes().toStatBlock()
        val realmDays = quests.realmDays().groupBy({ it.day }, { it.realm })
        val ergodicDays = realmDays.filterValues { it.toSet().size == Realm.entries.size }.keys
        val deepWorkHours = quests.sumDone(Realm.MIND, TargetUnit.MINUTES) / 60.0 + quests.sumDone(Realm.MIND, TargetUnit.HOURS)
        // Habit strength (Phase 5): Bound series right now, from the cached strengths.
        val strengths = db.habitDao().allCache().associate { it.seriesId to it.strength }
        val realms = strengths.keys.associateWith { quests.realmOfSeries(it) }
        val (boundHabits, boundRealms) = AchievementMetrics.bound(strengths) { realms[it] }
        return mapOf(
            AchievementMetrics.DAILIES_COMPLETED to db.snapshotDao().dailiesCompleted().toDouble(),
            AchievementMetrics.BEST_STREAK to maxOf(player.bestStreak, player.streakDays).toDouble(),
            AchievementMetrics.ERGODIC_STREAK to AchievementMetrics.longestRun(ergodicDays).toDouble(),
            AchievementMetrics.EQUIPARTITION to if (AchievementMetrics.equipartition(stats)) 1.0 else 0.0,
            AchievementMetrics.RANK to player.rank.ordinal.toDouble(),
            AchievementMetrics.DEEP_WORK_HOURS to deepWorkHours,
            AchievementMetrics.GATES_CLEARED to db.gateDao().clearedCount().toDouble(),
            AchievementMetrics.STEPS_TOTAL to quests.sumDoneAnyRealm(TargetUnit.STEPS),
            AchievementMetrics.BOSSES_DEFEATED to db.bossDao().defeatedCount().toDouble(),
            AchievementMetrics.PAPERS_SUBMITTED to db.bossDao().papersSubmitted().toDouble(),
            AchievementMetrics.STILL_NIGHT_STREAK to AchievementMetrics.longestRun(quests.daysDone(TemplateTitles.LIGHTS_OUT_KEY)).toDouble(),
            AchievementMetrics.RED_GATES_CLEARED to db.gateDao().redClearedCount().toDouble(),
            AchievementMetrics.BOUND_HABITS to boundHabits.toDouble(),
            AchievementMetrics.BOUND_REALMS to boundRealms.toDouble(),
        )
    }

    private companion object {
        val ROMAN = listOf("I", "II", "III")
    }
}
