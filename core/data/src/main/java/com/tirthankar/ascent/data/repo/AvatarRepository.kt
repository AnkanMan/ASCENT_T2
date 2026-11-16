package com.tirthankar.ascent.data.repo

import com.tirthankar.ascent.data.ContentRepository
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.InventoryItemEntity
import com.tirthankar.ascent.data.db.PlayerEntity
import com.tirthankar.ascent.data.model.AvatarLook
import com.tirthankar.ascent.engine.AvatarRules
import com.tirthankar.ascent.engine.ClassRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The layered avatar (PHASE4.md section 5): rank aura, level particles and rings, the
 * class layer and its evolution stage, and equipped gear. Shown on Status, in Inventory
 * and in the rank-up sequence.
 */
@Singleton
class AvatarRepository @Inject constructor(
    private val db: AscentDatabase,
    private val contentRepository: ContentRepository,
) {
    val look: Flow<AvatarLook?> =
        combine(db.playerDao().observePlayer(), db.inventoryDao().observeEquipped()) { player, equipped ->
            player?.let { look(it, equipped) }
        }

    internal fun look(player: PlayerEntity, equipped: List<InventoryItemEntity>): AvatarLook {
        val content = contentRepository.content
        val avatar = content.avatar
        val cls = player.classId?.let(content.classes::byId)
        val layer = player.classId?.let { avatar.classLayers[it] }
        return AvatarLook(
            rank = player.rank,
            aura = AvatarRules.aura(player.rank, avatar),
            particles = AvatarRules.particles(player.level, avatar),
            rings = AvatarRules.rings(player.level, avatar),
            cloak = layer?.cloak,
            sigil = layer?.sigil,
            stage = if (cls == null) 0 else ClassRules.stage(player.rank),
            gear = equipped.associate { it.slot.name to it.rarity.ordinal },
            name = player.name,
            title = player.title,
            className = cls?.let { ClassRules.displayName(it, player.rank) },
        )
    }
}
