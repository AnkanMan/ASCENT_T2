package com.tirthankar.ascent.bridge

import com.tirthankar.ascent.data.db.ArcStatus
import com.tirthankar.ascent.data.db.DailySnapshotEntity
import com.tirthankar.ascent.data.db.GateStatus
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.model.Arc
import com.tirthankar.ascent.data.model.Boss
import com.tirthankar.ascent.data.model.Gate
import com.tirthankar.ascent.data.model.Goal
import com.tirthankar.ascent.data.model.PlayerProfile
import com.tirthankar.ascent.data.model.Quest
import com.tirthankar.ascent.engine.DerivedStats
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.XpCurve
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Everything the ASCENT STATUS message reports. Aggregates only: no raw health records. */
data class StatusSnapshot(
    val today: LocalDate,
    val resetTime: LocalTime,
    val isRestDay: Boolean,
    val player: PlayerProfile,
    val goals: List<Goal>,
    val todayQuests: List<Quest>,
    val weekQuests: List<Quest>,
    val lastDays: List<DailySnapshotEntity>,
    val notes: String,
    /** A quick starter such as "Plan my day", or null. */
    val request: String?,
    // v2 (Phase 3)
    val sideQuests: List<Quest> = emptyList(),
    val arcs: List<Arc> = emptyList(),
    val gates: List<Gate> = emptyList(),
    val bosses: List<Boss> = emptyList(),
    /** History days: 7, or 28 for Mentor's Eye (A7). */
    val historyDays: Int = 7,
    // v3 (Phase 4)
    val classInfo: ClassStatus? = null,
    val powers: List<PowerStatus> = emptyList(),
    val trial: TrialStatus? = null,
    // v4 (Phase 5)
    /** Habit strength percent by series id; a quest whose series is here gets `strength NN%`. */
    val strengths: Map<String, Int> = emptyMap(),
    val extras: ExtrasStatus = ExtrasStatus(),
)

/** The grind guard today: self-added quests completed, and what the next one pays. */
data class ExtrasStatus(val count: Int = 0, val nextPercent: Int = 100)

/** The class line: its name at your rank and its two stats (none for Polymath). */
data class ClassStatus(val name: String, val stats: List<Stat>, val polymath: Boolean)

/** One unlocked active and where it stands: ready, n/m charges, a cooldown, or used up. */
data class PowerStatus(val name: String, val state: PowerState)

sealed interface PowerState {
    data object Ready : PowerState
    data class Charges(val left: Int, val total: Int) : PowerState
    data class Cooldown(val hours: Int) : PowerState
    data class Used(val period: String) : PowerState
}

/** The open rank trial: the rank it earns, days left and objectives done. */
data class TrialStatus(val rank: Rank, val daysLeft: Int, val done: Int, val total: Int)

/** Request lines the app's powers send (A7, A8). Wire format: fixed English. */
object PowerRequests {
    const val REVIEW = "Review"
    const val COLLAPSE = "Collapse: which one task?"
    fun stuck(problem: String): String = "Stuck: " + problem.trim().replace('\n', ' ')
}

/**
 * Builds the ASCENT STATUS text (Task 32). This is a wire format read by Claude, so
 * it is fixed English and documented in docs/ASCENT-System-Project-Instructions.md;
 * the app's own UI text lives in strings.xml.
 */
object StatusTextBuilder {

