package com.tirthankar.ascent.engine

/** Which slot of the day a template fills. */
enum class TemplateKind {
    /** Push and core (A day). */
    STRENGTH_A,

    /** Legs (B day). */
    STRENGTH_B,
    CARDIO,
    MOBILITY,

    /** The Body slot on rest, recovery and fatigue days. */
    BODY_RECOVERY,
    DEEP_WORK,
    MIND_OUTPUT,
    SLEEP,
    SELF_HABIT,
}

/** Muscle groups, so hard work never hits the same group on consecutive days. */
enum class MuscleGroup { UPPER, LOWER, CORE, NONE }

/**
 * An offline quest. Titles are not stored here: the app resolves [key] to a string
 * resource. A template either scales a [baseline] by the realm's difficulty, or has a
 * [fixedTarget] that scales only when [scales] is true.
 */
data class QuestTemplate(
    val key: String,
    val kind: TemplateKind,
    val stat: Stat,
    val tier: Tier,
    val unit: TargetUnit,
    val baseline: BaselineKey? = null,
    val baselineFactor: Double = 1.0,
    val fixedTarget: Double? = null,
    val scales: Boolean = true,
    val muscle: MuscleGroup = MuscleGroup.NONE,
) {
    val realm: Realm get() = stat.realm

    init {
        require((baseline == null) != (fixedTarget == null)) { "$key needs a baseline or a fixed target" }
        require(baseline == null || baseline.unit == unit) { "$key unit must match its baseline" }
    }
}

/** About 30 original quests across the three realms (Task 29). */
object TemplateLibrary {

