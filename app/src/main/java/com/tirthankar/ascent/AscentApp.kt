package com.tirthankar.ascent

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.toRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tirthankar.ascent.debug.DebugMenuEntry
import com.tirthankar.ascent.debug.DebugPanelRoute
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AuraPalette
import com.tirthankar.ascent.designsystem.AvatarSpec
import com.tirthankar.ascent.designsystem.CastSigilOverlay
import com.tirthankar.ascent.designsystem.LayeredAvatar
import com.tirthankar.ascent.designsystem.RankUpOverlay
import com.tirthankar.ascent.designsystem.RecordToast
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.tirthankar.ascent.designsystem.ChestItem
import com.tirthankar.ascent.designsystem.ChestRevealOverlay
import com.tirthankar.ascent.designsystem.LevelUpOverlay
import com.tirthankar.ascent.designsystem.RarityColors
import com.tirthankar.ascent.designsystem.SystemNoticeOverlay
import com.tirthankar.ascent.feature.awakening.AwakeningRoute
import com.tirthankar.ascent.feature.codex.CodexRoute
import com.tirthankar.ascent.feature.console.ConsoleRoute
import com.tirthankar.ascent.feature.gates.BossArenaRoute
import com.tirthankar.ascent.feature.gates.GatesRoute
import com.tirthankar.ascent.feature.inventory.InventoryRoute
import com.tirthankar.ascent.feature.quests.QuestLogRoute
import com.tirthankar.ascent.feature.quests.PlannerRoute
import com.tirthankar.ascent.feature.quests.StudyRoute
import com.tirthankar.ascent.feature.quests.TaskActivityRoute
import com.tirthankar.ascent.feature.skills.ClassAwakeningHost
import com.tirthankar.ascent.feature.skills.FocusSessionHost
import com.tirthankar.ascent.feature.skills.SkillsRoute
import com.tirthankar.ascent.feature.status.BackupPickers
import com.tirthankar.ascent.feature.status.LocalBackupPickers
import com.tirthankar.ascent.feature.status.RestoreEntry
import com.tirthankar.ascent.feature.status.SettingsRoute
import com.tirthankar.ascent.feature.status.StatusRoute
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/** Bottom-bar destinations (D8, D10; Phase 4 adds Skills): Status · Quests · Console · Gates · Skills. */
@Serializable data object StatusDestination

@Serializable data object StudyDestination

@Serializable data object PlannerDestination

@Serializable data class TaskActivityDestination(val questId: Long? = null, val itemId: Long? = null)

@Serializable data object QuestsDestination

@Serializable data object ConsoleDestination

@Serializable data object GatesDestination

@Serializable data object SkillsDestination

/** Screens opened from Status and Gates (Phase 3). */
@Serializable data object SettingsDestination

@Serializable data object InventoryDestination

@Serializable data object CodexDestination

@Serializable data class BossArenaDestination(val bossId: Long)

/** Debug builds only: the panel from Settings (Phase 4 Task 12). Release builds have an empty stub. */
@Serializable data object DebugDestination

private enum class Tab(val route: Any, val routeClass: KClass<*>, @StringRes val label: Int, val glyph: String) {
    STATUS(StatusDestination, StatusDestination::class, R.string.nav_status, "◆"),
    QUESTS(QuestsDestination, QuestsDestination::class, R.string.nav_quests, "⬡"),
    CONSOLE(ConsoleDestination, ConsoleDestination::class, R.string.nav_console, "▣"),
    GATES(GatesDestination, GatesDestination::class, R.string.nav_gates, "⧫"),
    SKILLS(SkillsDestination, SkillsDestination::class, R.string.nav_skills, "✦"),
}

/**
 * The single-activity shell. Before a player exists it shows the Awakening; after,
 * the three tabs, with the level-up sequence above everything.
 * [openConsole] is a one-shot request from a share into ASCENT.
 */
@Composable
fun AscentApp(
    openConsole: Boolean,
    onConsoleOpened: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val hasPlayer by viewModel.hasPlayer.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalBackupPickers provides rememberBackupPickers()) {
        when (hasPlayer) {
            null -> Unit // Loading: the Void background shows.
            false -> Scaffold(containerColor = Color.Transparent) { padding ->
                // A fresh install can restore before creating a character (Phase 5).
                AwakeningRoute(Modifier.padding(padding), restore = { RestoreEntry() })
            }
            true -> MainShell(viewModel, openConsole, onConsoleOpened)
        }
    }
}