    private val dayFormat = DateTimeFormatter.ofPattern("MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun build(s: StatusSnapshot): String = buildString {
        val p = s.player
        val progress = XpCurve.levelProgress(p.totalXp)
        appendLine("ASCENT STATUS · v4")
        appendLine(
            "Date: ${s.today} (${weekday(s.today)}) · resets ${s.resetTime.format(timeFormat)} · " +
                "rest day: ${if (s.isRestDay) "yes" else "no"}",
        )
        appendLine(
            "Player: Lv ${p.level} · Rank ${p.rank.name} · XP ${progress.xpIntoLevel}/${progress.xpForNext} · " +
                "streak ${p.streakDays} (best ${p.bestStreak}) · " +
                "HP ${p.hp}/${DerivedStats.maxHp(p.stats)} · MP ${p.mp}/${DerivedStats.maxMp(p.stats)}",
        )
        appendLine("Stats: " + Stat.entries.joinToString(" · ") { "${it.name} ${p.stats[it]}" })
        appendLine("Class: " + classLine(s.classInfo))
        appendLine("Powers: " + if (s.powers.isEmpty()) "none" else s.powers.joinToString(" · ") { "${it.name} ${powerState(it.state)}" })
        appendLine(
            "Rank trial: " + (s.trial?.let { "${it.rank.name} · open · ${it.daysLeft} days left · ${it.done}/${it.total}" } ?: "none"),
        )
        appendLine("Difficulty: " + Realm.entries.joinToString(" · ") { "${it.name} ${"%.2f".format(Locale.ROOT, p.difficulty[it] ?: 1.0)}" })

        appendLine()
        appendLine("Goals:")
        if (s.goals.isEmpty()) appendLine("- none")
        s.goals.forEach { g ->
            val target = g.target?.let { " · ${number(g.progress)}/${number(it)}${g.unitLabel?.let { u -> " $u" }.orEmpty()}" }.orEmpty()
            appendLine("- ${g.publicId} · ${g.horizon.name.lowercase()} · ${g.realm.name} · ${g.title}$target")
        }

        appendLine()
        appendLine("Today's quests:")
        questLines(s.todayQuests, s.goals, strengths = s.strengths)

        appendLine()
        appendLine("This week's quests:")
        questLines(s.weekQuests, s.goals, strengths = s.strengths)

        appendLine()
        appendLine("Side quests:")
        questLines(s.sideQuests, s.goals, today = s.today)

        appendLine()
        appendLine("Extras today: ${s.extras.count} (next pays ${s.extras.nextPercent}%)")

        appendLine()
        appendLine("Arcs:")
        arcLines(s.arcs)

        appendLine()
        appendLine("Gates:")
        gateLines(s.gates, s.today)

        appendLine()
        appendLine("Bosses:")
        bossLines(s.bosses)

        appendLine()
        appendLine("Last ${s.historyDays} days:")
        if (s.lastDays.isEmpty()) appendLine("- no history yet")
        s.lastDays.forEach { d ->
            val date = LocalDate.ofEpochDay(d.day)
            appendLine(
                "- ${date.format(dayFormat)} ${weekday(date)} · ${d.completed}/${d.objectives}" +
                    " · BODY ${d.bodyDone}/${d.bodyTotal} · MIND ${d.mindDone}/${d.mindTotal} · SELF ${d.selfDone}/${d.selfTotal}" +
                    (if (d.neutral) " · neutral" else if (d.restDay) " · rest" else ""),
            )
        }

        if (s.notes.isNotBlank()) {
            appendLine()
            appendLine("Notes: ${s.notes.trim()}")
        }
        if (!s.request.isNullOrBlank()) {
            appendLine()
            appendLine("Request: ${s.request.trim()}")
        }
    }.trimEnd()

    private fun classLine(c: ClassStatus?): String = when {
        c == null -> "none"
        c.polymath -> "${c.name} · Resonance +5% per realm done today, max +15%"
        else -> "${c.name} (${c.stats.joinToString("+") { it.name }}) · bonus +10% ${c.stats.joinToString("/") { it.name }}"
    }

    private fun powerState(state: PowerState): String = when (state) {
        PowerState.Ready -> "ready"
        is PowerState.Charges -> "${state.left}/${state.total}"
        is PowerState.Cooldown -> "cooldown ${state.hours} h"
        is PowerState.Used -> "used ${state.period}"
    }

    private fun StringBuilder.arcLines(arcs: List<Arc>) {
        if (arcs.isEmpty()) {
            appendLine("- none")
            return
        }
        arcs.forEach { arc ->
            val chapter = arc.activeChapter
            val where = chapter?.let { " · chapter ${it.position + 1}/${arc.chapters.size} ${it.title}" }.orEmpty()
            appendLine("- ${arc.publicId} · ${arc.realm.name} · ${arc.title} · ${arc.status.name.lowercase()}$where")
            if (arc.status == ArcStatus.ACTIVE) {
                chapter?.objectives?.forEach { o ->
                    appendLine("  - ${o.title} · ${number(o.progress)}/${number(o.target)} ${o.unitLabel}")
                }
            }
        }
    }

