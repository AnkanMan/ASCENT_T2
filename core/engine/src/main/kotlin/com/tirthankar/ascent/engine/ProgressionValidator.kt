package com.tirthankar.ascent.engine

import com.tirthankar.ascent.engine.ContentValidator.text
import com.tirthankar.ascent.engine.ContentValidator.uniqueIds

/**
 * Checks the Phase 4 content (skills, classes, rank trials, avatar) and that every
 * Phase 4 lore trigger has a line. Runs as part of [ContentValidator.validate].
 */
internal object ProgressionValidator {

    private val REALM_TREES = listOf(SkillTree.BODY, SkillTree.MIND, SkillTree.SELF)
    private val TRIAL_RANKS = listOf(Rank.D, Rank.C, Rank.B, Rank.A, Rank.S)

    fun validate(c: ContentBundle): List<String> = buildList {
        addAll(skills(c))
        addAll(classes(c))
        addAll(rankTrials(c))
        addAll(avatar(c))
        addAll(loreTriggers(c))
    }

    private fun skills(c: ContentBundle): List<String> = buildList {
        val s = c.skills
        val all = s.skills + c.classes.classes.map { it.unique }
        uniqueIds("skills", all.map { it.id })
        listOf(2, 3, 4).forEach { if (it !in s.tierGates) add("skills: no gate for tier $it") }
        REALM_TREES.forEach { tree ->
            val stats = s.treeStats[tree]
            if (stats == null || stats.size != 2 || stats.any { it.realm.name != tree.name }) add("skills: $tree needs its realm's two stats")
            val inTree = s.skills.filter { it.tree == tree }
            val shape = inTree.groupingBy { it.tier }.eachCount()
            if (shape != mapOf(1 to 2, 2 to 2, 3 to 2, 4 to 1)) add("skills: $tree must have 2 skills in tiers 1–3 and one ultimate, has $shape")
        }
        if (s.respecEssence <= 0) add("skills: respec cost must be > 0")
        all.forEach { k ->
            val where = "skill ${k.id}"
            text(where, k.name)
            text(where, k.description)
            k.prerequisites.forEach { p ->
                val pre = s.skill(p)
                if (pre == null) add("$where: unknown prerequisite $p")
                else if (pre.tree != k.tree) add("$where: prerequisite $p is in another tree")
            }
            if (k.tree in REALM_TREES) {
                when (k.tier) {
                    1 -> if (k.prerequisites.isNotEmpty()) add("$where: tier 1 has no prerequisite")
                    2, 3 -> if (k.prerequisites.size != 1 || s.skill(k.prerequisites.single())?.tier != k.tier - 1) {
                        add("$where: needs one prerequisite from tier ${k.tier - 1}")
                    }
                    4 -> if (k.prerequisites.size != 2 || k.prerequisites.any { s.skill(it)?.tier != 3 }) add("$where: the ultimate needs both tier 3 skills")
                    else -> add("$where: tier ${k.tier} is not 1–4")
                }
            }
            if (k.locked) {
                if (k.effects.isNotEmpty() || k.handler != null) add("$where: locked skills have no effects in Phase 4")
                return@forEach
            }
            when (k.kind) {
                SkillKind.PASSIVE -> {
                    if (k.effects.isEmpty()) add("$where: a passive needs effects")
                    if (k.handler != null || k.mp != 0) add("$where: a passive has no handler or MP")
                }
                SkillKind.ACTIVE -> {
                    if (k.handler !in PowerHandlers.ALL) add("$where: unknown handler ${k.handler}")
                    val limit = k.limit
                    if (limit == null) add("$where: an active needs a limit")
                    else if (limit.period == LimitPeriod.HOURS && (limit.hours ?: 0) <= 0) add("$where: an hour cooldown needs hours")
                    if (k.mp < 0) add("$where: MP can't be negative")
                }
            }
        }
        // The Observer tree exists, locked, until A-rank (A15).
        val observer = s.skills.filter { it.tree == SkillTree.OBSERVER }
        if (observer.isEmpty() || observer.any { !it.locked }) add("skills: the Observer tree must exist and be locked")
    }

    private fun classes(c: ContentBundle): List<String> = buildList {
        val k = c.classes
        uniqueIds("classes", k.classes.map { it.id })
        if (k.classes.size != 6) add("classes: expected 6, found ${k.classes.size}")
        if (k.polymathRatio <= 1.0) add("classes: polymath ratio must be > 1")
        if (k.awakenLevel != Rank.D.minLevel) add("classes: awaken level ${k.awakenLevel} should match D-rank's ${Rank.D.minLevel}")
        if (k.classes.count { it.polymath } != 1) add("classes: exactly one Polymath")
        k.classes.forEach { cls ->
            val where = "class ${cls.id}"
            text(where, cls.name)
            cls.evolutions.forEach { text(where, it) }
            if (cls.evolutions.size != 2) add("$where: needs B and S evolutions")
            if (cls.unique.tree != SkillTree.CLASS) add("$where: unique skill must be a CLASS skill")
            if (cls.polymath) {
                if (cls.stats.isNotEmpty()) add("$where: Polymath has no stat pair")
            } else {
                if (cls.stats.size != 2 || cls.stats.toSet().size != 2) add("$where: needs two different stats")
                cls.stats.forEach { stat ->
                    // A13: +10% XP and +10% mastery on each class stat.
                    if (cls.bonus.none { it is Effect.XpMult && it.stat == stat && it.realm == null && it.tag == null }) add("$where: no XP bonus for $stat")
                    if (cls.bonus.none { it is Effect.MasteryMult && it.stat == stat }) add("$where: no mastery bonus for $stat")
                }
            }
        }
    }

