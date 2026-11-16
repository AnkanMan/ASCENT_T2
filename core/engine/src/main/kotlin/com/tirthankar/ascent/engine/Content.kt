package com.tirthankar.ascent.engine

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** When a lore line unlocks. Only the fields its [kind] needs are set. */
@Serializable
data class LoreUnlock(
    val kind: String,
    val level: Int? = null,
    val realm: Realm? = null,
    val arc: String? = null,
    val phase: Int? = null,
    val achievement: String? = null,
    // Phase 4
    val rank: Rank? = null,
    val classId: String? = null,
    /** Class evolution stage: 1 at B-rank, 2 at S-rank. */
    val stage: Int? = null,
    val skill: String? = null,
)

/** The Record's filters (PHASE4.md section 6). */
enum class LoreCategory { WORLD, RANKS, CLASSES, SKILLS, ARCS, BOSSES, GATES, ACHIEVEMENTS }

@Serializable
data class LoreEntry(
    val id: String,
    val title: String,
    val unlock: LoreUnlock,
    val text: String,
    /** Overrides the category its unlock kind implies. */
    val category: LoreCategory? = null,
) {
    val recordCategory: LoreCategory get() = category ?: LoreKinds.category(unlock.kind)
}

@Serializable
data class LoreContent(
    val v: Int = 1,
    val entries: List<LoreEntry>,
)

/** Unlock kinds used in content/lore.json (see docs/lore-bible.md). */
object LoreKinds {
    const val AWAKENING = "awakening"
    const val LEVEL = "level"
    const val CHAPTER_DONE = "chapter_done"
    const val ARC_END = "arc_end"
    const val ARC_FINALE = "arc_finale"
    const val GATE_BREAK = "gate_break"
    const val RED_GATE_CLEAR = "red_gate_clear"
    const val BOSS_PHASE = "boss_phase"
    const val BOSS_FINAL_PHASE = "boss_final_phase"
    const val BOSS_DEFEAT = "boss_defeat"
    const val BOSS_STIRS = "boss_stirs"
    const val BOSS_ENRAGE = "boss_enrage"
    const val TRIAL_WON = "trial_won"
    const val TRIAL_FAILED = "trial_failed"
    const val ACHIEVEMENT = "achievement"

    // Phase 4
    const val RANK_TRIAL_START = "rank_trial_start"
    const val RANK_TRIAL_FAIL = "rank_trial_fail"
    const val RANK = "rank"
    const val SIGMA_HINT = "sigma_hint"
    const val CLASS = "class"
    const val CLASS_EVOLUTION = "class_evolution"
    const val SKILL_FIRST = "skill_first"
    const val POWER_FIRST_CAST = "power_first_cast"
    const val SKILL_UNLOCK = "skill_unlock"
    const val OBSERVER_GLIMPSE = "observer_glimpse"
    const val RESPEC = "respec"

    // Phase 5
    /** A restore from a backup file finished (shown once). */
    const val RESTORE = "restore"

    val ALL: Set<String> = setOf(
        AWAKENING, LEVEL, CHAPTER_DONE, ARC_END, ARC_FINALE, GATE_BREAK, RED_GATE_CLEAR, BOSS_PHASE,
        BOSS_FINAL_PHASE, BOSS_DEFEAT, BOSS_STIRS, BOSS_ENRAGE, TRIAL_WON, TRIAL_FAILED, ACHIEVEMENT,
        RANK_TRIAL_START, RANK_TRIAL_FAIL, RANK, SIGMA_HINT, CLASS, CLASS_EVOLUTION, SKILL_FIRST,
        POWER_FIRST_CAST, SKILL_UNLOCK, OBSERVER_GLIMPSE, RESPEC, RESTORE,
    )

    /** The Record filter an unlock kind belongs to. */
    fun category(kind: String): LoreCategory = when (kind) {
        CHAPTER_DONE, ARC_END, ARC_FINALE -> LoreCategory.ARCS
        GATE_BREAK, RED_GATE_CLEAR -> LoreCategory.GATES
        BOSS_PHASE, BOSS_FINAL_PHASE, BOSS_DEFEAT, BOSS_STIRS, BOSS_ENRAGE, TRIAL_WON, TRIAL_FAILED -> LoreCategory.BOSSES
        ACHIEVEMENT -> LoreCategory.ACHIEVEMENTS
        RANK_TRIAL_START, RANK_TRIAL_FAIL, RANK, SIGMA_HINT -> LoreCategory.RANKS
        CLASS, CLASS_EVOLUTION -> LoreCategory.CLASSES
        SKILL_FIRST, POWER_FIRST_CAST, SKILL_UNLOCK, OBSERVER_GLIMPSE, RESPEC -> LoreCategory.SKILLS
        else -> LoreCategory.WORLD
    }
}

