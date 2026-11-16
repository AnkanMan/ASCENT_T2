package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.ContentRepository
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.NoticeKind
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.PlayerEntity
import com.tirthankar.ascent.data.db.PlayerSkillEntity
import com.tirthankar.ascent.engine.ClassContent
import com.tirthankar.ascent.engine.ClassRules
import com.tirthankar.ascent.engine.Effect
import com.tirthankar.ascent.engine.LoreKinds
import com.tirthankar.ascent.engine.Respec
import com.tirthankar.ascent.engine.SkillContent
import com.tirthankar.ascent.engine.SkillDef
import com.tirthankar.ascent.engine.SkillRules
import com.tirthankar.ascent.engine.SkillUse
import com.tirthankar.ascent.engine.UnlockCheck
import com.tirthankar.ascent.engine.SkillTree
import com.tirthankar.ascent.engine.Stat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** One skill as the Skills screen sees it. [check] is why it can't be unlocked (Ok when it can). */
data class SkillStatus(
    val def: SkillDef,
    val unlocked: Boolean,
    val check: UnlockCheck,
    val use: SkillUse?,
)

/** What a respec would cost now: a token, Essence, or not affordable / nothing to return. */
sealed interface RespecCost {
    data object Token : RespecCost
    data class Essence(val amount: Int) : RespecCost
    data class CannotAfford(val essenceNeeded: Int) : RespecCost
    data object NothingToReturn : RespecCost
}

data class SkillOverview(
    val skills: List<SkillStatus>,
    /** The class's unique skill, once a class is chosen. */
    val classSkill: SkillStatus?,
    val unspentPoints: Int,
    val respec: RespecCost,
    /** Gate stat needed per tier (2 → 20, 3 → 35, 4 → 50) and each tree's two stats (A4). */
    val tierGates: Map<Int, Int>,
    val treeStats: Map<SkillTree, List<Stat>>,
)

/**
 * Skill points, unlocks and respec (PHASE4.md section 1). Points are computed, not
 * stored: one every even level plus one per boss defeated, so points from bosses beaten
 * before Phase 4 count at once. Also folds the Phase 4 effects (passives and class)
 * that RewardCalculator caps.
 */
