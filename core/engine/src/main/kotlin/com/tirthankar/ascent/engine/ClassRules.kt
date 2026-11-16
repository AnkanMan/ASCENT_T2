package com.tirthankar.ascent.engine

/** A class on offer with its score (the sum of its two stats). */
data class ClassOption(val classId: String, val score: Int)

/**
 * Classes (PHASE4.md section 3, A5, A13, A14). Awakened at level 10, independent of the
 * D-rank trial. Re-chosen at each rank-up, before the evolution at B and S.
 */
object ClassRules {

    fun scores(stats: StatBlock, content: ClassContent): List<ClassOption> =
        content.classes.filter { !it.polymath }.map { c -> ClassOption(c.id, c.stats.sumOf { stats[it] }) }
            .sortedWith(compareByDescending<ClassOption> { it.score }.thenBy { it.classId })

    /** Polymath: the highest stat is at most [ClassContent.polymathRatio] × the lowest. */
    fun polymathEligible(stats: StatBlock, content: ClassContent): Boolean {
        val values = Stat.entries.map { stats[it] }
        return values.max() <= content.polymathRatio * values.min() + 1e-9
    }

    /**
     * The classes offered: every class holding one of the two highest scores (ties are
     * shown side by side), plus Polymath when eligible.
     */
    fun choices(stats: StatBlock, content: ClassContent): List<ClassOption> {
        val all = scores(stats, content)
        val top = all.map { it.score }.distinct().take(2).toSet()
        val offered = all.filter { it.score in top }
        val polymath = content.classes.firstOrNull { it.polymath }
        return if (polymath != null && polymathEligible(stats, content)) offered + ClassOption(polymath.id, 0) else offered
    }

    /** Class Awakening is due at level 10 for a player without a class. */
    fun awakeningDue(level: Int, classId: String?, content: ClassContent): Boolean = classId == null && level >= content.awakenLevel

    /** 0 = base, 1 = evolved at B, 2 = final form at S. */
    fun stage(rank: Rank): Int = when (rank) {
        Rank.E, Rank.D, Rank.C -> 0
        Rank.B, Rank.A -> 1
        Rank.S, Rank.SIGMA -> 2
    }

    /** The class's name at [rank]: base, evolution or final form. */
    fun displayName(def: ClassDef, rank: Rank): String = when (val s = stage(rank)) {
        0 -> def.name
        else -> def.evolutions.getOrElse(s - 1) { def.name }
    }

    /** True when reaching [newRank] evolves the class (B and S). */
    fun evolvesAt(newRank: Rank): Boolean = newRank == Rank.B || newRank == Rank.S

    /** The class bonus (A13) plus the unique skill's passive effects (Resonance). */
    fun effects(def: ClassDef?): List<Effect> =
        def?.let { it.bonus + if (it.unique.kind == SkillKind.PASSIVE) it.unique.effects else emptyList() }.orEmpty()

    /** Re-choice is offered at every rank-up (A14); [choice] must be among [choices]. */
    fun validChoice(choice: String, stats: StatBlock, content: ClassContent): Boolean =
        choices(stats, content).any { it.classId == choice }
}