/**
 * The system pickers for Settings → Backup (PHASE5.md section 3): a folder for backups
 * (its permission is persisted by the backup service) and a zip to restore. Registered
 * here because activity-compose lives in `:app`.
 */
@Composable
private fun rememberBackupPickers(): BackupPickers {
    var onFolder by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var onFile by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var onExport by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        onFolder?.invoke(uri?.toString())
        onFolder = null
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onFile?.invoke(uri?.toString())
        onFile = null
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        onExport?.invoke(uri?.toString())
        onExport = null
    }
    return remember(folder, file, export) {
        BackupPickers(
            pickFolder = { callback ->
                onFolder = callback
                folder.launch(null)
            },
            pickBackup = { callback ->
                onFile = callback
                file.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            },
            createExport = { name, callback ->
                onExport = callback
                export.launch(name)
            },
        )
    }
}

@Composable
private fun MainShell(viewModel: MainViewModel, openConsole: Boolean, onConsoleOpened: () -> Unit) {
    val nav = rememberNavController()
    val pageEntry by nav.currentBackStackEntryAsState()
    val activityPage = pageEntry?.destination?.let { it.hasRoute<PlannerDestination>() || it.hasRoute<TaskActivityDestination>() || it.hasRoute<StudyDestination>() } == true
    val levelUp by viewModel.levelUp.collectAsStateWithLifecycle()
    val loot by viewModel.loot.collectAsStateWithLifecycle()
    val notices by viewModel.notices.collectAsStateWithLifecycle()
    val cast by viewModel.cast.collectAsStateWithLifecycle()
    val rankUp by viewModel.rankUp.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val avatar by viewModel.avatar.collectAsStateWithLifecycle()
    NotificationPermission(viewModel)

    LaunchedEffect(openConsole) {
        if (openConsole) {
            nav.navigateToTab(Tab.CONSOLE)
            onConsoleOpened()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { if (!activityPage) BottomBar(nav) },
        ) { padding ->
            NavHost(nav, startDestination = StatusDestination, modifier = Modifier.padding(padding)) {
                composable<StatusDestination> {
                    StatusRoute(
                        onOpenInventory = { nav.navigate(InventoryDestination) },
                        onOpenCodex = { nav.navigate(CodexDestination) },
                        onOpenSettings = { nav.navigate(SettingsDestination) },
                        onOpenSkills = { nav.navigateToTab(Tab.SKILLS) },
                    )
                }
                composable<QuestsDestination> {
                    QuestLogRoute(
                        onOpenStudy = { nav.navigate(StudyDestination) },
                        onOpenPlanner = { nav.navigate(PlannerDestination) },
                        onOpenTask = { nav.navigate(TaskActivityDestination(it.questId, it.itemId)) },
                    )
                }
                composable<StudyDestination> { StudyRoute(onBack = { nav.popBackStack() }) }
                composable<PlannerDestination> {
                    PlannerRoute(onBack = { nav.popBackStack() }, onOpenTask = { nav.navigate(TaskActivityDestination(it.questId, it.itemId)) })
                }
                composable<TaskActivityDestination> { entry ->
                    val task = entry.toRoute<TaskActivityDestination>()
                    TaskActivityRoute(task.questId, task.itemId, onBack = { nav.popBackStack() }, onOpenStudy = { nav.navigate(StudyDestination) { launchSingleTop = true } })
                }
                composable<ConsoleDestination> { ConsoleRoute() }
                composable<GatesDestination> { GatesRoute(onOpenBoss = { id -> nav.navigate(BossArenaDestination(id)) }) }
                composable<SkillsDestination> { SkillsRoute() }
                composable<SettingsDestination> {
                    SettingsRoute(onBack = { nav.popBackStack() }, extra = { DebugMenuEntry(onOpen = { nav.navigate(DebugDestination) }) })
                }
                composable<DebugDestination> { DebugPanelRoute(onBack = { nav.popBackStack() }) }
                composable<InventoryDestination> { InventoryRoute(onBack = { nav.popBackStack() }) }
                composable<CodexDestination> { CodexRoute(onBack = { nav.popBackStack() }) }
                composable<BossArenaDestination> { BossArenaRoute(onBack = { nav.popBackStack() }) }
            }
        }
        // The Focus Session (A6) covers the app while a Hyperfocus session runs.
        FocusSessionHost()
        // Class Awakening at level 10, and the re-choice after a rank-up (A14).
        ClassAwakeningHost()
        val notice = notices.firstOrNull()
        when {
            levelUp != null -> Unit
            loot.isNotEmpty() -> ChestRevealOverlay(
                header = stringResource(R.string.chest_header),
                items = loot.map {
                    ChestItem(
                        name = it.name,
                        detail = stringResource(R.string.chest_item_detail, stringResource(NoticeTexts.rarity(it.rarity)), it.slot.name.lowercase()),
                        color = RarityColors.forLevel(it.rarity.ordinal),
                    )
                },
                dismissLabel = stringResource(R.string.notice_continue),
                onDismiss = viewModel::dismissLoot,
            )
            notice != null -> SystemNoticeOverlay(
                header = stringResource(NoticeTexts.header(notice.kind)),
                subject = notice.subject,
                detail = if (NoticeTexts.isGate(notice.kind)) notice.detail?.let { stringResource(R.string.notice_gate_xp, it) } else notice.detail,
                accent = NoticeTexts.accent(notice.kind),
                dismissLabel = stringResource(R.string.notice_continue),
                onDismiss = viewModel::dismissNotice,
            )
        }
        // ◆ NEW RECORD: lore lines type themselves out at the top, without blocking (PHASE4.md section 6).
        records.firstOrNull()?.let { record ->
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                RecordToast(
                    header = stringResource(R.string.record_header),
                    title = record.subject,
                    text = record.detail.orEmpty(),
                    onFinished = viewModel::recordShown,
                )
            }
        }
        // The sigil ring after any cast (Skills, a quest's long-press, the Boss Arena). Skipped with reduced motion.
        cast?.let { CastSigilOverlay(name = it.name, onFinished = viewModel::castShown) }
        // The rank-up sequence: the glyph forged from light, then the new aura.
        rankUp?.let { notice ->
            val look = avatar
            RankUpOverlay(
                rank = notice.subject,
                accent = look?.let { AuraPalette.forToken(it.aura.name) } ?: AscentColors.LegendGold,
                header = stringResource(R.string.rank_up_header),
                message = stringResource(R.string.rank_up_message, notice.subject),
                dismissLabel = stringResource(R.string.notice_continue),
                onDismiss = viewModel::dismissRankUp,
                avatar = {
                    look?.let {
                        LayeredAvatar(
                            AvatarSpec(it.aura.name, it.particles, it.rings, it.cloak, it.sigil, it.stage, it.gear),
                            stringResource(R.string.rank_up_avatar),
                            Modifier.fillMaxSize(),
                        )
                    }
                },
            )
        }
        levelUp?.let { event ->
            LevelUpOverlay(
                fromLevel = event.fromLevel,
                toLevel = event.toLevel,
                header = stringResource(R.string.level_up_header),
                message = pluralStringResource(R.plurals.level_up_message, event.freePoints, event.fromLevel, event.toLevel, event.freePoints),
                dismissLabel = stringResource(R.string.level_up_continue),
                onDismiss = viewModel::dismissLevelUp,
            )
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    NavigationBar(containerColor = AscentColors.PanelSolid) {
        Tab.entries.forEach { tab ->
            val selected = entry?.destination?.hasRoute(tab.routeClass) == true
            NavigationBarItem(
                selected = selected,
                onClick = { nav.navigateToTab(tab) },
                icon = {
                    Text(tab.glyph, style = AscentTextStyles.SystemHeader, modifier = Modifier.clearAndSetSemantics { })
                },
                label = { Text(stringResource(tab.label), style = AscentTextStyles.SystemLabel) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AscentColors.GlowCyan,
                    selectedTextColor = AscentColors.GlowCyan,
                    unselectedIconColor = AscentColors.Mist.copy(alpha = 0.6f),
                    unselectedTextColor = AscentColors.Mist.copy(alpha = 0.6f),
                    indicatorColor = AscentColors.SystemBlue.copy(alpha = 0.2f),
                ),
            )
        }
    }
}

/** Standard tab behaviour: one copy of each tab, back returns to Status. */
private fun NavHostController.navigateToTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Asks once, after the Awakening, for Android 13+'s notification permission (Task 38). */
@Composable
private fun NotificationPermission(viewModel: MainViewModel) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val asked by viewModel.notificationPermissionAsked.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.onPermissionAsked() }
    LaunchedEffect(asked) {
        if (asked == false) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
