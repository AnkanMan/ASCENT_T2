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
import com.tirthankar.ascent.engine.ClassDef
import com.tirthankar.ascent.engine.ClassOption
import com.tirthankar.ascent.engine.ClassRules
import com.tirthankar.ascent.engine.LoreKinds
import com.tirthankar.ascent.engine.Rank
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classes (PHASE4.md section 3, A5, A13, A14): Class Awakening at level 10, the
 * re-choice at each rank-up (made before the evolution), and evolution lore at B and S.
 * The unique skill comes with the class at no point cost.
 */
@Singleton
class ClassRepository @Inject constructor(
    private val db: AscentDatabase,
    private val contentRepository: ContentRepository,
    private val time: TimeSource,
    private val events: GameEvents,
    private val lore: LoreRepository,
) {
    private val content: ClassContent get() = contentRepository.content.classes

    fun definition(id: String?): ClassDef? = id?.let(content::byId)

    /** True while a class choice is waiting: Class Awakening or a rank-up's re-choice. */
    val choiceDue: Flow<Boolean> = db.playerDao().observePlayer().map { it != null && isDue(it) }

    /** The classes on offer now, or none when no choice is due. */
    suspend fun choices(): List<ClassOption> {
        val player = db.playerDao().getPlayer() ?: return emptyList()
        if (!isDue(player)) return emptyList()
        return ClassRules.choices(db.playerDao().getAttributes().toStatBlock(), content)
    }

    /**
     * Takes a class from the offer (or keeps the current one at a re-choice). The old
     * class's unique skill is put away; its use record stays.
     */
    suspend fun choose(classId: String): Boolean {
        val emitted = mutableListOf<GameEvent>()
        val done = db.withTransaction {
            val player = db.playerDao().getPlayer() ?: return@withTransaction false
            if (!isDue(player)) return@withTransaction false
            val keeping = classId == player.classId
            if (!keeping && !ClassRules.validChoice(classId, db.playerDao().getAttributes().toStatBlock(), content)) {
                return@withTransaction false
            }
            val def = content.byId(classId) ?: return@withTransaction false
            val skillDao = db.skillDao()
            definition(player.classId)?.takeIf { !keeping }?.let { skillDao.lock(listOf(it.unique.id)) }
            val now = time.now().toEpochMilli()
            skillDao.upsertSkill(
                skillDao.skill(def.unique.id)?.copy(unlocked = true) ?: PlayerSkillEntity(def.unique.id, now, null, 0, null),
            )
            db.playerDao().upsertPlayer(
                player.copy(classId = classId, classChosenAt = if (keeping) player.classChosenAt else now, classRechoice = false),
            )
            if (!keeping) {
                emitted += GameEvent.Notice(NoticeKind.CLASS_AWAKENED, ClassRules.displayName(def, player.rank))
                lore.unlockOnce(LoreKinds.CLASS, def.name, emitted) { it.classId == classId }
            }
            // The choice comes before the evolution (A14): at B and S the class chosen or
            // kept takes its evolved form now.
            evolutionLore(def, player.rank, emitted)
            true
        }
        emitted.forEach(events::emit)
        return done
    }

    private suspend fun evolutionLore(def: ClassDef, rank: Rank, emitted: MutableList<GameEvent>) {
        val stage = ClassRules.stage(rank)
        if (stage == 0) return
        lore.unlockOnce(LoreKinds.CLASS_EVOLUTION, ClassRules.displayName(def, rank), emitted) {
            it.classId == def.id && it.stage == stage
        }
    }

    private fun isDue(player: PlayerEntity): Boolean =
        ClassRules.awakeningDue(player.level, player.classId, content) || (player.classRechoice && player.classId != null)
}
