package com.tirthankar.ascent.feature.status

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.backup.BackupResult
import com.tirthankar.ascent.data.backup.BackupService
import com.tirthankar.ascent.data.backup.ExportService
import com.tirthankar.ascent.data.backup.RestoreCheck
import com.tirthankar.ascent.data.repo.PlayerRepository
import com.tirthankar.ascent.data.settings.BackupPrefs
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.engine.BackupManifest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import javax.inject.Inject

/**
 * The system file pickers, registered by the app (activity-compose lives in `:app`).
 * Each takes a callback that gets the picked content URI, or null when cancelled.
 */
class BackupPickers(
    val pickFolder: (onResult: (String?) -> Unit) -> Unit,
    val pickBackup: (onResult: (String?) -> Unit) -> Unit,
    /** Asks where to save a new file, suggesting [name]. */
    val createExport: (name: String, onResult: (String?) -> Unit) -> Unit,
)

/** Null in Previews and tests: the pick buttons are then disabled. */
val LocalBackupPickers = staticCompositionLocalOf<BackupPickers?> { null }

/** A one-line result under the Backup panel. */
sealed interface BackupMessage {
    data class Written(val name: String) : BackupMessage
    data class Failed(val reason: String) : BackupMessage
    data object NoFolder : BackupMessage
    data object Corrupt : BackupMessage
    data object Foreign : BackupMessage
    data object HashMismatch : BackupMessage
    data class Newer(val found: Int, val supported: Int) : BackupMessage
    data class PrepareFailed(val reason: String) : BackupMessage
    data object Exported : BackupMessage
    data object ExportFailed : BackupMessage
}

/** The checked backup, waiting for "Restore and restart". */
data class RestorePreviewUi(
    val createdAt: LocalDateTime?,
    val level: Int?,
    val totalXp: Long?,
    val streakDays: Int?,
    val journalExcluded: Boolean,
)

/** Shown after a restore, with Undo restore until the next reset. */
data class RestoredUi(
    val backupCreatedAt: LocalDateTime,
    val level: Int,
    val totalXp: Long,
    val streakDays: Int,
    val countsOk: Boolean,
)

data class BackupUiState(
    val folderLabel: String? = null,
    val hasFolder: Boolean = false,
    val lastBackupAt: LocalDateTime? = null,
    val lastError: String? = null,
    val includeJournal: Boolean = true,
    val busy: Boolean = false,
    val message: BackupMessage? = null,
    val confirm: RestorePreviewUi? = null,
    /** Non-null while Undo restore is available. */
    val restored: RestoredUi? = null,
    /** The suggested export file name, `ascent-export-YYYYMMDD.zip`. */
    val exportName: String = "ascent-export.zip",
)

sealed interface BackupEvent {
    data class PickedFolder(val uri: String?) : BackupEvent
    data object BackupNow : BackupEvent
    data class PickedRestore(val uri: String?) : BackupEvent
    data object ConfirmRestore : BackupEvent
    data object CancelRestore : BackupEvent
    data object UndoRestore : BackupEvent
    data class IncludeJournal(val include: Boolean) : BackupEvent
    data object DismissMessage : BackupEvent
    data class PickedExport(val uri: String?) : BackupEvent
}

private data class BackupLocal(
    val busy: Boolean = false,
    val message: BackupMessage? = null,
    val confirm: RestorePreviewUi? = null,
)

