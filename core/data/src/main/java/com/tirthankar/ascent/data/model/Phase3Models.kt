package com.tirthankar.ascent.data.model

import com.tirthankar.ascent.data.db.ArcStatus
import com.tirthankar.ascent.data.db.BossStatus
import com.tirthankar.ascent.data.db.ChapterStatus
import com.tirthankar.ascent.data.db.GateSource
import com.tirthankar.ascent.data.db.GateStatus
import com.tirthankar.ascent.engine.Affix
import com.tirthankar.ascent.engine.EquipSlot
import com.tirthankar.ascent.engine.FinaleKind
import com.tirthankar.ascent.engine.GearRules
import com.tirthankar.ascent.engine.ItemSlot
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Rarity
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.UniqueEffect

/** An item you own. [affixes] hold base values; [affixValue] applies the upgrade level. */
data class OwnedItem(
    val id: Long,
    val templateId: String?,
    val name: String,
    val slot: ItemSlot,
    val rarity: Rarity,
    val affixes: List<Affix>,
    val unique: UniqueEffect?,
    val setId: String?,
    val setName: String?,
    val upgradeLevel: Int,
    val equippedSlot: EquipSlot?,
    val source: String,
    /** Essence from dismantling it, or null if it can't be (Mythic). */
    val essenceValue: Int?,
    /** Essence for the next upgrade, or null at +5. */
    val upgradeCost: Int?,
    val upgradeStep: Double,
) {
    val equipped: Boolean get() = equippedSlot != null

    fun affixValue(affix: Affix): Double = GearRules.upgradedValue(affix.value, upgradeLevel, upgradeStep)
}

data class ChapterObjective(
    val id: Long,
    val title: String,
    val target: Double,
    val unitLabel: String,
    /** Logged by hand plus linked weekly goals. */
    val progress: Double,
) {
    val done: Boolean get() = progress >= target - 1e-9
}

data class Chapter(
    val id: Long,
    val position: Int,
    val title: String,
    val status: ChapterStatus,
    val objectives: List<ChapterObjective>,
    val xpAwarded: Long,
)

data class Arc(
    val id: Long,
    val publicId: String,
    val realm: Realm,
    val title: String,
    val status: ArcStatus,
    val templateId: String?,
    val finale: FinaleKind,
    val finaleName: String?,
    val chapters: List<Chapter>,
) {
    val activeChapter: Chapter? get() = chapters.firstOrNull { it.status == ChapterStatus.ACTIVE }
    val chaptersDone: Int get() = chapters.count { it.status == ChapterStatus.DONE }
}

data class GateObjective(
    val id: Long,
    val title: String,
    val target: Double,
    val unitLabel: String,
    val isCount: Boolean,
    /** Matching completed quests plus what was logged by hand. */
    val progress: Double,
    val logged: Double,
    val auto: Boolean,
)

data class Gate(
    val id: Long,
    val publicId: String,
    val title: String,
    val lore: String,
    val realm: Realm,
    val rank: Rank,
    val days: Int,
    val isRed: Boolean,
    val status: GateStatus,
    val source: GateSource,
    val opensDay: Long?,
    val closesDay: Long?,
    val objectives: List<GateObjective>,
    val fraction: Double,
    /** XP if it closed now (before gear), for the card. */
    val xpPreview: Long,
    /** Phase 4: a rank trial (the rank it earns is [rank]) and its attempt number. */
    val isRankTrial: Boolean = false,
    val attempt: Int = 0,
) {
    val objectivesDone: Int get() = objectives.count { it.progress >= it.target - 1e-9 }
}

data class BossTask(
    val id: Long,
    val publicId: String,
    val phase: Int,
    val title: String,
    val hours: Double,
    val damage: Int,
    val done: Boolean,
    val unlocked: Boolean,
    val critical: Boolean,
    val canUndo: Boolean,
)

data class Boss(
    val id: Long,
    val publicId: String,
    val title: String,
    val arcId: Long?,
    val deadlineDay: Long?,
    val isTrial: Boolean,
    val isRaid: Boolean,
    val trialTemplate: String?,
    val recordToBeat: Double?,
    val lastAttemptDay: Long?,
    val lastResult: Double?,
    val status: BossStatus,
    val phases: List<String>,
    val tasks: List<BossTask>,
    val maxHp: Int,
    val hp: Int,
    val currentPhase: Int,
    val enraged: Boolean,
    val paperSubmitted: Boolean,
    /** For a Trial: true when an attempt is allowed today. */
    val trialOpen: Boolean,
)

data class AchievementProgress(
    val id: String,
    val name: String,
    val description: String,
    val hidden: Boolean,
    /** Tiers held (0 = none) of [tiers]. */
    val tier: Int,
    val tiers: Int,
    val progress: Double,
    val nextThreshold: Double?,
)

data class LoreLine(
    val id: String,
    val title: String,
    val text: String,
    val context: String?,
    /** Phase 4: the Record filter, and unread until opened. Claude's lines have their target's category. */
    val category: com.tirthankar.ascent.engine.LoreCategory? = null,
    val unread: Boolean = false,
)

/** "n of m recorded": seed lines recorded out of all seed lines. Hidden ones count, never described. */
data class RecordProgress(val recorded: Int, val total: Int)

/** What the layered avatar draws (PHASE4.md section 5), from the player, content and gear. */
data class AvatarLook(
    val rank: Rank,
    val aura: com.tirthankar.ascent.engine.AuraToken,
    val particles: Int,
    val rings: Int,
    val cloak: String?,
    val sigil: String?,
    /** 0 base, 1 evolved at B, 2 final at S: evolutions brighten the class layer. */
    val stage: Int,
    /** Equipped slot name (WEAPON, HEAD, …) → rarity order (0 Common … 4 Mythic). */
    val gear: Map<String, Int>,
    val name: String,
    val title: String,
    /** The class name at this rank (base, evolved or final), if a class is chosen. */
    val className: String?,
)
