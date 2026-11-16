package com.tirthankar.ascent.engine

import kotlin.math.min

/** The layered avatar's numbers (PHASE4.md section 5), from content/avatar.json. */
object AvatarRules {

    /** Aura particles: min(base + ⌊L / perLevels⌋, max). */
    fun particles(level: Int, content: AvatarContent): Int =
        min(content.particleBase + level.coerceAtLeast(0) / content.particlePerLevels, content.particleMax)

    /** One orbit ring per [AvatarContent.ringPerLevels] levels, up to [AvatarContent.ringMax]. */
    fun rings(level: Int, content: AvatarContent): Int =
        min(level.coerceAtLeast(0) / content.ringPerLevels, content.ringMax)

    /** The rank's aura token; ranks without one fall back to E's. */
    fun aura(rank: Rank, content: AvatarContent): AuraToken =
        content.auras[rank] ?: content.auras[Rank.E] ?: AuraToken.FAINT_GREY_BLUE
}

/**
 * Tags a template's quests carry without Claude saying so (PHASE4.md section 7), so
 * class skills and passives also work on the app's own quests.
 */
object TemplateTags {

    private val byTemplate: Map<String, Set<String>> = mapOf(
        "self_breathing" to setOf("meditation"),
        "body_easy_walk" to setOf("walk"),
        "body_mobility" to setOf("mobility"),
        "body_stretch" to setOf("mobility"),
        "mind_paper_summary" to setOf("paper", "writing"),
        "mind_derivation_page" to setOf("derivation"),
        "mind_simulation_run" to setOf("simulation"),
        "self_journal" to setOf("journal"),
        "self_lights_out" to setOf("sleep"),
    )

    fun of(templateKey: String?): Set<String> = templateKey?.let { byTemplate[it] }.orEmpty()

    /** Every key it names is a real template and every tag a known one (checked in tests). */
    internal val all: Map<String, Set<String>> get() = byTemplate
}
