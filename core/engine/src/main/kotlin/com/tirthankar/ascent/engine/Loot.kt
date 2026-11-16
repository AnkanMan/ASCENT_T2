package com.tirthankar.ascent.engine

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

enum class Rarity { COMMON, RARE, EPIC, LEGENDARY, MYTHIC }

/** Item types. An item of [ACCESSORY] fits either accessory slot. */
enum class ItemSlot { WEAPON, HEAD, BODY, HANDS, FEET, ACCESSORY }

/** The seven places gear is worn (SPEC "Equipment"). */
enum class EquipSlot(val itemSlot: ItemSlot) {
    WEAPON(ItemSlot.WEAPON),
    HEAD(ItemSlot.HEAD),
    BODY(ItemSlot.BODY),
    HANDS(ItemSlot.HANDS),
    FEET(ItemSlot.FEET),
    ACCESSORY_1(ItemSlot.ACCESSORY),
    ACCESSORY_2(ItemSlot.ACCESSORY),
    ;

    companion object {
        fun forItem(slot: ItemSlot): List<EquipSlot> = entries.filter { it.itemSlot == slot }
    }
}

/**
 * Affix types (SPEC "Affixes"). [phase4] types need systems that arrive in Phase 4
 * (Insight charges, cooldowns): they never roll before then (D7).
 */
enum class AffixType(val phase4: Boolean = false) {
    XP_REALM,
    MASTERY_STAT,
    CRIT,
    STREAK_CAP,
    HP_REGEN,
    GATE_REWARD,
    CHEST_LUCK,
    INSIGHT(phase4 = true),
    COOLDOWN(phase4 = true),
}

/** One affix. [value] is a fraction: 0.05 = +5% (or +5 points of crit, +0.05 streak cap). */
@Serializable
data class Affix(
    val type: AffixType,
    val value: Double,
    val realm: Realm? = null,
    val stat: Stat? = null,
)

/** Where a loot roll comes from; each has a row in the drop table. */
enum class DropSource { SIDE_QUEST, EMERGENCY, CHEST_ED, CHEST_CB, CHEST_AS, BOSS, RAID }

@Serializable
data class DropTable(
    /** Chance the roll happens at all (side quests 10%, emergency 20%, chests 100%). */
    val rollChance: Double = 1.0,
    /** Percent by rarity; sums to 100. */
    val weights: Map<Rarity, Int>,
)

@Serializable
data class RarityRule(
    val minAffixes: Int,
    val maxAffixes: Int,
    val maxPerAffix: Double,
    /** Essence from dismantling; null means it can't be dismantled (Mythic). */
    val essence: Int? = null,
)

@Serializable
data class GearCaps(
    /** Total gear XP bonus per realm, as a fraction. */
    val xpPerRealm: Double = 0.30,
    /** Total crit chance (base + gear). */
    val critTotal: Double = 0.25,
)

/** Loot rules shipped as JSON (content/loot.json). */
@Serializable
data class LootContent(
    val v: Int = 1,
    val tables: Map<DropSource, DropTable>,
    val rarities: Map<Rarity, RarityRule>,
    /** Affix types that may roll now. */
    val rollingAffixes: List<AffixType>,
    val prefixes: List<String>,
    val bases: Map<ItemSlot, List<String>>,
    /** Essence cost of +1 … +5. */
    val upgradeCosts: List<Int>,
    /** Each upgrade level adds this share of the base affix values. */
    val upgradeStep: Double = 0.2,
    /** Chance a Rare or Epic drop is a hand-written item of that rarity, when one exists. */
    val namedChance: Double = 0.25,
    val caps: GearCaps = GearCaps(),
)

/**
 * A hand-written item's special power. [effects] always apply; [dormant] marks a
 * part of [text] that needs a Phase 4 system and does nothing yet (D7).
 */
@Serializable
data class UniqueEffect(
    val text: String,
    val effects: List<Effect> = emptyList(),
    val dormant: Boolean = false,
)

@Serializable
data class ItemTemplate(
    val id: String,
    val name: String,
    val slot: ItemSlot,
    val rarity: Rarity,
    val affixes: List<Affix>,
    val unique: UniqueEffect? = null,
    val setId: String? = null,
    /** False for items only given by achievements, never by a random roll. */
    val drops: Boolean = true,
)

