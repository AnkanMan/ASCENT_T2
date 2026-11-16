package com.tirthankar.ascent.engine

import kotlinx.serialization.Serializable
import kotlin.math.floor

/** How an arc ends (SPEC "Main quests: arcs"). */
enum class FinaleKind { BOSS, TRIAL, TITLE, NONE }

@Serializable
data class ObjectiveTemplate(
    val title: String,
    val target: Double = 1.0,
    val unit: String = "done",
)

@Serializable
data class ChapterTemplate(
    val title: String,
    val objectives: List<ObjectiveTemplate> = emptyList(),
)

/** A ready-made arc (content/arcs.json), e.g. Iron Discipline. */
@Serializable
data class ArcTemplate(
    val id: String,
    val realm: Realm,
    val title: String,
    val chapters: List<ChapterTemplate>,
    val finale: FinaleKind,
    /** The boss name, or the title granted at the end. */
    val finaleName: String? = null,
    /** For a Trial finale: the template whose record is tested. */
    val trialTemplate: String? = null,
)

@Serializable
data class ArcContent(
    val v: Int = 1,
    val arcs: List<ArcTemplate>,
)

/** Arc and chapter rules. One active arc per realm; the rest wait in a backlog. */
object ArcRules {

    const val CHAPTER_BASE_XP = 1_000
    const val MIN_CHAPTERS = 3
    const val MAX_CHAPTERS = 6

    /** 1,000 × (1 + 0.05 L) XP per chapter. */
    fun chapterXp(level: Int): Long = floor(CHAPTER_BASE_XP * QuestXp.levelMultiplier(level) + 0.5).toLong()

    fun objectiveDone(progress: Double, target: Double): Boolean = progress >= target - 1e-9

    /**
     * A chapter completes when every objective is done. A chapter without objectives
     * never completes on its own; you complete it by hand (D13).
     */
    fun chapterComplete(objectives: List<Pair<Double, Double>>): Boolean =
        objectives.isNotEmpty() && objectives.all { (progress, target) -> objectiveDone(progress, target) }

    /** Fewer than 3 or more than 6 chapters gets a warning, but is allowed. */
    fun chapterCountWarning(chapters: Int): Boolean = chapters < MIN_CHAPTERS || chapters > MAX_CHAPTERS

    /** A new arc becomes active only when its realm has no active arc. */
    fun startsActive(activeArcsInRealm: Int): Boolean = activeArcsInRealm == 0
}
