package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.ContentRepository
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.NoticeKind
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.XpSource
import com.tirthankar.ascent.data.db.GateEntity
import com.tirthankar.ascent.data.db.GateKind
import com.tirthankar.ascent.data.db.GateObjectiveEntity
import com.tirthankar.ascent.data.db.GateSource
import com.tirthankar.ascent.data.db.GateStatus
import com.tirthankar.ascent.data.db.InventoryItemEntity
import com.tirthankar.ascent.data.db.QuestEntity
import com.tirthankar.ascent.engine.BodyTargetMemory
import com.tirthankar.ascent.engine.GameClock
import com.tirthankar.ascent.engine.GateRules
import com.tirthankar.ascent.engine.LoreKinds
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.RankTrialContent
import com.tirthankar.ascent.engine.RankTrialRules
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Seeds
import com.tirthankar.ascent.engine.TemplateLibrary
import com.tirthankar.ascent.engine.TrialOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rank trials (PHASE4.md section 4): a 7-day special Gate, opened as soon as you reach
 * the level with the rank one below, outside the two-open limit. Claude can't propose or
 * edit one. All six objectives complete → the new rank, a chest, a respec token, the
 * rank's lore and the class re-choice. Otherwise XP for what was cleared, "Measurement
 * noise" and a 7-day wait.
 */