@Serializable
data class ItemSet(
    val id: String,
    val name: String,
    val realm: Realm,
    val pieces: Int = 3,
    val bonus: UniqueEffect,
)

/** Hand-written items and sets (content/items.json). */
@Serializable
data class ItemsContent(
    val v: Int = 1,
    val items: List<ItemTemplate>,
    val sets: List<ItemSet>,
)

/** An item as rolled: either a hand-written template or a generated one. */
data class RolledItem(
    val templateId: String?,
    val name: String,
    val slot: ItemSlot,
    val rarity: Rarity,
    val affixes: List<Affix>,
    val unique: UniqueEffect? = null,
    val setId: String? = null,
    val seed: Long,
)

/** Seeded loot (SPEC "Drop tables"): the same seed always gives the same item. */
object LootRoller {

    /** The rarity for [table], or null when the roll itself misses ([DropTable.rollChance]). */
    fun rollRarity(table: DropTable, rng: SplitMix64, chestLuck: Double = 0.0): Rarity? {
        if (rng.nextDouble() >= table.rollChance) return null
        val total = table.weights.values.sum()
        var r = rng.nextInt(total)
        var rarity = Rarity.COMMON
        for (candidate in Rarity.entries) {
            val w = table.weights[candidate] ?: 0
            if (r < w) {
                rarity = candidate
                break
            }
            r -= w
        }
        // Chest luck shifts the roll one row rarer, never into Mythic (raids only).
        val luckRoll = rng.nextDouble()
        if (chestLuck > 0 && luckRoll < chestLuck && rarity < Rarity.LEGENDARY) {
            rarity = Rarity.entries[rarity.ordinal + 1]
        }
        return rarity
    }

    /** One drop from [source], or null if the roll missed or nothing exists at that rarity. */
    fun roll(
        source: DropSource,
        seed: Long,
        loot: LootContent,
        items: ItemsContent,
        chestLuck: Double = 0.0,
    ): RolledItem? {
        val table = loot.tables[source] ?: return null
        val rng = SplitMix64(seed)
        val rarity = rollRarity(table, rng, chestLuck) ?: return null
        return item(rarity, rng, seed, loot, items)
    }

    /** An item of [rarity]: hand-written for Legendary and Mythic, often generated below. */
    fun item(rarity: Rarity, rng: SplitMix64, seed: Long, loot: LootContent, items: ItemsContent): RolledItem? {
        val named = items.items.filter { it.rarity == rarity && it.drops }
        val useNamed = when (rarity) {
            Rarity.LEGENDARY, Rarity.MYTHIC -> true
            Rarity.COMMON -> false
            else -> named.isNotEmpty() && rng.nextDouble() < loot.namedChance
        }
        if (useNamed) {
            if (named.isEmpty()) return null
            return fromTemplate(rng.pick(named), seed)
        }
        return generate(rarity, rng, seed, loot)
    }

    fun fromTemplate(t: ItemTemplate, seed: Long) =
        RolledItem(t.id, t.name, t.slot, t.rarity, t.affixes, t.unique, t.setId, seed)

    private fun generate(rarity: Rarity, rng: SplitMix64, seed: Long, loot: LootContent): RolledItem? {
        val rule = loot.rarities[rarity] ?: return null
        val slot = rng.pick(ItemSlot.entries)
        val count = rule.minAffixes + rng.nextInt(rule.maxAffixes - rule.minAffixes + 1)
        val types = loot.rollingAffixes.filterNot { it.phase4 }.toMutableList()
        val affixes = buildList {
            repeat(count) {
                if (types.isEmpty()) return@repeat
                val type = types.removeAt(rng.nextInt(types.size))
                val value = roundAffix(rng.nextDouble(rule.maxPerAffix * 0.25, rule.maxPerAffix))
                add(
                    Affix(
                        type = type,
                        value = value,
                        realm = if (type == AffixType.XP_REALM || type == AffixType.GATE_REWARD) rng.pick(Realm.entries) else null,
                        stat = if (type == AffixType.MASTERY_STAT) rng.pick(Stat.entries) else null,
                    ),
                )
            }
        }
        val base = rng.pick(loot.bases[slot] ?: listOf(slot.name.lowercase()))
        val name = "${rng.pick(loot.prefixes)} $base"
        return RolledItem(null, name, slot, rarity, affixes, seed = seed)
    }

