package com.tirthankar.ascent.engine

/** The six attribute values of a player. */
data class StatBlock(
    val str: Int,
    val agi: Int,
    val int: Int,
    val foc: Int,
    val wis: Int,
    val vit: Int,
) {
    init {
        Stat.entries.forEach { require(get(it) >= 0) { "$it must be >= 0, was ${get(it)}" } }
    }

    operator fun get(stat: Stat): Int = when (stat) {
        Stat.STR -> str
        Stat.AGI -> agi
        Stat.INT -> int
        Stat.FOC -> foc
        Stat.WIS -> wis
        Stat.VIT -> vit
    }

    companion object {
        /** Every stat starts at 10 before the Awakening measurement shifts it (SPEC). */
        const val STARTING_VALUE = 10

        fun uniform(value: Int) = StatBlock(value, value, value, value, value, value)
    }
}
