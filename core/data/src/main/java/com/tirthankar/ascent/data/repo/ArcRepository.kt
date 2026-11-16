package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.ContentRepository
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.NoticeKind
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.ArcEntity
import com.tirthankar.ascent.data.db.ArcStatus
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.XpSource
import com.tirthankar.ascent.data.db.ChapterEntity
import com.tirthankar.ascent.data.db.ChapterObjectiveEntity
import com.tirthankar.ascent.data.db.ChapterStatus
import com.tirthankar.ascent.data.db.GoalEntity
import com.tirthankar.ascent.data.db.GoalQuestTotal
import com.tirthankar.ascent.data.db.GoalStatus
import com.tirthankar.ascent.data.db.LoreTarget
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.model.Arc
import com.tirthankar.ascent.data.model.Chapter
import com.tirthankar.ascent.data.model.ChapterObjective
import com.tirthankar.ascent.engine.ArcRules
import com.tirthankar.ascent.engine.ArcTemplate
import com.tirthankar.ascent.engine.ChapterTemplate
import com.tirthankar.ascent.engine.FinaleKind
import com.tirthankar.ascent.engine.LoreKinds
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.SafetyRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class NewArc(
    val realm: Realm,
    val title: String,
    val chapters: List<ChapterTemplate>,
    val finale: FinaleKind = FinaleKind.NONE,
    val finaleName: String? = null,
    val finaleDeadline: Long? = null,
    val trialTemplate: String? = null,
    val goalId: Long? = null,
    val templateId: String? = null,
    val source: QuestSource = QuestSource.MANUAL,
)

/**
 * Main quests (SPEC "Main quests: arcs"): a long-term goal in 3–6 chapters ending in a
 * finale. One active arc per realm; the rest wait in a backlog. Chapters complete when
 * every objective is done and pay 1,000 × (1 + 0.05 L) XP.
 */
