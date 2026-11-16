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
import com.tirthankar.ascent.data.db.GateObjectiveEntity
import com.tirthankar.ascent.data.db.GateSource
import com.tirthankar.ascent.data.db.GateStatus
import com.tirthankar.ascent.data.db.InventoryItemEntity
import com.tirthankar.ascent.data.db.LoreTarget
import com.tirthankar.ascent.data.db.QuestEntity
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.model.Gate
import com.tirthankar.ascent.data.model.GateObjective
import com.tirthankar.ascent.engine.BodyTargetMemory
import com.tirthankar.ascent.engine.GateOutcome
import com.tirthankar.ascent.engine.GateRules
import com.tirthankar.ascent.engine.GateTemplate
import com.tirthankar.ascent.engine.LoreKinds
import com.tirthankar.ascent.engine.QuestTemplate
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.SafetyRules
import com.tirthankar.ascent.engine.Seeds
import com.tirthankar.ascent.engine.SplitMix64
import com.tirthankar.ascent.engine.TargetUnit
import com.tirthankar.ascent.engine.TemplateLibrary
import com.tirthankar.ascent.engine.TemplateMatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** One objective of a Gate Claude proposes (bridge v2 `add_gate`). */
data class NewGateObjective(val title: String, val target: Double, val unit: String)

data class NewGate(
    val rank: Rank,
    val realm: Realm,
    val title: String,
    val days: Int,
    val objectives: List<NewGateObjective>,
    val lore: String = "",
)

/** The engine's own target for a template today (difficulty, fatigue and Body caps). */
typealias EngineTarget = suspend (QuestTemplate) -> Double?

/**
 * The smallest headroom the +10% weekly cap leaves across the Body templates [keys]
 * (shared by the Gate board and rank trials).
 */
internal suspend fun bodyHeadroomFor(
    keys: Set<String>,
    engineTarget: EngineTarget,
    memory: Map<String, BodyTargetMemory>,
    week: Long,
): Double = keys.mapNotNull { key ->
    val template = TemplateLibrary[key] ?: return@mapNotNull null
    GateRules.bodyHeadroom(memory[key], engineTarget(template), week, template.unit)
}.minOrNull() ?: (SafetyRules.BODY_WEEKLY_RISE - 1.0)