/** Settings → Backup (PHASE5.md section 3, Task 7). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backups: BackupService,
    private val prefs: BackupPrefs,
    players: PlayerRepository,
    private val time: TimeSource,
    private val export: ExportService,
) : ViewModel() {

    private val local = MutableStateFlow(BackupLocal())

    private val folderLabel = prefs.state.map { it.folderUri }.distinctUntilChanged().mapLatest { uri ->
        uri?.let { withContext(Dispatchers.IO) { backups.folderLabel(it) } ?: it.substringAfterLast('/') }
    }

    private val undo = prefs.state.mapLatest { s -> Triple(s, backups.canUndoRestore(), export.fileName()) }

    val uiState: StateFlow<BackupUiState> =
        combine(undo, folderLabel, players.profile, local) { (s, canUndo, exportName), label, profile, l ->
            BackupUiState(
                folderLabel = label,
                hasFolder = s.folderUri != null,
                lastBackupAt = s.lastBackupAt?.let(::local),
                lastError = s.lastError,
                includeJournal = !s.excludeJournal,
                busy = l.busy,
                message = l.message,
                confirm = l.confirm,
                restored = s.lastRestore?.takeIf { canUndo && profile != null }?.let { r ->
                    RestoredUi(local(r.backupCreatedAt), profile!!.level, profile.totalXp, profile.streakDays, r.countsOk)
                },
                exportName = exportName,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    fun onEvent(event: BackupEvent) {
        when (event) {
            is BackupEvent.PickedFolder -> event.uri?.let { uri ->
                work {
                    try {
                        backups.setFolder(uri)
                        null
                    } catch (e: SecurityException) {
                        BackupMessage.Failed(e.message ?: "permission")
                    }
                }
            }
            BackupEvent.BackupNow -> work {
                when (val r = backups.backupNow()) {
                    is BackupResult.Written -> BackupMessage.Written(r.name)
                    is BackupResult.Failed -> BackupMessage.Failed(r.reason)
                    BackupResult.NoFolder -> BackupMessage.NoFolder
                    BackupResult.Unchanged -> null
                }
            }
            is BackupEvent.PickedRestore -> event.uri?.let { uri ->
                viewModelScope.launch {
                    local.update { it.copy(busy = true, message = null) }
                    when (val check = backups.inspect(uri)) {
                        is RestoreCheck.Ok -> local.update { it.copy(busy = false, confirm = check.manifest.toPreview()) }
                        else -> local.update { it.copy(busy = false, message = check.toMessage()) }
                    }
                }
            }
            BackupEvent.ConfirmRestore -> viewModelScope.launch {
                local.update { it.copy(busy = true, confirm = null) }
                when (val check = backups.confirmRestore()) {
                    // The files are staged: restart so they're swapped in before anything opens them.
                    is RestoreCheck.Ok -> backups.restartApp()
                    else -> local.update { it.copy(busy = false, message = check.toMessage()) }
                }
            }
            BackupEvent.CancelRestore -> {
                backups.cancelRestore()
                local.update { it.copy(confirm = null) }
            }
            BackupEvent.UndoRestore -> viewModelScope.launch {
                local.update { it.copy(busy = true, message = null) }
                when (val check = backups.prepareUndo()) {
                    is RestoreCheck.Ok -> backups.restartApp()
                    else -> local.update { it.copy(busy = false, message = check.toMessage()) }
                }
            }
            is BackupEvent.IncludeJournal -> viewModelScope.launch { prefs.setExcludeJournal(!event.include) }
            is BackupEvent.PickedExport -> event.uri?.let { uri ->
                work { if (export.exportTo(uri)) BackupMessage.Exported else BackupMessage.ExportFailed }
            }
            BackupEvent.DismissMessage -> local.update { it.copy(message = null) }
        }
    }

    private fun work(block: suspend () -> BackupMessage?) = viewModelScope.launch {
        local.update { it.copy(busy = true, message = null) }
        val message = block()
        local.update { it.copy(busy = false, message = message) }
    }

    private fun local(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), time.zone())

    private fun BackupManifest.toPreview() = RestorePreviewUi(
        createdAt = try {
            LocalDateTime.parse(createdAtLocal)
        } catch (_: DateTimeParseException) {
            null
        },
        level = player?.level,
        totalXp = player?.totalXp,
        streakDays = player?.streakDays,
        journalExcluded = journalExcluded,
    )

    private fun RestoreCheck.toMessage(): BackupMessage = when (this) {
        RestoreCheck.Corrupt -> BackupMessage.Corrupt
        RestoreCheck.NotABackup -> BackupMessage.Foreign
        RestoreCheck.HashMismatch -> BackupMessage.HashMismatch
        is RestoreCheck.NewerSchema -> BackupMessage.Newer(found, supported)
        is RestoreCheck.Failed -> BackupMessage.PrepareFailed(reason)
        is RestoreCheck.Ok -> BackupMessage.PrepareFailed("unexpected")
    }
}

/** Settings → Backup, wired to its ViewModel and the app's file pickers. */
@Composable
fun BackupRoute(viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pickers = LocalBackupPickers.current
    BackupSection(
        state = state,
        onEvent = viewModel::onEvent,
        onPickFolder = pickers?.let { p -> { p.pickFolder { viewModel.onEvent(BackupEvent.PickedFolder(it)) } } },
        onPickRestore = pickers?.let { p -> { p.pickBackup { viewModel.onEvent(BackupEvent.PickedRestore(it)) } } },
        onExport = pickers?.let { p -> { p.createExport(state.exportName) { viewModel.onEvent(BackupEvent.PickedExport(it)) } } },
    )
}

