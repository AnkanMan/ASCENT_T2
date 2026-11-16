package com.tirthankar.ascent.engine

import kotlin.math.floor

/** A stat after mastery was added: its new value, leftover mastery and how many points it rose. */
data class StatGrowth(val value: Int, val mastery: Int, val statGain: Int)

/**
 * Organic growth (SPEC "How stats grow", D4): a completed quest pays mastery to its stat;
 * the stat rises by 1 each time mastery reaches 50 + 10·value, overflow carried over.
 */
object MasteryGrowth {

    fun baseMastery(tier: Tier): Int = when (tier) {
        Tier.TRIVIAL -> 2
        Tier.EASY -> 5
        Tier.NORMAL -> 10
        Tier.HARD -> 20
        Tier.EXTREME -> 40
    }

    /** Base mastery × mastery_mult effects for [stat] (and [tags]), rounded half-up. */
    fun masteryFor(tier: Tier, stat: Stat, effects: List<Effect> = emptyList(), tags: Set<String> = emptySet()): Int =
        floor(baseMastery(tier) * RewardCalculator.masteryMultiplier(stat, effects, tags) + 0.5).toInt()

    fun apply(value: Int, mastery: Int, gained: Int): StatGrowth {
        require(value >= 0) { "value must be >= 0, was $value" }
        require(mastery >= 0) { "mastery must be >= 0, was $mastery" }
        require(gained >= 0) { "gained must be >= 0, was $gained" }
        var v = value
        var m = mastery + gained
        while (m >= Mastery.masteryToNext(v)) {
            m -= Mastery.masteryToNext(v)
            v++
        }
        return StatGrowth(value = v, mastery = m, statGain = v - value)
    }

    /**
     * The exact inverse of [apply], used when a completion is undone: takes [amount]
     * mastery back, lowering the stat through its thresholds if needed. Never below 0.
     * [StatGrowth.statGain] is then zero or negative.
     */
    fun remove(value: Int, mastery: Int, amount: Int): StatGrowth {
        require(value >= 0) { "value must be >= 0, was $value" }
        require(mastery >= 0) { "mastery must be >= 0, was $mastery" }
        require(amount >= 0) { "amount must be >= 0, was $amount" }
        var v = value
        var m = mastery - amount
        while (m < 0 && v > 0) {
            v--
            m += Mastery.masteryToNext(v)
        }
        return StatGrowth(value = v, mastery = m.coerceAtLeast(0), statGain = v - value)
    }
}
