package com.tirthankar.ascent.engine

import kotlin.math.min

/** Values derived from attributes (SPEC "Derived values"). */
object DerivedStats {

    /** Max HP = 100 + 5 × STR + 10 × VIT. */
    fun maxHp(stats: StatBlock): Int = 100 + 5 * stats.str + 10 * stats.vit

    /** Max MP = 50 + 5 × INT + 3 × FOC. */
    fun maxMp(stats: StatBlock): Int = 50 + 5 * stats.int + 3 * stats.foc

    /** Critical chance = min(25%, 0.5% × AGI), as a fraction in [0, 0.25]. */
    fun critChance(stats: StatBlock): Double = min(0.25, stats.agi / 200.0)

    /** Cooldown reduction = min(40%, 1% × FOC), as a fraction in [0, 0.40]. */
    fun cooldownReduction(stats: StatBlock): Double = min(0.40, stats.foc / 100.0)
}

/** Organic stat growth (SPEC "How stats grow"). */
object Mastery {

    /** Mastery needed to raise a stat from [statValue] to [statValue] + 1: 50 + 10 × value. */
    fun masteryToNext(statValue: Int): Int {
        require(statValue >= 0) { "statValue must be >= 0, was $statValue" }
        return 50 + 10 * statValue
    }
}
