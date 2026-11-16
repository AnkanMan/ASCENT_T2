package com.tirthankar.ascent.engine

import java.time.LocalDate

/** Everything the planner needs to issue one day's Daily Quest from templates. */
data class PlannerInput(
    val day: LocalDate,
    val isRestDay: Boolean,
    val isRecoveryDay: Boolean,
    val fatigued: Boolean,
    /** Each realm's current multiplier d. */
    val difficulty: Map<Realm, Double>,
    val bodyLoad: BodyLoad,
    val baselines: Map<BaselineKey, Double>,
    val bodyMemory: Map<String, BodyTargetMemory>,
    /** How many training (non-rest) days have been issued so far; drives the Body rotation. */
    val trainingDayIndex: Int,
)

data class PlannedQuest(val template: QuestTemplate, val target: Double)

data class DailyPlan(
    val quests: List<PlannedQuest>,
    /** Body caps to store for the templates issued today. */
    val bodyMemory: Map<String, BodyTargetMemory>,
    val nextTrainingDayIndex: Int,
)

/**
 * Builds the offline Daily Quest (Task 30): 1 Body, 1–2 Mind, 1–2 Self (3–5 total).
 *
 * - Training days rotate strength, cardio, strength, mobility, strength, cardio,
 *   and strength alternates A (push and core) and B (legs), so the same muscle group
 *   never gets hard work on consecutive days.
 * - Rest and recovery days swap the Body slot for an easy walk or stretch.
 * - Fatigue cuts the day to three objectives (one recovery) at × 0.7.
 * - Targets are baseline × d, rounded, then Body targets are capped (+10% per week,
 *   60% deload weeks).
 */
object TemplatePlanner {

    private enum class Slot { STRENGTH, CARDIO, MOBILITY }

    private val TRAINING_CYCLE = listOf(
        Slot.STRENGTH, Slot.CARDIO, Slot.STRENGTH, Slot.MOBILITY, Slot.STRENGTH, Slot.CARDIO,
    )
    private val STRENGTH_PER_CYCLE = TRAINING_CYCLE.count { it == Slot.STRENGTH }

    fun bodyKindFor(trainingDayIndex: Int): TemplateKind {
        require(trainingDayIndex >= 0) { "trainingDayIndex must be >= 0" }
        val position = trainingDayIndex % TRAINING_CYCLE.size
        return when (TRAINING_CYCLE[position]) {
            Slot.CARDIO -> TemplateKind.CARDIO
            Slot.MOBILITY -> TemplateKind.MOBILITY
            Slot.STRENGTH -> {
                val strengthNumber = (trainingDayIndex / TRAINING_CYCLE.size) * STRENGTH_PER_CYCLE +
                    TRAINING_CYCLE.take(position).count { it == Slot.STRENGTH }
                if (strengthNumber % 2 == 0) TemplateKind.STRENGTH_A else TemplateKind.STRENGTH_B
            }
        }
    }

    fun plan(input: PlannerInput): DailyPlan {
        val seed = input.day.toEpochDay()
        val restLike = input.isRestDay || input.isRecoveryDay
        val training = !restLike && !input.fatigued

        val templates = buildList {
            add(
                if (training) pick(bodyKindFor(input.trainingDayIndex), seed / 2)
                else pick(TemplateKind.BODY_RECOVERY, seed),
            )
            add(pick(TemplateKind.DEEP_WORK, seed))
            if (!input.fatigued && seed % 2 == 0L) add(pick(TemplateKind.MIND_OUTPUT, seed / 2))
            add(pick(TemplateKind.SLEEP, seed))
            if (!input.fatigued) add(selfHabit(seed))
        }

        val memory = mutableMapOf<String, BodyTargetMemory>()
        val quests = templates.map { template ->
            PlannedQuest(template, sizeTarget(template, input, memory))
        }
        return DailyPlan(
            quests = quests,
            bodyMemory = memory,
            nextTrainingDayIndex = if (training) input.trainingDayIndex + 1 else input.trainingDayIndex,
        )
    }

    /** The engine-sized target for [template] today. Body caps are written to [memoryOut]. */
    fun sizeTarget(
        template: QuestTemplate,
        input: PlannerInput,
        memoryOut: MutableMap<String, BodyTargetMemory> = mutableMapOf(),
    ): Double {
        val base = template.baseline
            ?.let { (input.baselines[it] ?: Baselines.DEFAULTS.getValue(it)) * template.baselineFactor }
            ?: template.fixedTarget!!
        if (!template.scales) {
            return if (template.unit == TargetUnit.OTHER) base else TargetScaler.scaledTarget(base, 1.0, template.unit)
        }
        val fatigue = if (input.fatigued) Fatigue.MULTIPLIER else 1.0
        val d = input.difficulty[template.realm] ?: DifficultyController.INITIAL_MULTIPLIER
        // Body deload is applied after the weekly cap, inside capBodyTarget.
        val proposed = TargetScaler.scaledTarget(base, d * fatigue, template.unit)
        if (template.realm != Realm.BODY) return proposed
        val (target, memory) = SafetyRules.capBodyTarget(
            proposed = proposed,
            memory = input.bodyMemory[template.key],
            weekStartDay = GameClock.weekStart(input.day).toEpochDay(),
            deload = input.bodyLoad.deload,
            unit = template.unit,
        )
        memoryOut[template.key] = memory
        return target
    }

    /** Reading every other day; the other habits rotate in between. */
    private fun selfHabit(seed: Long): QuestTemplate {
        if (seed % 2 == 0L) return TemplateLibrary["self_reading"]!!
        val others = TemplateLibrary.ofKind(TemplateKind.SELF_HABIT).filter { it.key != "self_reading" }
        return others[Math.floorMod(seed / 2, others.size.toLong()).toInt()]
    }

    private fun pick(kind: TemplateKind, seed: Long): QuestTemplate {
        val options = TemplateLibrary.ofKind(kind)
        return options[Math.floorMod(seed, options.size.toLong()).toInt()]
    }
}