@Singleton
class ArcRepository @Inject constructor(
    private val db: AscentDatabase,
    private val contentRepository: ContentRepository,
    private val gameDay: GameDay,
    private val time: TimeSource,
    private val events: GameEvents,
    private val lore: LoreRepository,
    private val achievements: AchievementRepository,
    private val bosses: BossRepository,
) {
    private val dao = db.arcDao()

    val templates: List<ArcTemplate> get() = contentRepository.content.arcs.arcs

    val arcs: Flow<List<Arc>> = combine(
        dao.observeOpen(),
        dao.observeChapters(),
        dao.observeObjectives(),
        db.goalDao().observeLinkedToObjectives(),
        db.questDao().observeGoalTotals(),
    ) { arcs, chapters, objectives, goals, totals ->
        arcs.map { arc -> arc.toModel(chapters.filter { it.arcId == arc.id }, objectives, goals, totals) }
    }

    suspend fun open(): List<Arc> = arcs.first()

    suspend fun getByPublicId(publicId: String): ArcEntity? = dao.getByPublicId(publicId)

    /** Creates an arc; it starts active only if its realm has no active arc. Null if forbidden. */
    suspend fun create(arc: NewArc): Long? {
        if (SafetyRules.isForbidden(arc.title) || arc.chapters.any { SafetyRules.isForbidden(it.title) }) return null
        return db.withTransaction {
            val active = dao.getActive().count { it.realm == arc.realm }
            val id = dao.insertArc(
                ArcEntity(
                    publicId = "a${dao.maxId() + 1}",
                    realm = arc.realm,
                    title = arc.title.trim(),
                    goalId = arc.goalId,
                    status = if (ArcRules.startsActive(active)) ArcStatus.ACTIVE else ArcStatus.BACKLOG,
                    templateId = arc.templateId,
                    finale = arc.finale,
                    finaleName = arc.finaleName?.trim()?.takeIf { it.isNotEmpty() },
                    finaleDeadline = arc.finaleDeadline,
                    trialTemplate = arc.trialTemplate,
                    source = arc.source,
                    completedDay = null,
                    createdAtMillis = time.now().toEpochMilli(),
                ),
            )
            arc.chapters.forEachIndexed { index, chapter -> insertChapter(id, index, chapter, first = index == 0) }
            id
        }
    }

    suspend fun startFromTemplate(templateId: String): Long? {
        val t = contentRepository.content.arc(templateId) ?: return null
        return create(NewArc(t.realm, t.title, t.chapters, t.finale, t.finaleName, null, t.trialTemplate, templateId = t.id))
    }

    /** Makes [arcId] the realm's active arc; the one it replaces returns to the backlog. */
    suspend fun activate(arcId: Long): Boolean = db.withTransaction {
        val arc = dao.getArc(arcId) ?: return@withTransaction false
        if (arc.status != ArcStatus.BACKLOG) return@withTransaction false
        dao.getActive().filter { it.realm == arc.realm }.forEach { dao.updateArc(it.copy(status = ArcStatus.BACKLOG)) }
        dao.updateArc(arc.copy(status = ArcStatus.ACTIVE))
        true
    }

    suspend fun abandon(arcId: Long): Boolean = db.withTransaction {
        val arc = dao.getArc(arcId) ?: return@withTransaction false
        dao.updateArc(arc.copy(status = ArcStatus.ABANDONED))
        true
    }

    suspend fun addChapter(arcId: Long, chapter: ChapterTemplate): Boolean {
        if (SafetyRules.isForbidden(chapter.title)) return false
        return db.withTransaction {
            val arc = dao.getArc(arcId) ?: return@withTransaction false
            if (arc.status == ArcStatus.DONE || arc.status == ArcStatus.ABANDONED) return@withTransaction false
            val existing = dao.chapters(arcId)
            insertChapter(arcId, existing.size, chapter, first = existing.none { it.status != ChapterStatus.DONE })
            true
        }
    }

    /** Logs progress on a chapter objective (negative to correct), then checks the chapter. */
    suspend fun log(objectiveId: Long, amount: Double) {
        db.withTransaction {
            val objective = dao.getObjective(objectiveId) ?: return@withTransaction
            dao.updateObjective(objective.copy(logged = (objective.logged + amount).coerceAtLeast(0.0)))
        }
        sync()
    }

    /** Completes a chapter by hand: allowed when it has no objectives or all are done. */
    suspend fun completeChapter(chapterId: Long): Boolean {
        val emitted = mutableListOf<GameEvent>()
        val ok = db.withTransaction {
            val chapter = dao.getChapter(chapterId) ?: return@withTransaction false
            if (chapter.status != ChapterStatus.ACTIVE) return@withTransaction false
            val arc = dao.getArc(chapter.arcId) ?: return@withTransaction false
            val objectives = objectiveProgress(dao.objectives(chapter.id))
            if (objectives.isNotEmpty() && !ArcRules.chapterComplete(objectives)) return@withTransaction false
            finishChapter(arc, chapter, emitted)
            true
        }
        emitted.forEach(events::emit)
        if (ok) achievements.evaluate()
        return ok
    }

    /** Completes every chapter whose objectives are all done (logged or via linked goals). */
    suspend fun sync() {
        val emitted = mutableListOf<GameEvent>()
        db.withTransaction {
            for (arc in dao.getOpen()) {
                // A chapter may complete the next one immediately if it was already done.
                while (true) {
                    val chapter = dao.chapters(arc.id).firstOrNull { it.status == ChapterStatus.ACTIVE } ?: break
                    val progress = objectiveProgress(dao.objectives(chapter.id))
                    if (!ArcRules.chapterComplete(progress)) break
                    finishChapter(dao.getArc(arc.id) ?: break, chapter, emitted)
                }
            }
        }
        emitted.forEach(events::emit)
    }

    private suspend fun insertChapter(arcId: Long, position: Int, chapter: ChapterTemplate, first: Boolean) {
        val chapterId = dao.insertChapter(
            ChapterEntity(
                arcId = arcId,
                position = position,
                title = chapter.title.trim(),
                status = if (first) ChapterStatus.ACTIVE else ChapterStatus.LOCKED,
                completedDay = null,
                xpAwarded = 0,
            ),
        )
        chapter.objectives.forEachIndexed { i, o ->
            dao.insertObjective(ChapterObjectiveEntity(0, chapterId, i, o.title.trim(), o.target.coerceAtLeast(1.0), o.unit.trim(), 0.0))
        }
    }

    private suspend fun finishChapter(arc: ArcEntity, chapter: ChapterEntity, emitted: MutableList<GameEvent>) {
        val today = gameDay.today().toEpochDay()
        val player = db.playerDao().getPlayer() ?: return
        val xp = ArcRules.chapterXp(player.level)
        db.grantXp(xp, emitted, XpLog(XpSource.CHAPTER, today, time.now().toEpochMilli(), chapter.id))
        dao.updateChapter(chapter.copy(status = ChapterStatus.DONE, completedDay = today, xpAwarded = xp))
        emitted += GameEvent.Notice(NoticeKind.CHAPTER_COMPLETE, chapter.title, arc.title)
        val next = dao.chapters(arc.id).firstOrNull { it.position > chapter.position && it.status == ChapterStatus.LOCKED }
        if (next != null) {
            dao.updateChapter(next.copy(status = ChapterStatus.ACTIVE))
            lore.unlock(lore.seed(LoreKinds.CHAPTER_DONE), arc.title, emitted, LoreTarget.ARC to arc.id, CustomLoreEvents.CHAPTER_DONE)
            return
        }
        // The arc's end.
        dao.updateArc(arc.copy(status = ArcStatus.DONE, completedDay = today))
        emitted += GameEvent.Notice(NoticeKind.ARC_COMPLETE, arc.title)
        val seed = arc.templateId?.let { id -> lore.seed(LoreKinds.ARC_FINALE) { it.arc == id } }
            ?: lore.seed(LoreKinds.ARC_END) { it.realm == arc.realm }
        lore.unlock(seed, arc.title, emitted, LoreTarget.ARC to arc.id, CustomLoreEvents.ARC_END)
        when (arc.finale) {
            FinaleKind.BOSS -> bosses.ensureFinaleBoss(arc, today)
            FinaleKind.TRIAL -> arc.trialTemplate?.let { bosses.createTrialInTransaction(it, arc.id, today) }
            FinaleKind.TITLE -> arc.finaleName?.let { achievements.earn(it, "arc:${arc.publicId}", emitted) }
            FinaleKind.NONE -> Unit
        }
    }

    private suspend fun objectiveProgress(objectives: List<ChapterObjectiveEntity>): List<Pair<Double, Double>> {
        val goals = db.goalDao().getLinkedToObjectives()
        val totals = db.questDao().observeGoalTotals().first()
        return objectives.map { progress(it, goals, totals) to it.target }
    }

    private fun ArcEntity.toModel(
        chapters: List<ChapterEntity>,
        objectives: List<ChapterObjectiveEntity>,
        goals: List<GoalEntity>,
        totals: List<GoalQuestTotal>,
    ) = Arc(
        id = id,
        publicId = publicId,
        realm = realm,
        title = title,
        status = status,
        templateId = templateId,
        finale = finale,
        finaleName = finaleName,
        chapters = chapters.sortedBy { it.position }.map { ch ->
            Chapter(
                id = ch.id,
                position = ch.position,
                title = ch.title,
                status = ch.status,
                objectives = objectives.filter { it.chapterId == ch.id }.map { o ->
                    ChapterObjective(o.id, o.title, o.target, o.unitLabel, progress(o, goals, totals))
                },
                xpAwarded = ch.xpAwarded,
            )
        },
    )

    private companion object {
        /**
         * Logged progress plus linked weekly goals: a goal in the same unit adds its
         * progress; any other linked goal adds 1 once it is done.
         */
        fun progress(objective: ChapterObjectiveEntity, goals: List<GoalEntity>, totals: List<GoalQuestTotal>): Double =
            objective.logged + goals.filter { it.objectiveId == objective.id }.sumOf { goal ->
                if (goal.unitLabel != null && goal.unitLabel.equals(objective.unitLabel, ignoreCase = true)) {
                    GoalRepository.progress(goal, totals)
                } else if (goal.status == GoalStatus.DONE) {
                    1.0
                } else {
                    0.0
                }
            }
    }
}
