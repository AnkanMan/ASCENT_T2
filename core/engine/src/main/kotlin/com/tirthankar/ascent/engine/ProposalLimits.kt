package com.tirthankar.ascent.engine

import kotlin.math.max

/**
 * Clamps targets that arrive from outside the engine (Claude proposals, manual adds),
 * so the engine alone decides *how much* (SPEC "Adaptive difficulty engine").
 */
object ProposalLimits {

    /** Band around the engine's own target for a matching template. */
    const val BAND_LOW = 0.5
    const val BAND_HIGH = 1.5

    /** Upper bounds for quests with no matching template. */
    fun absoluteMax(realm: Realm, unit: TargetUnit): Double? = when (unit) {
        TargetUnit.REPS -> 300.0
        TargetUnit.MINUTES -> if (realm == Realm.BODY) 120.0 else 240.0
        TargetUnit.STEPS -> 25_000.0
        TargetUnit.PAGES -> 150.0
        TargetUnit.KM -> 25.0
        TargetUnit.SECONDS -> 600.0
        TargetUnit.HOURS -> 12.0
        TargetUnit.OTHER -> null
    }

    /**
     * Clamps [proposed]. With [reference] (the engine's target for the matching template
     * today) the band is [0.5, 1.5] × reference, and Body never goes above the reference,
     * which already carries the weekly +10% cap. Sleep quests in hours never drop below 7 h.
     */
    fun clamp(
        realm: Realm,
        unit: TargetUnit,
        proposed: Double,
        reference: Double?,
        isSleep: Boolean = false,
    ): Double {
        var value = max(proposed, unit.fineStep)
        if (reference != null) {
            val high = if (realm == Realm.BODY) reference else reference * BAND_HIGH
            val low = minOf(reference * BAND_LOW, high)
            value = value.coerceIn(low, high)
            value = max(TargetScaler.floorToStep(value, unit.fineStep), unit.fineStep)
        } else {
            absoluteMax(realm, unit)?.let { value = value.coerceAtMost(it) }
        }
        if (isSleep && unit == TargetUnit.HOURS) value = SafetyRules.sleepTargetHours(value)
        return value
    }
}

/** Matches free-text quest titles to a template, so Claude's quests get the same caps. */
object TemplateMatcher {

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""push[\s-]?ups?""", RegexOption.IGNORE_CASE) to "body_push_ups",
        Regex("""\bplank""", RegexOption.IGNORE_CASE) to "body_plank",
        Regex("""\bsquats?\b""", RegexOption.IGNORE_CASE) to "body_squats",
        Regex("""\blunges?\b""", RegexOption.IGNORE_CASE) to "body_lunges",
        Regex("""glute\s+bridges?""", RegexOption.IGNORE_CASE) to "body_glute_bridges",
        Regex("""\bsteps?\b""", RegexOption.IGNORE_CASE) to "body_steps",
        Regex("""\bdeep[\s-]?work\b""", RegexOption.IGNORE_CASE) to "mind_deep_work",
        Regex("""\bread(ing)?\b""", RegexOption.IGNORE_CASE) to "self_reading",
    )

    private val sleep = Regex("""\b(sleep|lights[\s-]?out|bed\s*time)\b""", RegexOption.IGNORE_CASE)

    /** The template for [title], if its realm and unit also agree. */
    fun match(title: String, realm: Realm, unit: TargetUnit): QuestTemplate? =
        rules.firstOrNull { (regex, _) -> regex.containsMatchIn(title) }
            ?.let { (_, key) -> TemplateLibrary[key] }
            ?.takeIf { it.realm == realm && it.unit == unit }

    fun isSleep(title: String): Boolean = sleep.containsMatchIn(title)

    /** Muscle group a Body title works, for the consecutive-day rule. */
    fun muscleGroup(title: String): MuscleGroup =
        rules.firstOrNull { (regex, _) -> regex.containsMatchIn(title) }
            ?.let { (_, key) -> TemplateLibrary[key]?.muscle }
            ?: MuscleGroup.NONE
}