/**
 * Restore only, for the Awakening screen of a fresh install: the way back after a new
 * phone or a reinstall (the PHASE5 exit test).
 */
@Composable
fun RestoreEntry(viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pickers = LocalBackupPickers.current ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(
            onClick = { pickers.pickBackup { viewModel.onEvent(BackupEvent.PickedRestore(it)) } },
            enabled = !state.busy,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(if (state.busy) R.string.backup_working else R.string.backup_restore), color = AscentColors.Mist) }
        state.message?.let { MessageLine(it, viewModel::onEvent) }
    }
    state.confirm?.let { ConfirmRestoreDialog(it, viewModel::onEvent) }
}

/** The Backup panel. Stateless. [onPickFolder] and [onPickRestore] are null without pickers. */
@Composable
fun BackupSection(
    state: BackupUiState,
    onEvent: (BackupEvent) -> Unit,
    onPickFolder: (() -> Unit)?,
    onPickRestore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onExport: (() -> Unit)? = null,
) {
    HoloPanel(modifier.fillMaxWidth(), header = stringResource(R.string.backup_header)) {
        Text(stringResource(R.string.backup_folder).uppercase(), style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.folderLabel ?: stringResource(R.string.backup_folder_none),
                style = MaterialTheme.typography.bodyLarge,
                color = AscentColors.Mist,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onPickFolder?.invoke() },
                enabled = onPickFolder != null && !state.busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (state.hasFolder) R.string.backup_folder_change else R.string.backup_folder_pick), color = AscentColors.GlowCyan)
            }
        }
        Text(stringResource(R.string.backup_schedule), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.7f))
        Text(
            state.lastBackupAt?.let { stringResource(R.string.backup_last, formatDateTime(it)) } ?: stringResource(R.string.backup_last_never),
            style = AscentTextStyles.Number,
            color = AscentColors.Mist,
            modifier = Modifier.padding(top = 8.dp),
        )
        state.lastError?.let {
            Text(stringResource(R.string.backup_last_error, it), style = MaterialTheme.typography.bodySmall, color = AscentColors.PenaltyRed)
        }
        OutlinedButton(
            onClick = { onEvent(BackupEvent.BackupNow) },
            enabled = state.hasFolder && !state.busy,
            modifier = Modifier
                .padding(top = 8.dp)
                .heightIn(min = 48.dp),
        ) { Text(stringResource(if (state.busy) R.string.backup_working else R.string.backup_now), color = AscentColors.GlowCyan) }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.backup_journal), style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist, modifier = Modifier.weight(1f))
            Switch(checked = state.includeJournal, onCheckedChange = { onEvent(BackupEvent.IncludeJournal(it)) })
        }
        Text(stringResource(R.string.backup_journal_note), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.7f))

        state.restored?.let { RestoredPanel(it, state.busy, onEvent) }

        TextButton(
            onClick = { onPickRestore?.invoke() },
            enabled = onPickRestore != null && !state.busy,
            modifier = Modifier
                .padding(top = 8.dp)
                .heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.backup_restore), color = AscentColors.GlowCyan) }
        Text(stringResource(R.string.backup_restore_note), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.7f))
        TextButton(
            onClick = { onExport?.invoke() },
            enabled = onExport != null && !state.busy,
            modifier = Modifier
                .padding(top = 8.dp)
                .heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.backup_export), color = AscentColors.GlowCyan) }
        Text(stringResource(R.string.backup_export_note), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.7f))
        if (onPickFolder == null) {
            Text(stringResource(R.string.backup_unavailable), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.5f))
        }
        state.message?.let { MessageLine(it, onEvent) }
    }
    state.confirm?.let { ConfirmRestoreDialog(it, onEvent) }
}