    /** Affix values snap to half a percent, at least 0.5%. */
    internal fun roundAffix(value: Double): Double = max(0.005, floor(value / 0.005 + 0.5) * 0.005)
}

/** An owned item, as the rules need it. */
data class GearPiece(
    val affixes: List<Affix>,
    val upgradeLevel: Int,
    val unique: UniqueEffect? = null,
    val setId: String? = null,
)

/** What equipped gear adds up to. Phase 4 parts are listed in [dormant], not folded. */
data class GearBonus(
    val effects: List<Effect>,
    val dormant: List<String>,
) {
    val hpRegen: Double get() = effects.filterIsInstance<Effect.HpRegen>().sumOf { it.value }
    val chestLuck: Double get() = effects.filterIsInstance<Effect.ChestLuck>().sumOf { it.value }.coerceAtMost(1.0)

    fun gateRewardMultiplier(realm: Realm): Double =
        1.0 + effects.filterIsInstance<Effect.GateReward>()
            .filter { it.realm == null || it.realm == realm }
            .sumOf { it.value - 1.0 }
}

/** Upgrades, Essence and folding gear into effects (SPEC "Upgrades", "Sets"). */
object GearRules {

    const val MAX_UPGRADE = 5

    /** An affix's value at [upgradeLevel]: each level adds [step] of the base value. */
    fun upgradedValue(base: Double, upgradeLevel: Int, step: Double = 0.2): Double =
        base * (1.0 + step * upgradeLevel.coerceIn(0, MAX_UPGRADE))

    /** Essence to go from [currentLevel] to the next, or null at +5. */
    fun upgradeCost(currentLevel: Int, costs: List<Int>): Int? =
        if (currentLevel in 0 until MAX_UPGRADE) costs.getOrNull(currentLevel) else null

    /** Essence from dismantling, or null if [rarity] can't be dismantled. */
    fun essenceFor(rarity: Rarity, rules: Map<Rarity, RarityRule>): Int? = rules[rarity]?.essence

    /** Affix → effect. Phase 4 types return null (dormant). */
    fun effectOf(affix: Affix, value: Double): Effect? = when (affix.type) {
        AffixType.XP_REALM -> Effect.XpMult(realm = affix.realm, value = 1.0 + value)
        AffixType.MASTERY_STAT -> affix.stat?.let { Effect.MasteryMult(it, 1.0 + value) }
        AffixType.CRIT -> Effect.CritBonus(value.coerceIn(0.0, 1.0))
        AffixType.STREAK_CAP -> Effect.StreakCap(QuestXp.DEFAULT_STREAK_CAP + value)
        AffixType.HP_REGEN -> Effect.HpRegen(value.coerceIn(0.0, 1.0))
        AffixType.GATE_REWARD -> Effect.GateReward(1.0 + value, affix.realm)
        AffixType.CHEST_LUCK -> Effect.ChestLuck(value.coerceIn(0.0, 1.0))
        AffixType.INSIGHT, AffixType.COOLDOWN -> null
    }

    /**
     * Everything [equipped] gear adds: affixes at their upgraded values, unique effects,
     * and set bonuses for sets with enough pieces worn. Caps are applied at reward time
     * ([RewardCalculator]), so this lists raw effects.
     */
    fun fold(equipped: List<GearPiece>, sets: List<ItemSet>, step: Double = 0.2): GearBonus {
        val effects = mutableListOf<Effect>()
        val dormant = mutableListOf<String>()
        equipped.forEach { piece ->
            piece.affixes.forEach { affix ->
                val effect = effectOf(affix, upgradedValue(affix.value, piece.upgradeLevel, step))
                if (effect != null) effects += effect else dormant += affix.type.name
            }
            piece.unique?.let { unique ->
                // [dormant] marks the text's Phase 4 part; listed effects always apply.
                if (unique.dormant) dormant += unique.text
                effects += unique.effects
            }
        }
        val worn = equipped.mapNotNull { it.setId }.groupingBy { it }.eachCount()
        sets.forEach { set ->
            if ((worn[set.id] ?: 0) >= set.pieces) {
                if (set.bonus.dormant) dormant += set.bonus.text
                effects += set.bonus.effects
            }
        }
        return GearBonus(effects, dormant)
    }

    /** Whole-number percent for display, e.g. 0.075 → 7.5. */
    fun percent(value: Double): Double = (value * 1000).roundToLong() / 10.0
}