    private fun rankTrials(c: ContentBundle): List<String> = buildList {
        val r = c.rankTrials
        if (r.days !in 3..7) add("rank trials: ${r.days} days")
        if (r.objectivesPerRealm < 1) add("rank trials: at least one objective per realm")
        if (r.retryDays <= 0) add("rank trials: retry days must be > 0")
        TRIAL_RANKS.forEach { rank ->
            val t = r.forRank(rank)
            if (t == null) {
                add("rank trials: no trial for $rank")
                return@forEach
            }
            if (t.level != rank.minLevel) add("rank trials: $rank level ${t.level}, SPEC says ${rank.minLevel}")
            if (kotlin.math.abs(t.effort - GateRules.extra(rank)) > 1e-9) add("rank trials: $rank effort should be ${GateRules.extra(rank)}")
            if (t.chest != GateRules.chest(rank)) add("rank trials: $rank chest should be ${GateRules.chest(rank)}")
        }
        Realm.entries.forEach { realm ->
            val pool = r.objectives[realm].orEmpty()
            if (pool.size < r.objectivesPerRealm) add("rank trials: $realm needs at least ${r.objectivesPerRealm} objectives")
            pool.forEach { o ->
                val where = "rank trial objective \"${o.title}\""
                text(where, o.title)
                if (o.templates.isEmpty()) add("$where: needs templates")
                o.templates.forEach { key ->
                    val t = TemplateLibrary[key]
                    if (t == null) add("$where: unknown template $key") else if (t.realm != realm) add("$where: $key is not $realm")
                }
                if (o.sessions == null && (o.perWeek ?: 0.0) <= 0) add("$where: needs sessions or perWeek")
            }
        }
    }

    private fun avatar(c: ContentBundle): List<String> = buildList {
        val a = c.avatar
        Rank.entries.forEach { if (it !in a.auras) add("avatar: no aura for $it") }
        c.classes.classes.forEach { cls ->
            val layer = a.classLayers[cls.id]
            if (layer == null) {
                add("avatar: no layer for class ${cls.id}")
            } else {
                if (layer.cloak !in a.cloaks) add("avatar: unknown cloak ${layer.cloak}")
                if (layer.sigil !in a.sigils) add("avatar: unknown sigil ${layer.sigil}")
            }
        }
        if (a.particleBase <= 0 || a.particlePerLevels <= 0 || a.particleMax < a.particleBase) add("avatar: bad particle rule")
        if (a.ringPerLevels <= 0 || a.ringMax <= 0) add("avatar: bad ring rule")
    }

    /** Every Phase 4 trigger has a line, so no unlock ever finds nothing. */
    private fun loreTriggers(c: ContentBundle): List<String> = buildList {
        val entries = c.lore.entries
        fun has(kind: String, match: (LoreUnlock) -> Boolean = { true }) = entries.any { it.unlock.kind == kind && match(it.unlock) }
        listOf(
            LoreKinds.RANK_TRIAL_START, LoreKinds.RANK_TRIAL_FAIL, LoreKinds.SIGMA_HINT, LoreKinds.SKILL_FIRST,
            LoreKinds.POWER_FIRST_CAST, LoreKinds.OBSERVER_GLIMPSE, LoreKinds.RESPEC,
        ).forEach { if (!has(it)) add("lore: no line for $it") }
        TRIAL_RANKS.forEach { rank -> if (!has(LoreKinds.RANK) { it.rank == rank }) add("lore: no line for reaching $rank") }
        c.classes.classes.forEach { cls ->
            if (!has(LoreKinds.CLASS) { it.classId == cls.id }) add("lore: no line for class ${cls.id}")
            listOf(1, 2).forEach { stage ->
                if (!has(LoreKinds.CLASS_EVOLUTION) { it.classId == cls.id && it.stage == stage }) add("lore: no evolution $stage line for ${cls.id}")
            }
        }
        c.skills.skills.filter { it.tier == 4 && !it.locked }.forEach { ult ->
            if (!has(LoreKinds.SKILL_UNLOCK) { it.skill == ult.id }) add("lore: no line for unlocking ${ult.id}")
        }
    }
}
