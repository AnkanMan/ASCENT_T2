package com.tirthankar.ascent.feature.inventory

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.data.model.OwnedItem
import com.tirthankar.ascent.data.repo.AvatarRepository
import com.tirthankar.ascent.data.repo.InventoryRepository
import com.tirthankar.ascent.designsystem.AvatarSpec
import com.tirthankar.ascent.designsystem.LayeredAvatar
import androidx.compose.foundation.layout.height
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.Materialize
import com.tirthankar.ascent.designsystem.RarityColors
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.engine.Affix
import com.tirthankar.ascent.engine.AffixType
import com.tirthankar.ascent.engine.EquipSlot
import com.tirthankar.ascent.engine.GearRules
import com.tirthankar.ascent.engine.ItemSlot
import com.tirthankar.ascent.engine.Rarity
import com.tirthankar.ascent.engine.UniqueEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InventoryUiState(
    val essence: Int = 0,
    val items: List<OwnedItem> = emptyList(),
    val selectedId: Long? = null,
    /** The layered avatar wearing this gear (Phase 4). */
    val avatar: AvatarSpec? = null,
) {
    val equipped: Map<EquipSlot, OwnedItem> = items.mapNotNull { item -> item.equippedSlot?.let { it to item } }.toMap()
    val selected: OwnedItem? get() = items.firstOrNull { it.id == selectedId }

    /** What the selected item would replace. */
    val compare: OwnedItem? get() = selected?.takeIf { !it.equipped }?.let { s ->
        EquipSlot.forItem(s.slot).firstNotNullOfOrNull { equipped[it] }
    }
}

sealed interface InventoryEvent {
    data class Select(val itemId: Long) : InventoryEvent
    data object Close : InventoryEvent
    data class Equip(val itemId: Long) : InventoryEvent
    data class Unequip(val itemId: Long) : InventoryEvent
    data class Dismantle(val itemId: Long) : InventoryEvent
    data class Upgrade(val itemId: Long) : InventoryEvent
}

@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val inventory: InventoryRepository,
    avatar: AvatarRepository,
) : ViewModel() {
    private val selected = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<InventoryUiState> =
        combine(inventory.items, inventory.essence, selected, avatar.look) { items, essence, id, look ->
            InventoryUiState(essence, items, id, look?.let { AvatarSpec(it.aura.name, it.particles, it.rings, it.cloak, it.sigil, it.stage, it.gear) })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InventoryUiState())

    fun onEvent(event: InventoryEvent) {
        when (event) {
            is InventoryEvent.Select -> selected.value = event.itemId
            InventoryEvent.Close -> selected.value = null
            is InventoryEvent.Equip -> viewModelScope.launch { inventory.equip(event.itemId) }
            is InventoryEvent.Unequip -> viewModelScope.launch { inventory.unequip(event.itemId) }
            is InventoryEvent.Dismantle -> viewModelScope.launch { if (inventory.dismantle(event.itemId)) selected.value = null }
            is InventoryEvent.Upgrade -> viewModelScope.launch { inventory.upgrade(event.itemId) }
        }
    }
}

@Composable
fun InventoryRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: InventoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Materialize(modifier) { InventoryScreen(state, viewModel::onEvent, onBack) }
    state.selected?.let { ItemDialog(it, state.compare, state.essence, viewModel::onEvent) }
}