    private fun StringBuilder.gateLines(gates: List<Gate>, today: LocalDate) {
        if (gates.isEmpty()) {
            appendLine("- none")
            return
        }
        gates.forEach { g ->
            val red = if (g.isRed) " · red" else ""
            val state = when (g.status) {
                GateStatus.OPEN -> {
                    val left = (g.closesDay ?: today.toEpochDay()) - today.toEpochDay() + 1
                    "open · $left days left · ${(g.fraction * 100).roundToInt()}%"
                }
                GateStatus.OFFERED -> "offered · ${g.days} days"
                else -> g.status.name.lowercase()
            }
            appendLine("- ${g.publicId} · ${g.rank.name} · ${g.realm.name} · ${g.title}$red · $state")
            if (g.status == GateStatus.OPEN) {
                g.objectives.forEach { o -> appendLine("  - ${o.title} · ${number(o.progress)}/${number(o.target)} ${o.unitLabel}") }
            }
        }
    }

    private fun StringBuilder.bossLines(bosses: List<Boss>) {
        if (bosses.isEmpty()) {
            appendLine("- none")
            return
        }
        bosses.forEach { b ->
            if (b.isTrial) {
                val state = if (b.trialOpen) "open" else "reopens later"
                appendLine("- ${b.publicId} · ${b.title} · trial · record ${number(b.recordToBeat ?: 0.0)} · $state")
                return@forEach
            }
            val phase = b.phases.getOrNull(b.currentPhase)?.let { " · phase ${b.currentPhase + 1}/${b.phases.size} $it" }.orEmpty()
            val deadline = b.deadlineDay?.let { " · deadline ${LocalDate.ofEpochDay(it)}" }.orEmpty()
            val raid = if (b.isRaid) " · raid" else ""
            appendLine("- ${b.publicId} · ${b.title}$raid · HP ${b.hp}/${b.maxHp}$phase$deadline")
            b.tasks.filter { !it.done }.forEach { t ->
                val lock = if (t.unlocked) "open" else "locked"
                appendLine("  - ${t.publicId} · phase ${t.phase + 1} · ${t.title} · ${number(t.hours)} h · $lock")
            }
        }
    }

    private fun StringBuilder.questLines(
        quests: List<Quest>,
        goals: List<Goal>,
        today: LocalDate? = null,
        strengths: Map<String, Int> = emptyMap(),
    ) {
        if (quests.isEmpty()) {
            appendLine("- none")
            return
        }
        quests.forEach { q ->
            val state = when (q.status) {
                QuestStatus.DONE -> "done"
                QuestStatus.OPEN -> "open"
                QuestStatus.EXPIRED -> "expired"
                QuestStatus.REMOVED -> "removed"
            }
            val progress = if (q.status == QuestStatus.DONE) number(q.target) else "0"
            val tags = buildList {
                add(q.tier.name.lowercase())
                add(state)
                if (q.source == QuestSource.TEMPLATE) add("template")
                if (q.recurring) add("repeats")
                when (q.kind) {
                    QuestKind.PENALTY -> add("penalty")
                    QuestKind.EMERGENCY -> add("emergency")
                    QuestKind.SIDE -> q.expiresDay?.let { end ->
                        if (today != null) add("ends ${LocalDate.ofEpochDay(end).format(dayFormat)}")
                    }
                    QuestKind.NORMAL -> if (!q.isDaily && q.scope.name == "DAILY") add("extra")
                }
                q.goalId?.let { id -> goals.firstOrNull { it.id == id }?.let { add("goal ${it.publicId}") } }
                q.seriesId?.let { strengths[it] }?.let { add("strength $it%") }
            }
            val scope = if (q.kind == QuestKind.SIDE) "side" else q.scope.name.lowercase()
            appendLine(
                "- ${q.publicId} · $scope · ${q.realm.name} · ${q.title} · " +
                    "$progress/${number(q.target)} ${q.unitLabel} · ${tags.joinToString(" · ")}",
            )
        }
    }

    private fun weekday(date: LocalDate) = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    internal fun number(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString() else "%.1f".format(Locale.ROOT, value)
}