@Singleton
class SkillRepository @Inject constructor(
    private val db: AscentDatabase,
    private val contentRepository: ContentRepository,
    private val time: TimeSource,
    private val events: GameEvents,
    private val lore: LoreRepository,
) {
    private val dao = db.skillDao()
    private val skills: SkillContent get() = contentRepository.content.skills
    private val classes: ClassContent get() = contentRepository.content.classes

    /** Ids of the skills you hold now (tree and class). */
    val unlocked: Flow<Set<String>> = dao.observeSkills().map { rows -> rows.filter { it.unlocked }.map { it.skillId }.toSet() }

    /** The whole tree with each skill's state, refreshed when skills, the player or stats change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val overview: Flow<SkillOverview?> =
        combine(dao.observeSkills(), db.playerDao().observePlayer(), db.playerDao().observeAttributes()) { _, player, _ -> player != null }
            .mapLatest { if (it) overview() else null }

    suspend fun overview(): SkillOverview? {
        val player = db.playerDao().getPlayer() ?: return null
        val rows = dao.skills().associateBy { it.skillId }
        val unlocked = rows.values.filter { it.unlocked }.map { it.skillId }.toSet()
        val bosses = db.bossDao().defeatedCount()
        val stats = db.playerDao().getAttributes().toStatBlock()
        val unspent = unspent(player, unlocked, bosses)
        fun status(def: SkillDef, check: UnlockCheck) = SkillStatus(def, def.id in unlocked, check, rows[def.id]?.toUse())
        val tree = skills.skills.map { def -> status(def, SkillRules.canUnlock(def, unlocked, unspent, stats, bosses, skills)) }
        val classSkill = player.classId?.let(classes::byId)?.unique?.let { status(it, UnlockCheck.AlreadyUnlocked) }
        val treeIds = unlocked.filter { skills.skill(it) != null }
        val respec = when {
            treeIds.isEmpty() -> RespecCost.NothingToReturn
            player.respecTokens > 0 -> RespecCost.Token
            player.essence >= skills.respecEssence -> RespecCost.Essence(skills.respecEssence)
            else -> RespecCost.CannotAfford(skills.respecEssence)
        }
        return SkillOverview(tree, classSkill, unspent, respec, skills.tierGates, skills.treeStats)
    }

    /** A tree skill or a class's unique skill. */
    fun definition(id: String): SkillDef? = skills.skill(id) ?: classes.classes.firstOrNull { it.unique.id == id }?.unique

    /** The skill that carries [handler] (tree or class). */
    fun idFor(handler: String): String? =
        (skills.skills + classes.classes.map { it.unique }).firstOrNull { it.handler == handler }?.id

    suspend fun unlockedIds(): Set<String> = dao.skills().filter { it.unlocked }.map { it.skillId }.toSet()

    suspend fun unspentPoints(): Int {
        val player = db.playerDao().getPlayer() ?: return 0
        return unspent(player, unlockedIds(), db.bossDao().defeatedCount())
    }

    suspend fun checkUnlock(id: String): UnlockCheck {
        val def = skills.skill(id) ?: return UnlockCheck.Locked
        val player = db.playerDao().getPlayer() ?: return UnlockCheck.Locked
        val unlocked = unlockedIds()
        val bosses = db.bossDao().defeatedCount()
        val stats = db.playerDao().getAttributes().toStatBlock()
        return SkillRules.canUnlock(def, unlocked, unspent(player, unlocked, bosses), stats, bosses, skills)
    }

    /** Unlocks a tree skill when every rule holds; returns the check either way. */
    suspend fun unlock(id: String): UnlockCheck {
        val emitted = mutableListOf<GameEvent>()
        val result = db.withTransaction {
            val check = checkUnlock(id)
            if (check != UnlockCheck.Ok) return@withTransaction check
            val def = skills.skill(id) ?: return@withTransaction UnlockCheck.Locked
            val now = time.now().toEpochMilli()
            dao.upsertSkill(dao.skill(id)?.copy(unlocked = true, unlockedAt = now) ?: PlayerSkillEntity(id, now, null, 0, null))
            emitted += GameEvent.Notice(NoticeKind.SKILL_UNLOCKED, def.name)
            lore.unlockOnce(LoreKinds.SKILL_FIRST, def.name, emitted)
            lore.unlockOnce(LoreKinds.SKILL_UNLOCK, def.name, emitted) { it.skill == id }
            UnlockCheck.Ok
        }
        emitted.forEach(events::emit)
        return result
    }

    /**
     * Returns every point spent on tree skills: free with a respec token, otherwise
     * Essence. Unlocks are lost; cooldowns and period uses are kept. Null when nothing is
     * unlocked or it can't be paid for.
     */
    suspend fun respec(): Respec? {
        val emitted = mutableListOf<GameEvent>()
        val result = db.withTransaction {
            val player = db.playerDao().getPlayer() ?: return@withTransaction null
            val unlocked = unlockedIds()
            val tree = unlocked.filter { skills.skill(it) != null }
            if (tree.isEmpty()) return@withTransaction null
            val respec = SkillRules.respec(unlocked, player.respecTokens, player.essence, skills) ?: return@withTransaction null
            dao.lock(tree)
            db.playerDao().upsertPlayer(
                player.copy(
                    respecTokens = player.respecTokens - if (respec.usesToken) 1 else 0,
                    essence = player.essence - respec.essenceCost,
                ),
            )
            lore.unlockOnce(LoreKinds.RESPEC, null, emitted)
            respec
        }
        emitted.forEach(events::emit)
        return result
    }

    /**
     * Phase 4 effects on quests: unlocked passives, plus the class bonus and its passive
     * unique (A13). Gear is separate (it has its own cap inside the +75%).
     */
    internal suspend fun effects(player: PlayerEntity?): List<Effect> =
        SkillRules.passiveEffects(unlockedIds(), skills) + ClassRules.effects(player?.classId?.let(classes::byId))

    suspend fun effects(): List<Effect> = effects(db.playerDao().getPlayer())

    internal suspend fun use(id: String): SkillUse? = dao.skill(id)?.toUse()

    private fun unspent(player: PlayerEntity, unlocked: Set<String>, bosses: Int): Int =
        SkillRules.unspent(player.level, bosses, unlocked, skills) + player.bonusSkillPoints
}

internal fun PlayerSkillEntity.toUse(): SkillUse = SkillUse(periodKey, usesInPeriod, lastCastAt?.let(Instant::ofEpochMilli))