@Singleton
class RankTrialRepository @Inject constructor(
    private val db: AscentDatabase,
    private val contentRepository: ContentRepository,
    private val gameDay: GameDay,
    private val time: TimeSource,
    private val events: GameEvents,
    private val inventory: InventoryRepository,
    private val lore: LoreRepository,
    private val achievements: AchievementRepository,
) {
    private val dao = db.gateDao()
    private val content: RankTrialContent get() = contentRepository.content.rankTrials

    /** The open trial, if any. */
    val open: Flow<GateEntity?> = dao.observeOpenTrial().map { it.firstOrNull() }

    /**
     * Opens the trial you qualify for, if none is open and no retry wait is running.
     * Call inside a transaction.
     */
    internal suspend fun openIfAvailable(
        today: LocalDate,
        engineTarget: EngineTarget,
        bodyMemory: Map<String, BodyTargetMemory>,
        emitted: MutableList<GameEvent>,
    ) {
        if (dao.openTrials().isNotEmpty()) return
        val player = db.playerDao().getPlayer() ?: return
        val epoch = today.toEpochDay()
        val last = dao.lastTrial()
        val def = RankTrialRules.available(player.rank, player.level, last?.retryAt, epoch, content) ?: return

        val bodyKeys = content.objectives[Realm.BODY].orEmpty().filter { it.sessions != null }.flatMap { it.templates }.toSet()
        val headroom = bodyHeadroomFor(bodyKeys, engineTarget, bodyMemory, GameClock.weekStart(today).toEpochDay())
        val targets = content.objectives.values.flatten().flatMap { it.templates }.toSet()
            .mapNotNull { key -> TemplateLibrary[key]?.let { key to engineTarget(it) } }
            .toMap()
        val attempt = (last?.takeIf { it.targetRank == def.rank }?.attempt ?: 0) + 1
        val seed = Seeds.forEvent(player.rollSeed, "rank_trial:${def.rank}:$attempt")
        val objectives = RankTrialRules.objectives(def, content, seed, headroom) { targets[it.key] }

        val intro = lore.seed(LoreKinds.RANK_TRIAL_START)
        val id = dao.insertGate(
            GateEntity(
                publicId = "gt${dao.maxId() + 1}",
                templateId = null,
                title = intro?.title ?: def.rank.name,
                lore = intro?.text.orEmpty(),
                // A trial spans all three realms; each objective knows its own.
                realm = Realm.BODY,
                rank = def.rank,
                days = content.days,
                isRed = false,
                status = GateStatus.OPEN,
                source = GateSource.BOARD,
                boardWeek = null,
                opensDay = epoch,
                closesDay = epoch + content.days - 1,
                fraction = null,
                xpAwarded = 0,
                createdAtMillis = time.now().toEpochMilli(),
                kind = GateKind.RANK_TRIAL,
                targetRank = def.rank,
                attempt = attempt,
            ),
        )
        objectives.forEachIndexed { i, o ->
            dao.insertObjective(GateObjectiveEntity(0, id, i, o.title, o.target, o.unit, o.templates.joinToString(","), o.isCount, 0.0, null))
        }
        emitted += GameEvent.Notice(NoticeKind.RANK_TRIAL_OPEN, def.rank.name)
        lore.unlockOnce(LoreKinds.RANK_TRIAL_START, def.rank.name, emitted)
    }

    /** Closes the trial whose last day is before [today]. Call inside a transaction. */
    internal suspend fun closeExpired(today: Long, emitted: MutableList<GameEvent>, dropped: MutableList<InventoryItemEntity>) {
        val quests = db.questDao().completedBetween(today - (content.days + 1), today)
        dao.openTrials().filter { (it.closesDay ?: Long.MAX_VALUE) < today }.forEach { close(it, quests, today, emitted, dropped) }
    }

    /** Passes the open trial at once when all six objectives are complete. */
    suspend fun closeCleared() {
        val emitted = mutableListOf<GameEvent>()
        val dropped = mutableListOf<InventoryItemEntity>()
        db.withTransaction {
            val today = gameDay.today().toEpochDay()
            val quests = db.questDao().completedBetween(today - (content.days + 1), today)
            dao.openTrials().forEach { trial ->
                val progress = dao.objectives(trial.id).map { GateRepository.progress(it, trial, quests) to it.target }
                if (RankTrialRules.outcome(progress) == TrialOutcome.PASSED) close(trial, quests, today, emitted, dropped)
            }
        }
        emitted.forEach(events::emit)
        inventory.announce(dropped)
        if (emitted.isNotEmpty()) achievements.evaluate()
    }

    private suspend fun close(
        trial: GateEntity,
        quests: List<QuestEntity>,
        today: Long,
        emitted: MutableList<GameEvent>,
        dropped: MutableList<InventoryItemEntity>,
    ) {
        val rank = trial.targetRank ?: return
        val def = content.forRank(rank) ?: return
        val objectives = dao.objectives(trial.id)
        val progress = objectives.map { GateRepository.progress(it, trial, quests) }
        objectives.zip(progress).forEach { (o, p) -> dao.updateObjective(o.copy(finalProgress = p)) }
        val pairs = progress.zip(objectives.map { it.target })
        val fraction = GateRules.fractionCleared(pairs)
        val outcome = RankTrialRules.outcome(pairs)

        val before = db.playerDao().getPlayer() ?: return
        // The Gate payout rule for what was cleared, at the target rank (PHASE4.md section 4).
        val xp = RankTrialRules.failXp(def, before.level, fraction)
        val player = db.grantXp(xp, emitted, XpLog(XpSource.RANK_TRIAL, today, time.now().toEpochMilli(), trial.id, multipliers("cleared" to fraction)))
            ?: return

        when (outcome) {
            TrialOutcome.PASSED -> {
                db.playerDao().upsertPlayer(
                    player.copy(
                        rank = rank,
                        respecTokens = player.respecTokens + content.respecTokens,
                        // The class re-choice is offered before any evolution (A14).
                        classRechoice = player.classId != null,
                    ),
                )
                inventory.rollAndGrant(def.chest, "rank_trial:${trial.id}", "gate:${trial.publicId}")?.let(dropped::add)
                dao.updateGate(trial.copy(status = GateStatus.CLEARED, fraction = fraction, xpAwarded = xp))
                emitted += GameEvent.Notice(NoticeKind.RANK_UP, rank.name, xp.toString())
                lore.unlockOnce(LoreKinds.RANK, rank.name, emitted) { it.rank == rank }
                if (rank == Rank.A) lore.unlockOnce(LoreKinds.OBSERVER_GLIMPSE, rank.name, emitted)
                if (rank == Rank.S) lore.unlockOnce(LoreKinds.SIGMA_HINT, rank.name, emitted)
            }
            TrialOutcome.FAILED -> {
                dao.updateGate(
                    trial.copy(
                        status = GateStatus.FAILED,
                        fraction = fraction,
                        xpAwarded = xp,
                        retryAt = RankTrialRules.retryDay(today, content),
                    ),
                )
                emitted += GameEvent.Notice(NoticeKind.RANK_TRIAL_FAILED, rank.name, xp.toString())
                lore.unlock(lore.seed(LoreKinds.RANK_TRIAL_FAIL), rank.name, emitted)
            }
        }
    }
}
