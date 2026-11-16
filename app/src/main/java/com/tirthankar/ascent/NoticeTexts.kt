package com.tirthankar.ascent

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.tirthankar.ascent.data.NoticeKind
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.engine.Rarity

/** Headers and accents for System notices; the text comes from strings.xml. */
internal object NoticeTexts {

    @StringRes
    fun header(kind: NoticeKind): Int = when (kind) {
        NoticeKind.ACHIEVEMENT -> R.string.notice_achievement
        NoticeKind.HIDDEN_ACHIEVEMENT -> R.string.notice_hidden
        NoticeKind.TITLE_EARNED -> R.string.notice_title
        NoticeKind.LORE -> R.string.notice_lore
        NoticeKind.CHAPTER_COMPLETE -> R.string.notice_chapter
        NoticeKind.ARC_COMPLETE -> R.string.notice_arc
        NoticeKind.GATE_CLEARED -> R.string.notice_gate_cleared
        NoticeKind.GATE_PARTIAL -> R.string.notice_gate_partial
        NoticeKind.GATE_BROKEN -> R.string.notice_gate_broken
        NoticeKind.GATE_FAILED -> R.string.notice_gate_failed
        NoticeKind.BOSS_PHASE -> R.string.notice_boss_phase
        NoticeKind.BOSS_DEFEATED -> R.string.notice_boss_defeated
        NoticeKind.BOSS_STIRS -> R.string.notice_boss_stirs
        NoticeKind.BOSS_ENRAGED -> R.string.notice_boss_enraged
        NoticeKind.TRIAL_WON -> R.string.notice_trial_won
        NoticeKind.TRIAL_FAILED -> R.string.notice_trial_failed
        NoticeKind.PENALTY_ISSUED -> R.string.notice_penalty
        NoticeKind.EMERGENCY_ISSUED -> R.string.notice_emergency
        NoticeKind.SKILL_UNLOCKED -> R.string.notice_skill_unlocked
        NoticeKind.CLASS_AWAKENED -> R.string.notice_class_awakened
        NoticeKind.RANK_TRIAL_OPEN -> R.string.notice_rank_trial_open
        NoticeKind.RANK_UP -> R.string.notice_rank_up
        NoticeKind.RANK_TRIAL_FAILED -> R.string.notice_rank_trial_failed
        NoticeKind.STREAK_SHIELDED -> R.string.notice_streak_shielded
    }

    fun accent(kind: NoticeKind): Color = when (kind) {
        NoticeKind.PENALTY_ISSUED, NoticeKind.GATE_BROKEN, NoticeKind.GATE_FAILED,
        NoticeKind.BOSS_STIRS, NoticeKind.BOSS_ENRAGED, NoticeKind.TRIAL_FAILED, NoticeKind.RANK_TRIAL_FAILED -> AscentColors.PenaltyRed
        NoticeKind.HIDDEN_ACHIEVEMENT, NoticeKind.LORE, NoticeKind.CLASS_AWAKENED -> AscentColors.ArcaneViolet
        NoticeKind.EMERGENCY_ISSUED, NoticeKind.BOSS_DEFEATED, NoticeKind.TRIAL_WON, NoticeKind.ARC_COMPLETE,
        NoticeKind.RANK_UP, NoticeKind.RANK_TRIAL_OPEN -> AscentColors.LegendGold
        else -> AscentColors.SystemBlue
    }

    /** Gate notices carry their XP as detail. */
    fun isGate(kind: NoticeKind): Boolean =
        kind == NoticeKind.GATE_CLEARED || kind == NoticeKind.GATE_PARTIAL || kind == NoticeKind.GATE_BROKEN || kind == NoticeKind.GATE_FAILED

    /** Notices worth a phone notification when they happen at the reset. */
    fun notifies(kind: NoticeKind): Boolean =
        isGate(kind) || kind == NoticeKind.PENALTY_ISSUED || kind == NoticeKind.BOSS_STIRS || kind == NoticeKind.BOSS_ENRAGED ||
            kind == NoticeKind.RANK_TRIAL_OPEN || kind == NoticeKind.RANK_UP || kind == NoticeKind.RANK_TRIAL_FAILED ||
            kind == NoticeKind.STREAK_SHIELDED

    @StringRes
    fun rarity(rarity: Rarity): Int = when (rarity) {
        Rarity.COMMON -> R.string.rarity_common
        Rarity.RARE -> R.string.rarity_rare
        Rarity.EPIC -> R.string.rarity_epic
        Rarity.LEGENDARY -> R.string.rarity_legendary
        Rarity.MYTHIC -> R.string.rarity_mythic
    }
}