/** Inventory (Tasks 28–30): seven slots, items by rarity, Essence. Stateless. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InventoryScreen(state: InventoryUiState, onEvent: (InventoryEvent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("← " + stringResource(R.string.inventory_back), color = AscentColors.GlowCyan)
        }
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.inventory_equipped)) {
            state.avatar?.let {
                LayeredAvatar(
                    it,
                    stringResource(R.string.inventory_avatar_description),
                    Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally).width(96.dp).height(140.dp),
                )
            }
            Text(stringResource(R.string.inventory_essence, state.essence), style = AscentTextStyles.Number, color = AscentColors.LegendGold)
            EquipSlot.entries.forEach { slot ->
                val item = state.equipped[slot]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(enabled = item != null, role = Role.Button) { item?.let { onEvent(InventoryEvent.Select(it.id)) } }
                        .padding(vertical = 4.dp),
                ) {
                    Text(stringResource(slot.labelRes()).uppercase(), style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue, modifier = Modifier.width(110.dp))
                    Text(
                        item?.let { "${it.name}${upgradeSuffix(it)}" } ?: stringResource(R.string.inventory_empty_slot),
                        style = MaterialTheme.typography.bodyLarge,
                        color = item?.let { RarityColors.forLevel(it.rarity.ordinal) } ?: AscentColors.Mist.copy(alpha = 0.5f),
                    )
                }
            }
        }
        val bag = state.items.filter { !it.equipped }
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.inventory_items)) {
            if (bag.isEmpty()) {
                Text(stringResource(R.string.inventory_none), style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bag.sortedByDescending { it.rarity }.forEach { item ->
                    val color = RarityColors.forLevel(item.rarity.ordinal)
                    Column(
                        Modifier
                            .width(150.dp)
                            .border(1.dp, color)
                            .clickable(role = Role.Button) { onEvent(InventoryEvent.Select(item.id)) }
                            .padding(8.dp),
                    ) {
                        Text(item.name + upgradeSuffix(item), style = MaterialTheme.typography.bodyMedium, color = color)
                        Text(
                            "${stringResource(item.rarity.labelRes())} · ${stringResource(item.slot.labelRes())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AscentColors.Mist.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemDialog(item: OwnedItem, compare: OwnedItem?, essence: Int, onEvent: (InventoryEvent) -> Unit) {
    val color = RarityColors.forLevel(item.rarity.ordinal)
    AlertDialog(
        onDismissRequest = { onEvent(InventoryEvent.Close) },
        title = { Text(item.name + upgradeSuffix(item), color = color) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${stringResource(item.rarity.labelRes())} · ${stringResource(item.slot.labelRes())}" + (item.setName?.let { " · " + stringResource(R.string.inventory_set, it) } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = AscentColors.Mist.copy(alpha = 0.8f),
                )
                ItemEffects(item)
                compare?.let {
                    Text(stringResource(R.string.inventory_replaces, it.name), style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue, modifier = Modifier.padding(top = 8.dp))
                    ItemEffects(it)
                }
                Text(stringResource(R.string.inventory_source, item.source), style = MaterialTheme.typography.labelSmall, color = AscentColors.Mist.copy(alpha = 0.6f))
                item.upgradeCost?.let { cost ->
                    TextButton(onClick = { onEvent(InventoryEvent.Upgrade(item.id)) }, enabled = essence >= cost, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.inventory_upgrade, item.upgradeLevel + 1, cost), color = AscentColors.LegendGold)
                    }
                }
                if (!item.equipped) {
                    item.essenceValue?.let { value ->
                        TextButton(onClick = { onEvent(InventoryEvent.Dismantle(item.id)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.inventory_dismantle, value), color = AscentColors.PenaltyRed)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(if (item.equipped) InventoryEvent.Unequip(item.id) else InventoryEvent.Equip(item.id)) }) {
                Text(stringResource(if (item.equipped) R.string.inventory_unequip else R.string.inventory_equip))
            }
        },
        dismissButton = { TextButton(onClick = { onEvent(InventoryEvent.Close) }) { Text(stringResource(R.string.inventory_close)) } },
    )
}

@Composable
private fun ItemEffects(item: OwnedItem) {
    item.affixes.forEach { a ->
        Text(affixText(a, item.affixValue(a)), style = MaterialTheme.typography.bodyMedium, color = if (a.type.phase4) AscentColors.Mist.copy(alpha = 0.5f) else AscentColors.Mist)
    }
    item.unique?.let { u -> UniqueText(u) }
}

@Composable
private fun UniqueText(u: UniqueEffect) {
    Text(u.text, style = MaterialTheme.typography.bodyMedium, color = AscentColors.LegendGold)
    if (u.dormant) Text(stringResource(R.string.inventory_dormant), style = MaterialTheme.typography.labelSmall, color = AscentColors.Mist.copy(alpha = 0.6f))
}

@Composable
private fun affixText(a: Affix, value: Double): String {
    val pct = GearRules.percent(value).let { if (it == Math.floor(it)) it.toInt().toString() else it.toString() }
    return when (a.type) {
        AffixType.XP_REALM -> stringResource(R.string.affix_xp, pct, a.realm?.name ?: "")
        AffixType.MASTERY_STAT -> stringResource(R.string.affix_mastery, pct, a.stat?.name ?: "")
        AffixType.CRIT -> stringResource(R.string.affix_crit, pct)
        AffixType.STREAK_CAP -> stringResource(R.string.affix_streak, "%.2f".format(value))
        AffixType.HP_REGEN -> stringResource(R.string.affix_regen, pct)
        AffixType.GATE_REWARD -> stringResource(R.string.affix_gate, pct, a.realm?.name ?: "")
        AffixType.CHEST_LUCK -> stringResource(R.string.affix_luck, pct)
        AffixType.INSIGHT, AffixType.COOLDOWN -> stringResource(R.string.inventory_dormant)
    }
}

@Composable
private fun upgradeSuffix(item: OwnedItem): String = if (item.upgradeLevel > 0) " +${item.upgradeLevel}" else ""

@StringRes
private fun EquipSlot.labelRes(): Int = when (this) {
    EquipSlot.WEAPON -> R.string.slot_weapon
    EquipSlot.HEAD -> R.string.slot_head
    EquipSlot.BODY -> R.string.slot_body
    EquipSlot.HANDS -> R.string.slot_hands
    EquipSlot.FEET -> R.string.slot_feet
    EquipSlot.ACCESSORY_1 -> R.string.slot_accessory_1
    EquipSlot.ACCESSORY_2 -> R.string.slot_accessory_2
}

@StringRes
private fun ItemSlot.labelRes(): Int = when (this) {
    ItemSlot.WEAPON -> R.string.slot_weapon
    ItemSlot.HEAD -> R.string.slot_head
    ItemSlot.BODY -> R.string.slot_body
    ItemSlot.HANDS -> R.string.slot_hands
    ItemSlot.FEET -> R.string.slot_feet
    ItemSlot.ACCESSORY -> R.string.slot_accessory
}

@StringRes
private fun Rarity.labelRes(): Int = when (this) {
    Rarity.COMMON -> R.string.rarity_common
    Rarity.RARE -> R.string.rarity_rare
    Rarity.EPIC -> R.string.rarity_epic
    Rarity.LEGENDARY -> R.string.rarity_legendary
    Rarity.MYTHIC -> R.string.rarity_mythic
}

// ---- Previews ----

private fun sample(id: Long, name: String, slot: ItemSlot, rarity: Rarity, affixes: List<Affix>, equipped: EquipSlot? = null, unique: UniqueEffect? = null, upgrade: Int = 0) =
    OwnedItem(id, null, name, slot, rarity, affixes, unique, null, null, upgrade, equipped, "gate:gt3", 3, 10, 0.2)

private val sampleState = InventoryUiState(
    essence = 42,
    items = listOf(
        sample(1, "Hamiltonian Blade", ItemSlot.WEAPON, Rarity.LEGENDARY, listOf(Affix(AffixType.XP_REALM, 0.10, com.tirthankar.ascent.engine.Realm.MIND)), EquipSlot.WEAPON, UniqueEffect("One free Insight per day (Phase 4).", dormant = true)),
        sample(2, "Ergodic Cowl", ItemSlot.HEAD, Rarity.RARE, listOf(Affix(AffixType.CRIT, 0.03)), EquipSlot.HEAD, upgrade = 2),
        sample(3, "Diffusive Boots", ItemSlot.FEET, Rarity.COMMON, listOf(Affix(AffixType.HP_REGEN, 0.02))),
        sample(4, "Observer's Sigil", ItemSlot.ACCESSORY, Rarity.EPIC, listOf(Affix(AffixType.MASTERY_STAT, 0.06, stat = com.tirthankar.ascent.engine.Stat.FOC))),
    ),
)

@Preview(name = "Inventory", widthDp = 400, heightDp = 1000)
@Composable
private fun InventoryPreview() {
    AscentTheme { SystemBackground { InventoryScreen(sampleState, {}, {}) } }
}

@Preview(name = "Inventory — item", widthDp = 400, heightDp = 700)
@Composable
private fun ItemPreview() {
    AscentTheme { ItemDialog(sampleState.items[3], null, 42, {}) }
}
