package com.tirthankar.ascent.engine

/**
 * SplitMix64 (Steele, Lea and Flood, 2014): a tiny, fully specified generator, so a
 * stored seed replays the same rolls after any library update (SPEC "Loot").
 */
class SplitMix64(seed: Long) {

    private var state = seed

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11) * DOUBLE_UNIT

    /** Uniform in [0, bound). */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be > 0, was $bound" }
        return ((nextLong() ushr 1) % bound).toInt()
    }

    /** Uniform in [from, to]. */
    fun nextDouble(from: Double, to: Double): Double = from + (to - from) * nextDouble()

    fun <T> pick(options: List<T>): T {
        require(options.isNotEmpty()) { "nothing to pick from" }
        return options[nextInt(options.size)]
    }

    private companion object {
        const val GOLDEN_GAMMA = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        const val MIX_1 = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        const val MIX_2 = -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        const val DOUBLE_UNIT = 1.0 / (1L shl 53)
    }
}

/**
 * Seeds for rolls. Every roll is tied to an event key such as "quest:42" or
 * "gate:7:chest:0", so repeating an action replays the same roll instead of re-rolling.
 */
object Seeds {

    /** A stable 64-bit id for an event key (FNV-1a). */
    fun eventId(key: String): Long {
        var hash = FNV_OFFSET
        for (c in key) {
            hash = hash xor c.code.toLong()
            hash *= FNV_PRIME
        }
        return hash
    }

    /** The seed for one event: the per-install seed mixed with the event's id. */
    fun forEvent(installSeed: Long, key: String): Long = installSeed xor eventId(key)

    fun rng(installSeed: Long, key: String): SplitMix64 = SplitMix64(forEvent(installSeed, key))

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xCBF29CE484222325
    private const val FNV_PRIME = 0x100000001b3L
}
