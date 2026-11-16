package com.tirthankar.ascent.engine

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * Grind-guard numbers (content/guard.json), so the threshold can be retuned without a
 * code change (PHASE5.md section 2, D4, D5).
 */
@Serializable
data class GuardContent(
    val v: Int = 1,
    /** Slots per game day that pay full XP. */
    val freeSlots: Int = 3,
    /** Each slot past the free ones pays this fraction of the one before. */
    val decay: Double = 0.5,
    /** The lowest multiplier. */
    val floor: Double = 0.1,
    /** Slots a quest uses, by tier: raising the tier doesn't bypass the guard (D5). */
    val slots: Map<Tier, Int> = DEFAULT_SLOTS,
) {
    companion object {
        val DEFAULT_SLOTS: Map<Tier, Int> = mapOf(
            Tier.TRIVIAL to 1,
            Tier.EASY to 1,
            Tier.NORMAL to 1,
            Tier.HARD to 2,
            Tier.EXTREME to 3,
        )
    }
}

/** Where a quest came from, as far as the guard cares. Only [SELF_ADDED] uses slots. */
enum class QuestOrigin {
    /** Your own quests, bounties and Clarity's extras. */
    SELF_ADDED,
    TEMPLATE,
    CLAUDE,
    PENALTY,
    EMERGENCY,
    HIDDEN,
    ARC_OBJECTIVE,
    GATE_OBJECTIVE,
    BOSS_TASK,
}

/** What one completion pays under the guard. Slots are numbered from 1; 0 means unguarded. */
data class GuardPay(
    val firstSlot: Int,
    val lastSlot: Int,
    val multiplier: Double,
    /** Past the free slots a self-added side quest gets no loot roll. */
    val lootAllowed: Boolean,
) {
    val guarded: Boolean get() = lastSlot > 0

    companion object {
        val UNGUARDED = GuardPay(firstSlot = 0, lastSlot = 0, multiplier = 1.0, lootAllowed = true)
    }
}

/**
 * Diminishing XP after the 3rd self-added quest in a game day (PHASE5.md section 2):
 *
 *     m(n) = 1                          for n ≤ 3
 *     m(n) = max(0.5^(n − 3), 0.1)      for n > 3
 *
 * A quest pays the multiplier of its **last** slot. Slots are counted from the quests
 * completed so far today, so undoing one frees its slots without repaying anyone, and
 * completing and undoing in any order can never gain XP.
 */
object GrindGuard {

    fun counts(origin: QuestOrigin): Boolean = origin == QuestOrigin.SELF_ADDED

    fun slots(tier: Tier, content: GuardContent = GuardContent()): Int =
        (content.slots[tier] ?: GuardContent.DEFAULT_SLOTS.getValue(tier)).coerceAtLeast(1)

    /** m(n) for the n-th slot of the day (n ≥ 1). */
    fun multiplier(n: Int, content: GuardContent = GuardContent()): Double {
        require(n >= 1) { "slots are numbered from 1, was $n" }
        val past = n - content.freeSlots
        return if (past <= 0) 1.0 else max(content.decay.pow(past), content.floor)
    }

    /** What a quest pays when [usedSlots] are already taken today. */
    fun pay(usedSlots: Int, tier: Tier, origin: QuestOrigin, content: GuardContent = GuardContent()): GuardPay {
        if (!counts(origin)) return GuardPay.UNGUARDED
        val first = usedSlots.coerceAtLeast(0) + 1
        val last = usedSlots.coerceAtLeast(0) + slots(tier, content)
        return GuardPay(first, last, multiplier(last, content), lootAllowed = last <= content.freeSlots)
    }

    /** Mastery points after the guard, rounded half-up like XP. */
    fun scaleMastery(points: Int, multiplier: Double): Int =
        floor(points * multiplier.coerceIn(0.0, 1.0) + 0.5).toInt()

    /** How many slots are taken from what is still completed today. */
    fun usedSlots(completedTiers: List<Tier>, content: GuardContent = GuardContent()): Int =
        completedTiers.sumOf { slots(it, content) }

    /** The multiplier the next Normal quest would get, for "next pays NN%". */
    fun nextMultiplier(usedSlots: Int, content: GuardContent = GuardContent()): Double =
        multiplier(usedSlots.coerceAtLeast(0) + 1, content)
}

/**
 * One game day's completed self-added quests (id → slots used). Immutable: [complete]
 * and [undo] return the new day. A new game day starts empty.
 */
data class GuardDay(
    val day: Long,
    val completed: Map<Long, Int> = emptyMap(),
) {
    val usedSlots: Int get() = completed.values.sum()

    /** This day, or an empty one if [today] is a new game day. */
    fun on(today: Long): GuardDay = if (today == day) this else GuardDay(today)

    fun complete(id: Long, tier: Tier, origin: QuestOrigin, content: GuardContent = GuardContent()): Pair<GuardDay, GuardPay> {
        if (id in completed) return this to GuardPay.UNGUARDED
        val pay = GrindGuard.pay(usedSlots, tier, origin, content)
        if (!pay.guarded) return this to pay
        return copy(completed = completed + (id to GrindGuard.slots(tier, content))) to pay
    }

    fun undo(id: Long): GuardDay = copy(completed = completed - id)
}