@Composable
private fun RestoredPanel(restored: RestoredUi, busy: Boolean, onEvent: (BackupEvent) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            stringResource(R.string.backup_restored, formatDateTime(restored.backupCreatedAt), restored.level, formatXp(restored.totalXp), restored.streakDays),
            style = MaterialTheme.typography.bodyMedium,
            color = AscentColors.GlowCyan,
        )
        if (!restored.countsOk) {
            Text(stringResource(R.string.backup_restored_counts), style = MaterialTheme.typography.bodySmall, color = AscentColors.LegendGold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onEvent(BackupEvent.UndoRestore) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.backup_undo), color = AscentColors.LegendGold)
            }
            Text(
                stringResource(R.string.backup_undo_note),
                style = MaterialTheme.typography.bodySmall,
                color = AscentColors.Mist.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun MessageLine(message: BackupMessage, onEvent: (BackupEvent) -> Unit) {
    val (text, bad) = when (message) {
        is BackupMessage.Written -> stringResource(R.string.backup_written, message.name) to false
        is BackupMessage.Failed -> stringResource(R.string.backup_failed, message.reason) to true
        BackupMessage.NoFolder -> stringResource(R.string.backup_no_folder) to true
        BackupMessage.Corrupt -> stringResource(R.string.backup_refused_corrupt) to true
        BackupMessage.Foreign -> stringResource(R.string.backup_refused_foreign) to true
        BackupMessage.HashMismatch -> stringResource(R.string.backup_refused_hash) to true
        is BackupMessage.Newer -> stringResource(R.string.backup_refused_newer, message.found, message.supported) to true
        is BackupMessage.PrepareFailed -> stringResource(R.string.backup_refused_failed, message.reason) to true
        BackupMessage.Exported -> stringResource(R.string.backup_exported) to false
        BackupMessage.ExportFailed -> stringResource(R.string.backup_export_failed) to true
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (bad) AscentColors.PenaltyRed else AscentColors.GlowCyan,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = { onEvent(BackupEvent.DismissMessage) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.backup_dismiss), color = AscentColors.Mist)
        }
    }
}

@Composable
private fun ConfirmRestoreDialog(preview: RestorePreviewUi, onEvent: (BackupEvent) -> Unit) {
    val made = preview.createdAt?.let { formatDateTime(it) } ?: "?"
    AlertDialog(
        onDismissRequest = { onEvent(BackupEvent.CancelRestore) },
        title = { Text(stringResource(R.string.backup_confirm_title)) },
        text = {
            Column {
                Text(
                    if (preview.level != null && preview.totalXp != null && preview.streakDays != null) {
                        stringResource(R.string.backup_confirm_body, made, preview.level, formatXp(preview.totalXp), preview.streakDays)
                    } else {
                        stringResource(R.string.backup_confirm_body_unknown, made)
                    },
                )
                if (preview.journalExcluded) {
                    Text(stringResource(R.string.backup_confirm_journal_out), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(BackupEvent.ConfirmRestore) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.backup_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(BackupEvent.CancelRestore) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.backup_cancel))
            }
        },
    )
}

@Composable
private fun formatDateTime(t: LocalDateTime): String =
    t.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(LocalConfiguration.current.locales[0]))

@Composable
private fun formatXp(xp: Long): String = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(xp)

// ---- Previews ----

internal object SampleBackup {
    val fresh = BackupUiState()
    val working = BackupUiState(
        folderLabel = "ASCENT backups",
        hasFolder = true,
        lastBackupAt = LocalDateTime.of(2026, 11, 16, 4, 12),
        message = BackupMessage.Written("ascent-backup-20261116-0412.zip"),
    )
    val restored = working.copy(
        message = null,
        restored = RestoredUi(LocalDateTime.of(2026, 11, 12, 22, 40), 23, 41_200, 12, countsOk = true),
    )
}

@Preview(name = "Backup — not set up", widthDp = 400, heightDp = 760)
@Composable
private fun BackupFreshPreview() {
    AscentTheme { SystemBackground { Column(Modifier.padding(16.dp)) { BackupSection(SampleBackup.fresh, {}, onPickFolder = {}, onPickRestore = {}) } } }
}

@Preview(name = "Backup — working", widthDp = 400, heightDp = 980)
@Composable
private fun BackupWorkingPreview() {
    AscentTheme { SystemBackground { Column(Modifier.padding(16.dp)) { BackupSection(SampleBackup.working, {}, onPickFolder = {}, onPickRestore = {}, onExport = {}) } } }
}

@Preview(name = "Backup — just restored", widthDp = 400, heightDp = 900)
@Composable
private fun BackupRestoredPreview() {
    AscentTheme { SystemBackground { Column(Modifier.padding(16.dp)) { BackupSection(SampleBackup.restored, {}, onPickFolder = {}, onPickRestore = {}) } } }
}

@Preview(name = "Backup — confirm restore", widthDp = 400, heightDp = 600)
@Composable
private fun BackupConfirmPreview() {
    AscentTheme {
        SystemBackground {
            ConfirmRestoreDialog(RestorePreviewUi(LocalDateTime.of(2026, 11, 12, 22, 40), 23, 41_200, 12, journalExcluded = true), onEvent = {})
        }
    }
}
