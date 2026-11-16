package com.tirthankar.ascent.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A bonus from a skill, item or class, stored as JSON data (SPEC "Effects as data").
 * Example: {"kind":"xp_mult","realm":"MIND","value":1.15}
 *
 * Multipliers are written as factors (1.15 = +15%) and stack additively in RewardCalculator.
 */
@Serializable
sealed interface Effect {

    /**
     * Quest XP multiplier. No scope applies to every quest; realm, stat and/or [tag]
     * narrow it. Tags are set by the app per quest, e.g. "sleep", "before_9",
     * "weakest_realm" (see [QuestTags]).
     */
    @Serializable
    @SerialName("xp_mult")
    data class XpMult(
        val realm: Realm? = null,
        val stat: Stat? = null,
        val value: Double,
        val tag: String? = null,
    ) : Effect {
        init {
            require(value > 0) { "xp_mult value must be > 0, was $value" }
        }
    }

    /** Mastery gain multiplier for one stat, e.g. Iron Body: STR 1.10. A [tag] narrows it. */
    @Serializable
    @SerialName("mastery_mult")
    data class MasteryMult(
        val stat: Stat,
        val value: Double,
        val tag: String? = null,
    ) : Effect {
        init {
            require(value > 0) { "mastery_mult value must be > 0, was $value" }
        }
    }

    /** Raises the streak bonus cap, e.g. Toda Chain: 1.6. The highest cap wins. */
    @Serializable
    @SerialName("streak_cap")
    data class StreakCap(
        val value: Double,
    ) : Effect {
        init {
            require(value >= 1.0) { "streak_cap value must be >= 1.0, was $value" }
        }
    }

    /** Adds to the critical chance, as a fraction (0.05 = +5 percentage points). */
    @Serializable
    @SerialName("crit_bonus")
    data class CritBonus(
        val value: Double,
    ) : Effect {
        init {
            require(value in 0.0..1.0) { "crit_bonus value must be in [0, 1], was $value" }
        }
    }

    /** HP restored at each daily reset, as a fraction of max HP (0.05 = 5%). Phase 3 gear. */
    @Serializable
    @SerialName("hp_regen")
    data class HpRegen(
        val value: Double,
    ) : Effect {
        init {
            require(value in 0.0..1.0) { "hp_regen value must be in [0, 1], was $value" }
        }
    }

    /** Gate XP multiplier, as a factor (1.10 = +10%). Stacks additively. */
    @Serializable
    @SerialName("gate_reward")
    data class GateReward(
        val value: Double,
        val realm: Realm? = null,
    ) : Effect {
        init {
            require(value > 0) { "gate_reward value must be > 0, was $value" }
        }
    }

    /** Chance that a loot roll shifts one rarity row up (0.05 = 5%). Stacks additively. */
    @Serializable
    @SerialName("chest_luck")
    data class ChestLuck(
        val value: Double,
    ) : Effect {
        init {
            require(value in 0.0..1.0) { "chest_luck value must be in [0, 1], was $value" }
        }
    }

    // ---- Phase 4 effects (skills and classes). Data only here; rules live in the engine. ----

    /**
     * A chain bonus (Momentum, Focus Chain): +[perDay] XP in [realm] for each consecutive
     * qualifying day of [chain], up to [cap]. Rest days are neutral (A3).
     */
    @Serializable
    @SerialName("chain_xp")
    data class ChainXp(val realm: Realm, val perDay: Double, val cap: Double, val chain: String) : Effect

    /** Rest-day HP restore multiplier (Recovery Aura: 1.5). */
    @Serializable
    @SerialName("rest_hp_mult")
    data class RestHpMult(val value: Double) : Effect

    /** Quests with [tag] pay full mastery to every stat in [stats] (Literature Sense). */
    @Serializable
    @SerialName("dual_mastery")
    data class DualMastery(val tag: String, val stats: List<Stat>) : Effect

    /** Shielded misses (Streak Shield: one per week; Iron Lattice: one more per month). */
    @Serializable
    @SerialName("streak_shield")
    data class StreakShield(val perWeek: Int = 0, val perMonth: Int = 0) : Effect

    /** Quests with [tag] also restore [hp] HP (Bonds). */
    @Serializable
    @SerialName("tag_hp")
    data class TagHp(val tag: String, val hp: Int) : Effect

    /** Finishing the rest-day quest fully restores HP and MP and gives +[nextDayXp] XP the next day (Equilibrium). */
    @Serializable
    @SerialName("rest_restore")
    data class RestRestore(val nextDayXp: Double) : Effect

    /** Extra Insight charges per day; [free] charges cost no MP (Hamiltonian Blade). */
    @Serializable
    @SerialName("insight_charges")
    data class InsightCharges(val value: Int, val free: Boolean = false) : Effect

    /** Cooldown reduction on hour-based cooldowns, for one [skill] or all (A12). */
    @Serializable
    @SerialName("cooldown_reduction")
    data class CooldownReduction(val value: Double, val skill: String? = null) : Effect

    /** Polymath's Resonance: +[perRealm] XP for each realm with a completed quest today, up to [cap]. */
    @Serializable
    @SerialName("resonance")
    data class Resonance(val perRealm: Double, val cap: Double) : Effect
}

/** Tags the app attaches to a quest so tagged [Effect.XpMult] effects can apply. */
object QuestTags {
    /** A lights-out or other sleep quest. */
    const val SLEEP = "sleep"

    /** Completed before 09:00 local time. */
    const val BEFORE_NINE = "before_9"

    /** The quest's realm has the lowest completion over the last 7 days. */
    const val WEAKEST_REALM = "weakest_realm"

    /** Completed on a rest or recovery day. */
    const val REST_DAY = "rest_day"

    /** A Trial attempt (mastery only). */
    const val TRIAL = "trial"
}