/** Lore text rules (docs/lore-bible.md): short, original, never franchise wording. */
object LoreRules {

    const val MAX_LENGTH = 200

    /** Words that must never appear in lore or item text (CLAUDE.md "Originality"). */
    private val franchise = Regex(
        """\b(shadows?|arise|monarchs?|hunters?|dungeons?|mana|awakened one|solo leveling|jin-?woo|igris|beru)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun hasFranchiseWords(text: String): Boolean = franchise.containsMatchIn(text)

    /** True when [text] may be stored as a lore line. */
    fun isValid(text: String): Boolean =
        text.isNotBlank() && text.length <= MAX_LENGTH && !hasFranchiseWords(text) && !SafetyRules.isForbidden(text)
}

/** Every content file, loaded together. */
data class ContentBundle(
    val loot: LootContent,
    val items: ItemsContent,
    val gates: GateContent,
    val quests: QuestContent,
    val achievements: AchievementContent,
    val arcs: ArcContent,
    val lore: LoreContent,
    // Phase 4
    val skills: SkillContent,
    val classes: ClassContent,
    val rankTrials: RankTrialContent,
    val avatar: AvatarContent,
    // Phase 5
    val guard: GuardContent = GuardContent(),
) {
    fun item(id: String): ItemTemplate? = items.items.firstOrNull { it.id == id }
    fun gate(id: String): GateTemplate? = gates.gates.firstOrNull { it.id == id }
    fun arc(id: String): ArcTemplate? = arcs.arcs.firstOrNull { it.id == id }
    fun achievement(id: String): AchievementDef? = achievements.achievements.firstOrNull { it.id == id }
}

/** Reads content JSON. Throws [IllegalArgumentException] on malformed files (caught by the loader). */
object ContentCodec {

    val json = Json {
        classDiscriminator = "kind"
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun <T> decode(serializer: KSerializer<T>, text: String): T = json.decodeFromString(serializer, text)

    private val affixList = ListSerializer(Affix.serializer())

    /** Item affixes as stored in the database. */
    fun encodeAffixes(affixes: List<Affix>): String = json.encodeToString(affixList, affixes)

    /** Never throws: a damaged row reads as no affixes. */
    fun decodeAffixes(text: String): List<Affix> =
        try {
            json.decodeFromString(affixList, text)
        } catch (e: IllegalArgumentException) {
            emptyList()
        }

    fun encodeUnique(unique: UniqueEffect): String = json.encodeToString(UniqueEffect.serializer(), unique)

    fun decodeUnique(text: String?): UniqueEffect? =
        text?.let {
            try {
                json.decodeFromString(UniqueEffect.serializer(), it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

    /** File names under assets/content/. */
    object Files {
        const val LOOT = "loot.json"
        const val ITEMS = "items.json"
        const val GATES = "gates.json"
        const val QUESTS = "quests.json"
        const val ACHIEVEMENTS = "achievements.json"
        const val ARCS = "arcs.json"
        const val LORE = "lore.json"
        const val SKILLS = "skills.json"
        const val CLASSES = "classes.json"
        const val RANK_TRIALS = "rank_trials.json"
        const val AVATAR = "avatar.json"
        const val GUARD = "guard.json"
    }

    /** Loads every file through [read], which returns a file's text by name. */
    fun load(read: (String) -> String): ContentBundle = ContentBundle(
        loot = decode(LootContent.serializer(), read(Files.LOOT)),
        items = decode(ItemsContent.serializer(), read(Files.ITEMS)),
        gates = decode(GateContent.serializer(), read(Files.GATES)),
        quests = decode(QuestContent.serializer(), read(Files.QUESTS)),
        achievements = decode(AchievementContent.serializer(), read(Files.ACHIEVEMENTS)),
        arcs = decode(ArcContent.serializer(), read(Files.ARCS)),
        lore = decode(LoreContent.serializer(), read(Files.LORE)),
        skills = decode(SkillContent.serializer(), read(Files.SKILLS)),
        classes = decode(ClassContent.serializer(), read(Files.CLASSES)),
        rankTrials = decode(RankTrialContent.serializer(), read(Files.RANK_TRIALS)),
        avatar = decode(AvatarContent.serializer(), read(Files.AVATAR)),
        guard = decode(GuardContent.serializer(), read(Files.GUARD)),
    )
}

/**
 * Checks content before it ships (Task 3): ids unique, references resolve, drop rows
 * sum to 100, affixes within their rarity, nothing forbidden, no franchise names.
 */
object ContentValidator {

    fun validate(c: ContentBundle): List<String> = buildList {
        addAll(loot(c.loot))
        addAll(items(c))
        addAll(gates(c.gates))
        addAll(quests(c.quests))
        addAll(achievements(c))
        addAll(arcs(c.arcs))
        addAll(lore(c))
        addAll(ProgressionValidator.validate(c))
        addAll(guard(c.guard))
    }

    private fun guard(g: GuardContent): List<String> = buildList {
        if (g.freeSlots < 0) add("guard: freeSlots ${g.freeSlots} is negative")
        if (g.decay <= 0.0 || g.decay > 1.0) add("guard: decay ${g.decay} must be in (0, 1]")
        if (g.floor <= 0.0 || g.floor > 1.0) add("guard: floor ${g.floor} must be in (0, 1]")
        Tier.entries.forEach { tier ->
            val slots = g.slots[tier]
            if (slots == null) add("guard: no slot count for $tier") else if (slots < 1) add("guard: $tier uses $slots slots")
        }
        // Raising the tier must never use fewer slots (D5).
        Tier.entries.zipWithNext().forEach { (lower, higher) ->
            if ((g.slots[higher] ?: 0) < (g.slots[lower] ?: 0)) add("guard: $higher uses fewer slots than $lower")
        }
    }

    internal fun MutableList<String>.text(where: String, text: String) {
        if (SafetyRules.isForbidden(text)) add("$where: forbidden wording \"$text\"")
        if (LoreRules.hasFranchiseWords(text)) add("$where: franchise wording \"$text\"")
    }

    internal fun MutableList<String>.uniqueIds(where: String, ids: List<String>) {
        ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { add("$where: duplicate id $it") }
    }

    private fun loot(l: LootContent): List<String> = buildList {
        DropSource.entries.forEach { source ->
            val table = l.tables[source]
            if (table == null) {
                add("loot: no table for $source")
                return@forEach
            }
            val sum = table.weights.values.sum()
            if (sum != 100) add("loot: $source weights sum to $sum, not 100")
            if (table.rollChance <= 0 || table.rollChance > 1) add("loot: $source rollChance ${table.rollChance}")
            if (table.weights.values.any { it < 0 }) add("loot: $source has a negative weight")
        }
        Rarity.entries.forEach { if (it !in l.rarities) add("loot: no rule for $it") }
        l.rarities.forEach { (rarity, rule) ->
            if (rule.minAffixes < 1 || rule.maxAffixes < rule.minAffixes) add("loot: bad affix count for $rarity")
        }
        if (l.rollingAffixes.any { it.phase4 }) add("loot: Phase 4 affix types must not roll yet (D7)")
        if (l.prefixes.isEmpty()) add("loot: no name prefixes")
        ItemSlot.entries.forEach { if (l.bases[it].isNullOrEmpty()) add("loot: no name bases for $it") }
        if (l.upgradeCosts.size != GearRules.MAX_UPGRADE) add("loot: need ${GearRules.MAX_UPGRADE} upgrade costs")
        (l.prefixes + l.bases.values.flatten()).forEach { text("loot name", it) }
    }

    private fun items(c: ContentBundle): List<String> = buildList {
        val items = c.items.items
        uniqueIds("items", items.map { it.id } + c.items.sets.map { it.id })
        items.forEach { item ->
            val rule = c.loot.rarities[item.rarity]
            val where = "item ${item.id}"
            text(where, item.name)
            item.unique?.let { text(where, it.text) }
            if (rule != null) {
                if (item.affixes.size !in rule.minAffixes..rule.maxAffixes) add("$where: ${item.affixes.size} affixes for ${item.rarity}")
                item.affixes.forEach { a ->
                    if (a.value <= 0 || a.value > rule.maxPerAffix + 1e-9) add("$where: affix ${a.type} ${a.value} over ${rule.maxPerAffix}")
                }
            }
            item.affixes.forEach { a ->
                if (a.type.phase4) add("$where: Phase 4 affix ${a.type} (use a dormant unique effect)")
                if (a.type == AffixType.MASTERY_STAT && a.stat == null) add("$where: mastery affix needs a stat")
                if (a.type == AffixType.XP_REALM && a.realm == null) add("$where: XP affix needs a realm")
            }
            if (item.rarity >= Rarity.LEGENDARY && item.unique == null) add("$where: ${item.rarity} needs a unique effect")
            if (item.setId != null && c.items.sets.none { it.id == item.setId }) add("$where: unknown set ${item.setId}")
        }
        c.items.sets.forEach { set ->
            text("set ${set.id}", set.name)
            val pieces = items.count { it.setId == set.id }
            if (pieces < set.pieces) add("set ${set.id}: $pieces pieces, needs ${set.pieces}")
        }
        // Every rarity a table can roll must be able to produce an item.
        listOf(Rarity.LEGENDARY, Rarity.MYTHIC).forEach { rarity ->
            val rolls = c.loot.tables.values.any { (it.weights[rarity] ?: 0) > 0 }
            if (rolls && items.none { it.rarity == rarity && it.drops }) add("items: tables roll $rarity but none can drop")
        }
    }

    private fun gates(g: GateContent): List<String> = buildList {
        uniqueIds("gates", g.gates.map { it.id })
        g.gates.forEach { gate ->
            val where = "gate ${gate.id}"
            text(where, gate.title)
            text(where, gate.lore)
            if (gate.days !in 3..7) add("$where: ${gate.days} days, must be 3–7")
            if (gate.objectives.size !in 3..6) add("$where: ${gate.objectives.size} objectives, must be 3–6")
            gate.objectives.forEach { o ->
                text(where, o.title)
                o.templates.forEach { key -> if (TemplateLibrary[key] == null) add("$where: unknown template $key") }
                if (o.sessions != null) {
                    val t = o.templates.firstOrNull()?.let { TemplateLibrary[it] }
                    if (t == null) add("$where: \"${o.title}\" has sessions but no template")
                    else if (t.realm != gate.realm) add("$where: template ${t.key} is not ${gate.realm}")
                } else if ((o.perWeek ?: 0.0) <= 0) {
                    add("$where: \"${o.title}\" needs sessions or perWeek")
                }
            }
        }
    }

    private fun quests(q: QuestContent): List<String> = buildList {
        uniqueIds("quests", (q.penalty + q.emergency).map { it.id })
        if (q.penalty.isEmpty()) add("quests: no penalty tasks")
        if (q.emergency.isEmpty()) add("quests: no emergency tasks")
        (q.penalty + q.emergency).forEach { t ->
            text("quest ${t.id}", t.title)
            if (t.stat.realm != t.realm) add("quest ${t.id}: stat ${t.stat} is not in ${t.realm}")
            if (t.target <= 0) add("quest ${t.id}: target must be > 0")
        }
    }

    private fun achievements(c: ContentBundle): List<String> = buildList {
        val defs = c.achievements.achievements
        uniqueIds("achievements", defs.map { it.id })
        defs.forEach { a ->
            val where = "achievement ${a.id}"
            text(where, a.name)
            text(where, a.description)
            if (a.metric !in AchievementMetrics.ALL) add("$where: unknown metric ${a.metric}")
            if (a.thresholds.isEmpty() || a.thresholds.zipWithNext().any { (x, y) -> y <= x }) add("$where: thresholds must rise")
            if (a.rewards.size != a.thresholds.size) add("$where: one reward per threshold")
            a.rewards.forEach { r ->
                r.title?.let { text(where, it) }
                if (r.item != null && c.item(r.item) == null) add("$where: unknown item ${r.item}")
                if (r.chest != null && r.chest !in setOf(DropSource.CHEST_ED, DropSource.CHEST_CB, DropSource.CHEST_AS)) {
                    add("$where: ${r.chest} is not a chest")
                }
            }
        }
    }

    private fun arcs(a: ArcContent): List<String> = buildList {
        uniqueIds("arcs", a.arcs.map { it.id })
        a.arcs.forEach { arc ->
            val where = "arc ${arc.id}"
            text(where, arc.title)
            arc.chapters.forEach { ch -> text(where, ch.title); ch.objectives.forEach { text(where, it.title) } }
            if (ArcRules.chapterCountWarning(arc.chapters.size)) add("$where: ${arc.chapters.size} chapters, must be 3–6")
            when (arc.finale) {
                FinaleKind.TRIAL -> if (arc.trialTemplate?.let { TemplateLibrary[it] } == null) add("$where: Trial needs a template")
                FinaleKind.BOSS, FinaleKind.TITLE -> if (arc.finaleName.isNullOrBlank()) add("$where: finale needs a name")
                FinaleKind.NONE -> Unit
            }
        }
    }

    private fun lore(c: ContentBundle): List<String> = buildList {
        val entries = c.lore.entries
        uniqueIds("lore", entries.map { it.id })
        entries.forEach { e ->
            val where = "lore ${e.id}"
            if (!LoreRules.isValid(e.text)) add("$where: text is empty, too long, forbidden or franchise wording")
            text(where, e.title)
            if (e.unlock.kind !in LoreKinds.ALL) add("$where: unknown unlock kind ${e.unlock.kind}")
            e.unlock.achievement?.let { if (c.achievement(it) == null) add("$where: unknown achievement $it") }
            e.unlock.arc?.let { if (c.arc(it) == null) add("$where: unknown arc $it") }
            e.unlock.classId?.let { if (c.classes.byId(it) == null) add("$where: unknown class $it") }
            e.unlock.skill?.let { if (c.skills.skill(it) == null) add("$where: unknown skill $it") }
            if (e.unlock.kind == LoreKinds.RANK && e.unlock.rank == null) add("$where: rank line needs a rank")
            if (e.unlock.kind == LoreKinds.CLASS_EVOLUTION && e.unlock.stage !in setOf(1, 2)) add("$where: evolution stage must be 1 or 2")
        }
    }
}