    val all: List<QuestTemplate> = listOf(
        // Body: strength A (push + core)
        QuestTemplate("body_push_ups", TemplateKind.STRENGTH_A, Stat.STR, Tier.NORMAL, TargetUnit.REPS, BaselineKey.PUSH_UPS, muscle = MuscleGroup.UPPER),
        QuestTemplate("body_plank", TemplateKind.STRENGTH_A, Stat.STR, Tier.NORMAL, TargetUnit.SECONDS, BaselineKey.PLANK, muscle = MuscleGroup.CORE),
        QuestTemplate("body_incline_push_ups", TemplateKind.STRENGTH_A, Stat.STR, Tier.EASY, TargetUnit.REPS, BaselineKey.PUSH_UPS, baselineFactor = 1.25, muscle = MuscleGroup.UPPER),
        // Body: strength B (legs)
        QuestTemplate("body_squats", TemplateKind.STRENGTH_B, Stat.STR, Tier.NORMAL, TargetUnit.REPS, BaselineKey.SQUATS, muscle = MuscleGroup.LOWER),
        QuestTemplate("body_lunges", TemplateKind.STRENGTH_B, Stat.STR, Tier.NORMAL, TargetUnit.REPS, BaselineKey.SQUATS, baselineFactor = 0.5, muscle = MuscleGroup.LOWER),
        QuestTemplate("body_glute_bridges", TemplateKind.STRENGTH_B, Stat.STR, Tier.EASY, TargetUnit.REPS, BaselineKey.SQUATS, muscle = MuscleGroup.LOWER),
        // Body: cardio
        QuestTemplate("body_steps", TemplateKind.CARDIO, Stat.AGI, Tier.NORMAL, TargetUnit.STEPS, BaselineKey.STEPS),
        QuestTemplate("body_brisk_walk", TemplateKind.CARDIO, Stat.AGI, Tier.NORMAL, TargetUnit.MINUTES, BaselineKey.WALK, baselineFactor = 1.5),
        // Body: mobility
        QuestTemplate("body_mobility", TemplateKind.MOBILITY, Stat.AGI, Tier.EASY, TargetUnit.MINUTES, fixedTarget = 15.0),
        // Body: rest, recovery and fatigue days
        QuestTemplate("body_easy_walk", TemplateKind.BODY_RECOVERY, Stat.AGI, Tier.EASY, TargetUnit.MINUTES, BaselineKey.WALK, scales = false),
        QuestTemplate("body_stretch", TemplateKind.BODY_RECOVERY, Stat.AGI, Tier.EASY, TargetUnit.MINUTES, fixedTarget = 15.0, scales = false),
        // Mind: deep work
        QuestTemplate("mind_deep_work", TemplateKind.DEEP_WORK, Stat.INT, Tier.NORMAL, TargetUnit.MINUTES, BaselineKey.DEEP_WORK),
        QuestTemplate("mind_phone_free_block", TemplateKind.DEEP_WORK, Stat.FOC, Tier.NORMAL, TargetUnit.MINUTES, BaselineKey.DEEP_WORK),
        // Mind: one concrete output
        QuestTemplate("mind_derivation_page", TemplateKind.MIND_OUTPUT, Stat.INT, Tier.NORMAL, TargetUnit.OTHER, fixedTarget = 1.0, scales = false),
        QuestTemplate("mind_paper_summary", TemplateKind.MIND_OUTPUT, Stat.INT, Tier.NORMAL, TargetUnit.OTHER, fixedTarget = 1.0, scales = false),
        QuestTemplate("mind_simulation_run", TemplateKind.MIND_OUTPUT, Stat.INT, Tier.NORMAL, TargetUnit.OTHER, fixedTarget = 1.0, scales = false),
        QuestTemplate("mind_research_log", TemplateKind.MIND_OUTPUT, Stat.FOC, Tier.EASY, TargetUnit.OTHER, fixedTarget = 1.0, scales = false),
        QuestTemplate("mind_close_todo", TemplateKind.MIND_OUTPUT, Stat.FOC, Tier.EASY, TargetUnit.OTHER, fixedTarget = 1.0, scales = false),
        QuestTemplate("mind_plan_tomorrow", TemplateKind.MIND_OUTPUT, Stat.FOC, Tier.EASY, TargetUnit.OTHER, fixedTarget = 1.0, scales = false),
        // Self: sleep
        QuestTemplate("self_lights_out", TemplateKind.SLEEP, Stat.VIT, Tier.NORMAL, TargetUnit.OTHER, fixedTarget = 1.0, scales = false),
        // Self: rotating habits
        QuestTemplate("self_reading", TemplateKind.SELF_HABIT, Stat.WIS, Tier.NORMAL, TargetUnit.PAGES, BaselineKey.READING),
        QuestTemplate("self_journal", TemplateKind.SELF_HABIT, Stat.WIS, Tier.EASY, TargetUnit.OTHER, fixedTarget = 1.0, scales = false),
        QuestTemplate("self_message_someone", TemplateKind.SELF_HABIT, Stat.WIS, Tier.EASY, TargetUnit.OTHER, fixedTarget = 1.0, scales = false),
        QuestTemplate("self_new_skill", TemplateKind.SELF_HABIT, Stat.WIS, Tier.NORMAL, TargetUnit.MINUTES, fixedTarget = 20.0),
        QuestTemplate("self_water", TemplateKind.SELF_HABIT, Stat.VIT, Tier.EASY, TargetUnit.OTHER, fixedTarget = 8.0, scales = false),
        QuestTemplate("self_protein", TemplateKind.SELF_HABIT, Stat.VIT, Tier.EASY, TargetUnit.OTHER, fixedTarget = 1.0, scales = false),
        QuestTemplate("self_cook_meal", TemplateKind.SELF_HABIT, Stat.VIT, Tier.NORMAL, TargetUnit.OTHER, fixedTarget = 1.0, scales = false),
        QuestTemplate("self_daylight", TemplateKind.SELF_HABIT, Stat.VIT, Tier.EASY, TargetUnit.MINUTES, fixedTarget = 15.0, scales = false),
        QuestTemplate("self_breathing", TemplateKind.SELF_HABIT, Stat.VIT, Tier.EASY, TargetUnit.MINUTES, fixedTarget = 10.0, scales = false),
        QuestTemplate("self_screens_off", TemplateKind.SELF_HABIT, Stat.VIT, Tier.EASY, TargetUnit.OTHER, fixedTarget = 1.0, scales = false),
        QuestTemplate("self_phone_free_morning", TemplateKind.SELF_HABIT, Stat.WIS, Tier.EASY, TargetUnit.OTHER, fixedTarget = 1.0, scales = false),
    )

    private val byKey = all.associateBy { it.key }

    operator fun get(key: String): QuestTemplate? = byKey[key]

    fun ofKind(kind: TemplateKind): List<QuestTemplate> = all.filter { it.kind == kind }
}
