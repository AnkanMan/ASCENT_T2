package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.ContentRepository
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.NoticeKind
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.CustomLoreEntity
import com.tirthankar.ascent.data.db.LoreTarget
import com.tirthankar.ascent.data.db.LoreUnlockEntity
import com.tirthankar.ascent.data.model.LoreLine
import com.tirthankar.ascent.data.model.RecordProgress
import com.tirthankar.ascent.engine.LoreCategory
import com.tirthankar.ascent.engine.LoreEntry
import com.tirthankar.ascent.engine.LoreKinds
import com.tirthankar.ascent.engine.LoreRules
import com.tirthankar.ascent.engine.LoreUnlock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A line to show for an event: Claude's line for your own content, or the seed line. */
data class LoreText(val id: String, val title: String, val text: String)

/** Claude's `add_lore` events (SPEC bridge v2) and the target each applies to. */
object CustomLoreEvents {
    const val CHAPTER_DONE = "chapter_done"
    const val ARC_END = "arc_end"
    const val DEFEAT = "defeat"
    const val BREAK = "break"
    fun phase(n: Int) = "phase_$n"

    fun targetOf(event: String): LoreTarget? = when {
        event == CHAPTER_DONE || event == ARC_END -> LoreTarget.ARC
        event == DEFEAT || event.matches(Regex("phase_[2-5]")) -> LoreTarget.BOSS
        event == BREAK -> LoreTarget.GATE
        else -> null
    }
}

/**
 * The lore codex: seed lines from content/lore.json and Claude's lines for your own
 * arcs, bosses and Gates. Each line enters the codex the first time it unlocks and is
 * then frozen (docs/lore-bible.md).
 */
@Singleton
class LoreRepository @Inject constructor(
    private val db: AscentDatabase,
    private val contentRepository: ContentRepository,
    private val time: TimeSource,
) {
    private val dao = db.progressDao()
    private val entries: List<LoreEntry> get() = contentRepository.content.lore.entries

    /** The Record (PHASE4.md section 6): unlocked lines in unlock order, with category and unread state. */
    val unlocked: Flow<List<LoreLine>> = dao.observeLore().map { list ->
        val byId = entries.associateBy { it.id }
        list.map { LoreLine(it.id, it.title, it.text, it.context, it.category ?: byId[it.id]?.recordCategory, unread = it.readAt == null) }
    }

    /** "n of m recorded": seed lines only; Claude's own lines are extra. */
    val record: Flow<RecordProgress> = dao.observeLore().map { list ->
        val ids = entries.map { it.id }.toSet()
        RecordProgress(list.count { it.id in ids }, ids.size)
    }

    /** The seed line for [kind] whose unlock matches [match]. */
    fun seed(kind: String, match: (LoreUnlock) -> Boolean = { true }): LoreEntry? =
        entries.firstOrNull { it.unlock.kind == kind && match(it.unlock) }

    /**
     * Unlocks the line for an event, preferring Claude's line for this [target]. Emits a
     * lore notice the first time. Returns the line shown, or null when there is none.
     * Call inside a transaction.
     */
    internal suspend fun unlock(
        seed: LoreEntry?,
        context: String?,
        emitted: MutableList<GameEvent>,
        target: Pair<LoreTarget, Long>? = null,
        event: String? = null,
    ): LoreText? {
        val custom = if (target != null && event != null) dao.customLore(target.first, target.second, event) else null
        val line = when {
            custom != null -> LoreText("custom:${custom.id}", custom.title, custom.text)
            seed != null -> LoreText(seed.id, seed.title, seed.text)
            else -> return null
        }
        if (custom != null && !custom.unlocked) dao.upsertCustomLore(custom.copy(unlocked = true))
        val category = when {
            custom != null -> when (custom.targetKind) {
                LoreTarget.ARC -> LoreCategory.ARCS
                LoreTarget.BOSS -> LoreCategory.BOSSES
                LoreTarget.GATE -> LoreCategory.GATES
            }
            else -> seed?.recordCategory
        }
        // Enters the codex once (insert is ignored after that); shown in context every time.
        dao.insertLore(LoreUnlockEntity(line.id, line.title, line.text, context, time.now().toEpochMilli(), category))
        emitted += GameEvent.Notice(NoticeKind.LORE, line.title, line.text)
        return line
    }

    /** Unlocks the Awakening and level lines up to [level]. Call inside a transaction. */
    internal suspend fun syncLevels(level: Int, emitted: MutableList<GameEvent>) {
        val have = dao.loreIds().toSet()
        entries.filter {
            (it.unlock.kind == LoreKinds.AWAKENING || (it.unlock.kind == LoreKinds.LEVEL && (it.unlock.level ?: Int.MAX_VALUE) <= level)) &&
                it.id !in have
        }.forEach { unlock(it, null, emitted) }
    }

    /**
     * Stores a line Claude wrote for one of your arcs, bosses or Gates (`add_lore`).
     * Returns false if the text breaks the lore rules or that event's line already unlocked.
     */
    suspend fun addCustom(target: LoreTarget, targetId: Long, event: String, title: String, text: String): Boolean =
        db.withTransaction {
            if (!LoreRules.isValid(text) || LoreRules.hasFranchiseWords(title)) return@withTransaction false
            val existing = dao.customLore(target, targetId, event)
            if (existing?.unlocked == true) return@withTransaction false
            dao.upsertCustomLore(
                CustomLoreEntity(
                    id = existing?.id ?: 0,
                    targetKind = target,
                    targetId = targetId,
                    event = event,
                    title = title.trim(),
                    text = text.trim(),
                    unlocked = false,
                    createdAtMillis = time.now().toEpochMilli(),
                ),
            )
            true
        }

    /** Unlocks [kind]'s seed line once, if it exists and isn't recorded yet. Call inside a transaction. */
    internal suspend fun unlockOnce(kind: String, context: String?, emitted: MutableList<GameEvent>, match: (LoreUnlock) -> Boolean = { true }) {
        val entry = seed(kind, match) ?: return
        if (entry.id in dao.loreIds()) return
        unlock(entry, context, emitted)
    }

    /** Opening an entry clears its unread dot. */
    suspend fun markRead(id: String) = dao.markRead(id, time.now().toEpochMilli())

    val unreadCount: Flow<Int> = dao.observeUnread()

    /** True when the line for this event has already unlocked (and is frozen). */
    suspend fun isCustomUnlocked(target: LoreTarget, targetId: Long, event: String): Boolean =
        dao.customLore(target, targetId, event)?.unlocked == true
}