/**
 * The Gate board (SPEC "Gate board", D3–D5): three offers at each Sunday reset, up to two
 * open at once. Your normal quests count toward a Gate (D4); anything else is logged by
 * hand. At expiry it pays base × (1 + 0.05 L) × fraction cleared; the chest opens at 100%.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GateRepository @Inject constructor(
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
    private val content get() = contentRepository.content

    /** Offers on this week's board and open Gates. */
    val current: Flow<List<Gate>> = combine(
        dao.observeCurrent(),
        dao.observeObjectives(),
        gameDay.observeToday().flatMapLatest { db.questDao().observeCompletedBetween(it.toEpochDay() - 7, it.toEpochDay()) },
        db.playerDao().observePlayer(),
    ) { gates, objectives, quests, player ->
        gates.map { it.toModel(objectives.filter { o -> o.gateId == it.id }, quests, player?.level ?: 1) }
    }

    /** The open rank trial (Phase 4), for its card on Status and the Gates tab. */
    val trial: Flow<Gate?> = combine(
        dao.observeOpenTrial(),
        dao.observeObjectives(),
        gameDay.observeToday().flatMapLatest { db.questDao().observeCompletedBetween(it.toEpochDay() - 8, it.toEpochDay()) },
        db.playerDao().observePlayer(),
    ) { trials, objectives, quests, player ->
        trials.firstOrNull()?.let { it.toModel(objectives.filter { o -> o.gateId == it.id }, quests, player?.level ?: 1) }
    }

    val recent: Flow<List<Gate>> = combine(dao.observeClosed(10), dao.observeObjectives()) { gates, objectives ->
        gates.map { it.toModel(objectives.filter { o -> o.gateId == it.id }, emptyList(), 1) }
    }

    suspend fun currentList(): List<Gate> = current.first()

    suspend fun getByPublicId(publicId: String): GateEntity? = dao.getByPublicId(publicId)

    suspend fun openCount(): Int = dao.getOpen().size

    /** Accepts an offer. At most two Gates are open at once. */
    suspend fun accept(gateId: Long): Boolean = db.withTransaction {
        val gate = dao.getGate(gateId) ?: return@withTransaction false
        if (gate.status != GateStatus.OFFERED || dao.getOpen().size >= GateRules.MAX_OPEN) return@withTransaction false
        val today = gameDay.today().toEpochDay()
        dao.updateGate(gate.copy(status = GateStatus.OPEN, opensDay = today, closesDay = today + gate.days - 1))
        true
    }

    suspend fun decline(gateId: Long): Boolean = db.withTransaction {
        val gate = dao.getGate(gateId) ?: return@withTransaction false
        if (gate.status != GateStatus.OFFERED) return@withTransaction false
        dao.updateGate(gate.copy(status = GateStatus.DECLINED))
        true
    }

    /** Logs progress the app can't see (negative to correct), then checks for a clear. */
    suspend fun log(objectiveId: Long, amount: Double) {
        db.withTransaction {
            val o = dao.getObjective(objectiveId) ?: return@withTransaction
            dao.updateObjective(o.copy(logged = (o.logged + amount).coerceAtLeast(0.0)))
        }
        closeCleared()
    }

    /**
     * Adds Claude's Gate, already open, at the rank [rank] (the validator corrects it).
     * Null when two Gates are open or the text is forbidden.
     */
    suspend fun addFromClaude(gate: NewGate): Long? {
        if (SafetyRules.isForbidden(gate.title) || gate.objectives.any { SafetyRules.isForbidden(it.title) }) return null
        return db.withTransaction {
            if (dao.getOpen().size >= GateRules.MAX_OPEN) return@withTransaction null
            val today = gameDay.today().toEpochDay()
            val days = gate.days.coerceIn(3, 7)
            val id = dao.insertGate(
                GateEntity(
                    publicId = "gt${dao.maxId() + 1}",
                    templateId = null,
                    title = gate.title.trim(),
                    lore = gate.lore,
                    realm = gate.realm,
                    rank = gate.rank,
                    days = days,
                    isRed = false,
                    status = GateStatus.OPEN,
                    source = GateSource.CLAUDE,
                    boardWeek = null,
                    opensDay = today,
                    closesDay = today + days - 1,
                    fraction = null,
                    xpAwarded = 0,
                    createdAtMillis = time.now().toEpochMilli(),
                ),
            )
            gate.objectives.forEachIndexed { i, o ->
                val unit = TargetUnit.fromLabel(o.unit)
                val template = TemplateMatcher.match(o.title, gate.realm, unit)
                val amount = template != null && template.unit == unit && unit != TargetUnit.OTHER
                dao.insertObjective(
                    GateObjectiveEntity(0, id, i, o.title.trim(), o.target.coerceAtLeast(1.0), o.unit.trim(), template?.key.orEmpty(), !amount, 0.0, null),
                )
            }
            id
        }
    }

    /**
     * Spawns the board for the week starting [weekStart] (D3: at the Sunday reset).
     * Unaccepted offers from the last board lapse. A Red Gate joins the first board of
     * each month. Call inside a transaction.
     */
    internal suspend fun spawnBoard(
        weekStart: LocalDate,
        engineTarget: EngineTarget,
        bodyMemory: Map<String, BodyTargetMemory>,
    ) {
        val player = db.playerDao().getPlayer() ?: return
        val week = weekStart.toEpochDay()
        if (player.lastBoardWeek != null && player.lastBoardWeek >= week) return
        dao.getOffered().forEach { dao.updateGate(it.copy(status = GateStatus.LAPSED)) }

        val headroom = bodyHeadroom(engineTarget, bodyMemory, week)
        val seed = Seeds.forEvent(player.rollSeed, "board:$week")
        val rng = SplitMix64(seed)
        val used = mutableSetOf<String>()
        GateRules.board(player.rank, seed, headroom).forEach { (rank, realm) ->
            pickTemplate(realm, rng, used)?.let { insertOffer(it, rank, isRed = false, week, engineTarget) }
        }
        if (weekStart.dayOfMonth <= 7) {
            val realm = if (rng.nextInt(2) == 0) Realm.MIND else Realm.SELF
            pickTemplate(realm, rng, used)?.let { insertOffer(it, GateRules.redRank(player.rank), isRed = true, week, engineTarget) }
        }
        db.playerDao().upsertPlayer(player.copy(lastBoardWeek = week))
    }

    /** Closes every open Gate whose last day is before [today]. Call inside a transaction. */
    internal suspend fun closeExpired(today: Long, emitted: MutableList<GameEvent>, dropped: MutableList<InventoryItemEntity>) {
        val quests = db.questDao().completedBetween(today - 8, today)
        dao.getOpen().filter { (it.closesDay ?: Long.MAX_VALUE) < today }.forEach { close(it, quests, emitted, dropped) }
    }

    /** Clears any open Gate that reached 100%, paying out at once. */
    suspend fun closeCleared() {
        val emitted = mutableListOf<GameEvent>()
        val dropped = mutableListOf<InventoryItemEntity>()
        db.withTransaction {
            val today = gameDay.today().toEpochDay()
            val quests = db.questDao().completedBetween(today - 8, today)
            dao.getOpen().forEach { gate ->
                val progress = dao.objectives(gate.id).map { progress(it, gate, quests) to it.target }
                if (GateRules.fractionCleared(progress) >= 1.0 - 1e-9) close(gate, quests, emitted, dropped)
            }
        }
        emitted.forEach(events::emit)
        inventory.announce(dropped)
        if (emitted.isNotEmpty()) achievements.evaluate()
    }

    private suspend fun close(
        gate: GateEntity,
        quests: List<QuestEntity>,
        emitted: MutableList<GameEvent>,
        dropped: MutableList<InventoryItemEntity>,
    ) {
        val objectives = dao.objectives(gate.id)
        val progress = objectives.map { progress(it, gate, quests) }
        objectives.zip(progress).forEach { (o, p) -> dao.updateObjective(o.copy(finalProgress = p)) }
        val fraction = GateRules.fractionCleared(progress.zip(objectives.map { it.target }))
        val outcome = GateRules.outcome(fraction, gate.isRed)
        val player = db.playerDao().getPlayer() ?: return
        val gear = inventory.gearBonus()
        val xp = GateRules.payoutXp(gate.rank, player.level, fraction, gate.isRed, gear.gateRewardMultiplier(gate.realm))
        val day = gate.closesDay ?: gameDay.today().toEpochDay()
        db.grantXp(xp, emitted, XpLog(XpSource.GATE, day, time.now().toEpochMilli(), gate.id, multipliers("cleared" to fraction)))
        repeat(GateRules.chests(outcome, gate.isRed)) { i ->
            inventory.rollAndGrant(GateRules.chest(gate.rank), "gate:${gate.id}:chest:$i", "gate:${gate.publicId}")?.let(dropped::add)
        }
        val status = when (outcome) {
            GateOutcome.CLEARED -> GateStatus.CLEARED
            GateOutcome.PARTIAL -> GateStatus.PARTIAL
            GateOutcome.BROKEN -> GateStatus.BROKEN
            GateOutcome.RED_FAILED -> GateStatus.FAILED
        }
        dao.updateGate(gate.copy(status = status, fraction = fraction, xpAwarded = xp))
        val kind = when (outcome) {
            GateOutcome.CLEARED -> NoticeKind.GATE_CLEARED
            GateOutcome.PARTIAL -> NoticeKind.GATE_PARTIAL
            GateOutcome.BROKEN -> NoticeKind.GATE_BROKEN
            GateOutcome.RED_FAILED -> NoticeKind.GATE_FAILED
        }
        emitted += GameEvent.Notice(kind, gate.title, xp.toString())
        when {
            outcome == GateOutcome.BROKEN ->
                lore.unlock(lore.seed(LoreKinds.GATE_BREAK) { it.realm == gate.realm }, gate.title, emitted, LoreTarget.GATE to gate.id, CustomLoreEvents.BREAK)
            outcome == GateOutcome.CLEARED && gate.isRed -> lore.unlock(lore.seed(LoreKinds.RED_GATE_CLEAR), gate.title, emitted)
        }
    }

    private fun pickTemplate(realm: Realm, rng: SplitMix64, used: MutableSet<String>): GateTemplate? {
        val options = content.gates.gates.filter { it.realm == realm && it.id !in used }
        if (options.isEmpty()) return null
        return rng.pick(options).also { used += it.id }
    }

    private suspend fun insertOffer(t: GateTemplate, rank: Rank, isRed: Boolean, week: Long, engineTarget: EngineTarget) {
        val id = dao.insertGate(
            GateEntity(
                publicId = "gt${dao.maxId() + 1}",
                templateId = t.id,
                title = t.title,
                lore = t.lore,
                realm = t.realm,
                rank = rank,
                days = t.days,
                isRed = isRed,
                status = GateStatus.OFFERED,
                source = GateSource.BOARD,
                boardWeek = week,
                opensDay = null,
                closesDay = null,
                fraction = null,
                xpAwarded = 0,
                createdAtMillis = time.now().toEpochMilli(),
            ),
        )
        t.objectives.forEachIndexed { i, o ->
            val template = o.templates.firstOrNull()?.let { TemplateLibrary[it] }
            val count = GateRules.isCount(o)
            val normal = GateRules.normalWeek(o, template?.let { engineTarget(it) })
            val unit = if (count) TargetUnit.OTHER else (template?.unit ?: TargetUnit.fromLabel(o.unit))
            val target = GateRules.objectiveTarget(normal, t.days, rank, count, unit)
            dao.insertObjective(GateObjectiveEntity(0, id, i, o.title, target, o.unit, o.templates.joinToString(","), count, 0.0, null))
        }
    }

    /** The smallest headroom the +10% cap leaves across the Body templates Gates use. */
    private suspend fun bodyHeadroom(engineTarget: EngineTarget, memory: Map<String, BodyTargetMemory>, week: Long): Double {
        val keys = content.gates.gates.filter { it.realm == Realm.BODY }
            .flatMap { g -> g.objectives.filter { it.sessions != null }.flatMap { it.templates } }
            .toSet()
        return bodyHeadroomFor(keys, engineTarget, memory, week)
    }

    private fun GateEntity.toModel(objectives: List<GateObjectiveEntity>, quests: List<QuestEntity>, level: Int): Gate {
        val items = objectives.sortedBy { it.position }.map { o ->
            val p = o.finalProgress ?: progress(o, this, quests)
            GateObjective(o.id, o.title, o.target, o.unitLabel, o.isCount, p, o.logged, o.templateKeys.isNotBlank())
        }
        val fraction = fraction ?: GateRules.fractionCleared(items.map { it.progress to it.target })
        return Gate(
            id = id,
            publicId = publicId,
            title = title,
            lore = lore,
            realm = realm,
            rank = rank,
            days = days,
            isRed = isRed,
            status = status,
            source = source,
            opensDay = opensDay,
            closesDay = closesDay,
            objectives = items,
            fraction = fraction,
            isRankTrial = kind == com.tirthankar.ascent.data.db.GateKind.RANK_TRIAL,
            attempt = attempt,
            xpPreview = if (status == GateStatus.OPEN || status == GateStatus.OFFERED) {
                GateRules.payoutXp(rank, level, if (status == GateStatus.OFFERED) 1.0 else fraction, isRed)
            } else {
                xpAwarded
            },
        )
    }

    internal companion object {
        /** Matching completed quests inside the Gate's days, plus what was logged by hand. */
        fun progress(o: GateObjectiveEntity, gate: GateEntity, quests: List<QuestEntity>): Double {
            val keys = o.templateKeys.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val opens = gate.opensDay ?: return o.logged
            val closes = gate.closesDay ?: return o.logged
            if (keys.isEmpty()) return o.logged
            val matching = quests.filter { q ->
                q.status == QuestStatus.DONE &&
                    (q.completedDay ?: q.day) in opens..closes &&
                    (q.templateKey ?: TemplateMatcher.match(q.title, q.realm, q.unit)?.key) in keys
            }
            val auto = if (o.isCount) {
                matching.size.toDouble()
            } else {
                val unit = TargetUnit.fromLabel(o.unitLabel)
                matching.filter { it.unit == unit }.sumOf { it.target }
            }
            return auto + o.logged
        }
    }
}
