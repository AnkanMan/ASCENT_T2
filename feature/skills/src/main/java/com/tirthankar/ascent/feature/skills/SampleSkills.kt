package com.tirthankar.ascent.feature.skills

import com.tirthankar.ascent.data.repo.RespecCost
import com.tirthankar.ascent.data.repo.SkillStatus
import com.tirthankar.ascent.engine.CastCheck
import com.tirthankar.ascent.engine.LimitPeriod
import com.tirthankar.ascent.engine.SkillDef
import com.tirthankar.ascent.engine.SkillKind
import com.tirthankar.ascent.engine.SkillLimit
import com.tirthankar.ascent.engine.SkillTree
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.UnlockCheck

/** Preview and test data shaped like content/skills.json (the app reads the real file). */
internal object SampleSkills {

    private fun passive(id: String, name: String, tree: SkillTree, tier: Int, vararg pre: String) =
        SkillDef(id, name, tree, tier, SkillKind.PASSIVE, "$name passive.", pre.toList())

    private fun active(id: String, name: String, tree: SkillTree, tier: Int, mp: Int, limit: SkillLimit, vararg pre: String) =
        SkillDef(id, name, tree, tier, SkillKind.ACTIVE, "$name active.", pre.toList(), mp, limit, handler = id)

    private val day = SkillLimit(LimitPeriod.DAY)
    private val week = SkillLimit(LimitPeriod.WEEK)
    private val month = SkillLimit(LimitPeriod.MONTH)

    val defs: List<SkillDef> = listOf(
        passive("iron_body", "Iron Body", SkillTree.BODY, 1),
        passive("momentum", "Momentum", SkillTree.BODY, 2, "iron_body"),
        active("limit_break", "Limit Break", SkillTree.BODY, 3, 40, week, "momentum"),
        passive("light_feet", "Light Feet", SkillTree.BODY, 1),
        active("second_wind", "Second Wind", SkillTree.BODY, 2, 20, day, "light_feet"),
        passive("recovery_aura", "Recovery Aura", SkillTree.BODY, 3, "second_wind"),
        active("unbroken", "Unbroken", SkillTree.BODY, 4, 50, month, "limit_break", "recovery_aura"),
        passive("deep_work", "Deep Work", SkillTree.MIND, 1),
        active("hyperfocus", "Hyperfocus", SkillTree.MIND, 2, 30, SkillLimit(LimitPeriod.HOURS, hours = 12), "deep_work"),
        passive("focus_chain", "Focus Chain", SkillTree.MIND, 3, "hyperfocus"),
        passive("clear_mind", "Clear Mind", SkillTree.MIND, 1),
        active("insight", "Insight", SkillTree.MIND, 2, 20, SkillLimit(LimitPeriod.DAY, wisPerCharge = 20), "clear_mind"),
        passive("literature_sense", "Literature Sense", SkillTree.MIND, 3, "insight"),
        active("eigenstate_collapse", "Eigenstate Collapse", SkillTree.MIND, 4, 80, week, "focus_chain", "literature_sense"),
        passive("restful", "Restful", SkillTree.SELF, 1),
        passive("streak_shield", "Streak Shield", SkillTree.SELF, 2, "restful"),
        passive("bonds", "Bonds", SkillTree.SELF, 3, "streak_shield"),
        passive("curious", "Curious", SkillTree.SELF, 1),
        active("clarity", "Clarity", SkillTree.SELF, 2, 15, day, "curious"),
        active("mentors_eye", "Mentor's Eye", SkillTree.SELF, 3, 30, week, "clarity"),
        passive("equilibrium", "Equilibrium", SkillTree.SELF, 4, "bonds", "mentors_eye"),
        SkillDef("superposition", "Superposition", SkillTree.OBSERVER, 1, SkillKind.ACTIVE, "A-rank.", locked = true),
        SkillDef("time_dilation", "Time Dilation", SkillTree.OBSERVER, 2, SkillKind.ACTIVE, "A-rank.", listOf("superposition"), locked = true),
        SkillDef("singularity", "Singularity", SkillTree.OBSERVER, 4, SkillKind.PASSIVE, "Σ.", listOf("time_dilation"), locked = true),
    )

    private val unlocked = setOf("iron_body", "momentum", "deep_work", "hyperfocus", "clear_mind", "restful")

    val state: SkillsUiState = SkillsUiState(
        loaded = true,
        skills = defs.map { def ->
            val check = when {
                def.id in unlocked -> UnlockCheck.AlreadyUnlocked
                def.locked -> UnlockCheck.Locked
                def.id == "light_feet" || def.id == "curious" -> UnlockCheck.Ok
                def.id == "limit_break" -> UnlockCheck.GateStat(35, 22)
                else -> def.prerequisites.firstOrNull { it !in unlocked }?.let { UnlockCheck.MissingPrerequisite(it) } ?: UnlockCheck.NoPoints
            }
            SkillStatus(def, def.id in unlocked, check, null)
        },
        classSkill = SkillStatus(
            SkillDef("first_principles", "First Principles", SkillTree.CLASS, 0, SkillKind.ACTIVE, "Replace one open Mind daily with a derivation.", limit = day, handler = "first_principles"),
            unlocked = true,
            check = UnlockCheck.AlreadyUnlocked,
            use = null,
        ),
        points = 2,
        respec = RespecCost.Token,
        tierGates = mapOf(2 to 20, 3 to 35, 4 to 50),
        treeStats = mapOf(
            SkillTree.BODY to listOf(Stat.STR, Stat.AGI),
            SkillTree.MIND to listOf(Stat.INT, Stat.FOC),
            SkillTree.SELF to listOf(Stat.WIS, Stat.VIT),
        ),
        casts = mapOf("hyperfocus" to CastCheck.OnCooldown(250), "first_principles" to CastCheck.NeedsTarget),
    )
}
