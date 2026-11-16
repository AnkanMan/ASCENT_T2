package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.ContentRepository
import com.tirthankar.ascent.data.DroppedItem
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.InventoryItemEntity
import com.tirthankar.ascent.data.model.OwnedItem
import com.tirthankar.ascent.engine.Affix
import com.tirthankar.ascent.engine.ContentCodec
import com.tirthankar.ascent.engine.DropSource
import com.tirthankar.ascent.engine.EquipSlot
import com.tirthankar.ascent.engine.GearBonus
import com.tirthankar.ascent.engine.GearPiece
import com.tirthankar.ascent.engine.GearRules
import com.tirthankar.ascent.engine.LootRoller
import com.tirthankar.ascent.engine.Rarity
import com.tirthankar.ascent.engine.RolledItem
import com.tirthankar.ascent.engine.Seeds
import com.tirthankar.ascent.engine.UniqueEffect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Gear (SPEC "Loot and equipment"): drops, equip, dismantle into Essence, upgrade. */
@Singleton
class InventoryRepository @Inject constructor(
    private val db: AscentDatabase,
    private val contentRepository: ContentRepository,
    private val time: TimeSource,
    private val events: GameEvents,
) {
    private val dao = db.inventoryDao()
    private val content get() = contentRepository.content

    val items: Flow<List<OwnedItem>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    val essence: Flow<Int> = db.playerDao().observePlayer().map { it?.essence ?: 0 }

    val equipped: Flow<List<OwnedItem>> = combine(dao.observeEquipped(), essence) { list, _ -> list.map { it.toModel() } }

    /** Everything worn adds up to this; Phase 4 parts are listed as dormant. */
    suspend fun gearBonus(): GearBonus = fold(dao.equipped())

    internal fun fold(equipped: List<InventoryItemEntity>): GearBonus =
        GearRules.fold(
            equipped.map { GearPiece(decodeAffixes(it.affixesJson), it.upgradeLevel, decodeUnique(it.uniqueJson), it.setId) },
            content.items.sets,
            content.loot.upgradeStep,
        )

    /**
     * Rolls [source]'s table for the event [eventKey] (seeded, so repeating the action
     * replays the same roll) and stores any item. Call inside a transaction.
     */
    internal suspend fun rollAndGrant(source: DropSource, eventKey: String, origin: String): InventoryItemEntity? {
        val player = db.playerDao().getPlayer() ?: return null
        val seed = Seeds.forEvent(player.rollSeed, eventKey)
        val luck = fold(dao.equipped()).chestLuck
        val rolled = LootRoller.roll(source, seed, content.loot, content.items, luck) ?: return null
        return grant(rolled, origin)
    }

    /** Gives a hand-written item once per [origin]. Call inside a transaction. */
    internal suspend fun grantTemplate(templateId: String, origin: String): InventoryItemEntity? {
        if (dao.hasSource(origin)) return null
        val template = content.item(templateId) ?: return null
        return grant(LootRoller.fromTemplate(template, Seeds.eventId(origin)), origin)
    }

    internal suspend fun grant(rolled: RolledItem, origin: String): InventoryItemEntity {
        val entity = InventoryItemEntity(
            templateId = rolled.templateId,
            name = rolled.name,
            slot = rolled.slot,
            rarity = rolled.rarity,
            affixesJson = ContentCodec.encodeAffixes(rolled.affixes),
            uniqueJson = rolled.unique?.let(ContentCodec::encodeUnique),
            setId = rolled.setId,
            upgradeLevel = 0,
            equippedSlot = null,
            source = origin,
            seed = rolled.seed,
            acquiredAtMillis = time.now().toEpochMilli(),
        )
        val id = dao.insert(entity)
        return entity.copy(id = id)
    }

    /** Wears an item. With no [slot], an empty matching slot is used, else the first. */
    suspend fun equip(itemId: Long, slot: EquipSlot? = null): Boolean = db.withTransaction {
        val item = dao.get(itemId) ?: return@withTransaction false
        val slots = EquipSlot.forItem(item.slot)
        val worn = dao.equipped()
        val target = slot?.takeIf { it in slots }
            ?: slots.firstOrNull { s -> worn.none { it.equippedSlot == s } }
            ?: slots.first()
        worn.filter { it.equippedSlot == target && it.id != item.id }.forEach { dao.update(it.copy(equippedSlot = null)) }
        dao.update(item.copy(equippedSlot = target))
        true
    }

    suspend fun unequip(itemId: Long): Boolean = db.withTransaction {
        val item = dao.get(itemId) ?: return@withTransaction false
        dao.update(item.copy(equippedSlot = null))
        true
    }

    /** Unmakes an unequipped item into Essence. Mythic items can't be dismantled (D13). */
    suspend fun dismantle(itemId: Long): Boolean = db.withTransaction {
        val item = dao.get(itemId) ?: return@withTransaction false
        if (item.equippedSlot != null) return@withTransaction false
        val essence = GearRules.essenceFor(item.rarity, content.loot.rarities) ?: return@withTransaction false
        val player = db.playerDao().getPlayer() ?: return@withTransaction false
        dao.delete(item.id)
        db.playerDao().upsertPlayer(player.copy(essence = player.essence + essence))
        true
    }

    /** Spends Essence for the next upgrade level, up to +5. */
    suspend fun upgrade(itemId: Long): Boolean = db.withTransaction {
        val item = dao.get(itemId) ?: return@withTransaction false
        val cost = GearRules.upgradeCost(item.upgradeLevel, content.loot.upgradeCosts) ?: return@withTransaction false
        val player = db.playerDao().getPlayer() ?: return@withTransaction false
        if (player.essence < cost) return@withTransaction false
        db.playerDao().upsertPlayer(player.copy(essence = player.essence - cost))
        dao.update(item.copy(upgradeLevel = item.upgradeLevel + 1))
        true
    }

    /** Announces items gained so the chest animation can reveal them. */
    internal fun announce(items: List<InventoryItemEntity>) {
        if (items.isNotEmpty()) events.emit(GameEvent.LootDropped(items.map { it.toDropped() }))
    }

    internal fun InventoryItemEntity.toModel(): OwnedItem = OwnedItem(
        id = id,
        templateId = templateId,
        name = name,
        slot = slot,
        rarity = rarity,
        affixes = decodeAffixes(affixesJson),
        unique = decodeUnique(uniqueJson),
        setId = setId,
        setName = setId?.let { id -> content.items.sets.firstOrNull { it.id == id }?.name },
        upgradeLevel = upgradeLevel,
        equippedSlot = equippedSlot,
        source = source,
        essenceValue = if (rarity == Rarity.MYTHIC) null else GearRules.essenceFor(rarity, content.loot.rarities),
        upgradeCost = GearRules.upgradeCost(upgradeLevel, content.loot.upgradeCosts),
        upgradeStep = content.loot.upgradeStep,
    )

    private companion object {
        fun decodeAffixes(json: String): List<Affix> = ContentCodec.decodeAffixes(json)

        fun decodeUnique(json: String?): UniqueEffect? = ContentCodec.decodeUnique(json)
    }
}

internal fun InventoryItemEntity.toDropped() = DroppedItem(name, rarity, slot)
