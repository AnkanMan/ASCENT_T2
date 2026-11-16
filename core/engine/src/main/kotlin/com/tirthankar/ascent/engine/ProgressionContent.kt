package com.tirthankar.ascent.engine

import kotlinx.serialization.Serializable

// ---- Skills (content/skills.json, PHASE4.md section 1) ----

/** The three realm trees, the Observer tree (A-rank, no effects in Phase 4) and class skills. */
enum class SkillTree { BODY, MIND, SELF, OBSERVER, CLASS }

enum class SkillKind { PASSIVE, ACTIVE }

/** How often an active can be cast. HOURS is the only period cooldown reduction touches. */
enum class LimitPeriod { DAY, WEEK, MONTH, HOURS }

@Serializable
data class SkillLimit(
    val period: LimitPeriod,
    /** Casts per period (or charges per day for Insight-style skills). */
    val uses: Int = 1,
    /** For HOURS: the cooldown length. */
    val hours: Int? = null,
    /** Insight: charges per day = [uses] + ⌊WIS / [wisPerCharge]⌋. */
    val wisPerCharge: Int? = null,
)

/**
 * One skill. Passives carry [effects] folded by RewardCalculator; actives carry a
 * [handler] id that the engine's power rules know.
 */
@Serializable
data class SkillDef(
    val id: String,
    val name: String,
    val tree: SkillTree,
    /** 1–3, or 4 for the ultimate; 0 for class skills. */
    val tier: Int,
    val kind: SkillKind,
    val description: String,
    val prerequisites: List<String> = emptyList(),
    val mp: Int = 0,
    val limit: SkillLimit? = null,
    val effects: List<Effect> = emptyList(),
    val handler: String? = null,
    /** True for the Observer tree: shown locked, never unlockable in Phase 4. */
    val locked: Boolean = false,
)

@Serializable
data class SkillContent(
    val v: Int = 1,
    /** Gate stat threshold by tier (2 → 20, 3 → 35, 4 → 50). */
    val tierGates: Map<Int, Int>,
    /** The two stats per realm tree; the gate stat is the higher (A4). */
    val treeStats: Map<SkillTree, List<Stat>>,
    /** Respec without a token costs this much Essence. */
    val respecEssence: Int,
    val skills: List<SkillDef>,
) {
    fun skill(id: String): SkillDef? = skills.firstOrNull { it.id == id }
}

/** Handler ids for actives (realm trees and class skills). */
object PowerHandlers {
    const val SECOND_WIND = "second_wind"
    const val LIMIT_BREAK = "limit_break"
    const val UNBROKEN = "unbroken"
    const val HYPERFOCUS = "hyperfocus"
    const val INSIGHT = "insight"
    const val EIGENSTATE_COLLAPSE = "eigenstate_collapse"
    const val CLARITY = "clarity"
    const val MENTORS_EYE = "mentors_eye"
    const val FIRST_PRINCIPLES = "first_principles"
    const val PARALLEL_RUNS = "parallel_runs"
    const val OVERLOAD = "overload"
    const val STILLNESS = "stillness"
    const val LONG_WALK = "long_walk"

    val ALL: Set<String> = setOf(
        SECOND_WIND, LIMIT_BREAK, UNBROKEN, HYPERFOCUS, INSIGHT, EIGENSTATE_COLLAPSE, CLARITY, MENTORS_EYE,
        FIRST_PRINCIPLES, PARALLEL_RUNS, OVERLOAD, STILLNESS, LONG_WALK,
    )
}

/** Quest tags the app and Claude may use (PHASE4.md section 7). */
object KnownTags {
    val ALL: Set<String> = setOf(
        "social", "paper", "meditation", "walk", "mobility", "sleep", "journal", "derivation", "simulation", "writing",
    )
}

// ---- Classes (content/classes.json, PHASE4.md section 3) ----

@Serializable
data class ClassDef(
    val id: String,
    val name: String,
    /** The two stats it scores and boosts; empty for Polymath. */
    val stats: List<Stat>,
    /** Evolved names at B-rank and S-rank. */
    val evolutions: List<String>,
    /** +10% XP and +10% mastery on its stats (A13), as effects. */
    val bonus: List<Effect>,
    val unique: SkillDef,
    val polymath: Boolean = false,
)

@Serializable
data class ClassContent(
    val v: Int = 1,
    /** Polymath is eligible when the highest stat ≤ this × the lowest (A5). */
    val polymathRatio: Double,
    val awakenLevel: Int,
    val classes: List<ClassDef>,
) {
    fun byId(id: String): ClassDef? = classes.firstOrNull { it.id == id }
}

// ---- Rank trials (content/rank_trials.json, PHASE4.md section 4) ----

@Serializable
data class RankTrialDef(
    /** The rank the trial earns. */
    val rank: Rank,
    val level: Int,
    /** Extra effort over a normal week, as for Gates of this rank. */
    val effort: Double,
    /** The chest of the new rank's row. */
    val chest: DropSource,
)

@Serializable
data class RankTrialContent(
    val v: Int = 1,
    val days: Int,
    val objectivesPerRealm: Int,
    val retryDays: Int,
    val respecTokens: Int,
    val trials: List<RankTrialDef>,
    /** Objectives the generator picks from, per realm (same shape as Gate objectives). */
    val objectives: Map<Realm, List<GateObjectiveTemplate>>,
) {
    fun forRank(rank: Rank): RankTrialDef? = trials.firstOrNull { it.rank == rank }
}

// ---- Avatar (content/avatar.json, PHASE4.md section 5) ----

/** Colour tokens the design system knows; JSON names them instead of raw colours. */
enum class AuraToken { FAINT_GREY_BLUE, SYSTEM_BLUE, GLOW_CYAN, ARCANE_VIOLET, LEGEND_GOLD, WHITE_BLUE_BLAZE, INVERTED_GOLD }

@Serializable
data class ClassLayer(val cloak: String, val sigil: String)

@Serializable
data class AvatarContent(
    val v: Int = 1,
    val auras: Map<Rank, AuraToken>,
    val classLayers: Map<String, ClassLayer>,
    val cloaks: List<String>,
    val sigils: List<String>,
    /** Particles = min(base + ⌊L / perLevels⌋, max). */
    val particleBase: Int,
    val particlePerLevels: Int,
    val particleMax: Int,
    /** One orbit ring per [ringPerLevels] levels, up to [ringMax]. */
    val ringPerLevels: Int,
    val ringMax: Int,
)
