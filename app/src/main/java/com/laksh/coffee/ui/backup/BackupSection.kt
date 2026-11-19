package com.laksh.coffee.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.data.backup.ImportState
import com.laksh.coffee.ui.catalog.SheetNote
import com.laksh.coffee.ui.components.ActionRow
import com.laksh.coffee.ui.components.GroupPosition
import com.laksh.coffee.ui.components.GroupRow
import com.laksh.coffee.ui.components.LocalAnimationsActive
import com.laksh.coffee.ui.components.SectionLabel
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.ToggleRow
import java.time.LocalDate

/**
 * Gear › App › YOUR DATA: save everything to one file, or bring a file back.
 *
 * Both go through the system file picker, so the app needs no storage permission and the file
 * lands exactly where you choose. Neither uses the network.
 */
@Composable
fun BackupSection(
    modifier: Modifier = Modifier,
    vm: BackupViewModel = viewModel(factory = BackupViewModel.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val importState by vm.import.collectAsStateWithLifecycle()

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.save(uri)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }

    val saving = ui.export == ExportState.Saving
    val reading = importState == ImportState.Reading
    // Tabs stay composed, so "coming on screen" is this signal and not a new composition: without
    // it the size and the photo count would be the ones read when the app started.
    val onScreen = LocalAnimationsActive.current
    LaunchedEffect(onScreen) {
        if (onScreen) vm.refreshEstimate()
    }

    val estimate = ui.estimate
    val photoCount = estimate?.photoCount ?: 0

    Column(modifier) {
        SectionLabel("YOUR DATA")
        ActionRow(
            icon = Icons.Rounded.Save,
            title = if (saving) "Saving a backup…" else "Back up everything",
            subtitle = estimate?.let { BackupCopy.sizeLine(it.withoutPhotos, it.withPhotos, it.photoCount, ui.includePhotos) }
                ?: "Everything you logged, in one file",
            // The date is read at the tap, not while composing: this page stays composed overnight.
            onClick = { create.launch(BackupCopy.fileName(LocalDate.now())) },
            position = GroupPosition.First,
            enabled = !saving,
        )
        GroupRow(position = GroupPosition.Middle, hairlineInset = 64.dp) {
            ToggleRow(
                label = "Include photos",
                checked = ui.includePhotos,
                onChange = vm::setIncludePhotos,
                // 64dp: the label lines up with the titles of the rows above and below it.
                modifier = Modifier.padding(start = 64.dp, end = 16.dp),
                subtitle = when (photoCount) {
                    0 -> "No photos yet"
                    1 -> "1 photo"
                    else -> "$photoCount photos"
                },
            )
        }
        ActionRow(
            icon = Icons.Rounded.Restore,
            title = if (reading) "Reading the backup…" else "Restore from a backup",
            subtitle = "From Android or the web, or an older JSON export",
            onClick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            position = GroupPosition.Last,
            enabled = importState == ImportState.Idle,
        )

        when (val e = ui.export) {
            is ExportState.Saved -> {
                Spacer(Modifier.height(10.dp))
                StatusLine(
                    // Saved, with something to know: some photos are not in this file.
                    if (e.photosLeftOut > 0) Status.Attention else Status.Good,
                    "Saved",
                    Modifier.padding(horizontal = 4.dp),
                    detail = BackupCopy.savedDetail(e.counts, e.photosWithData, e.photosLeftOut),
                )
            }
            ExportState.Failed -> {
                Spacer(Modifier.height(10.dp))
                StatusLine(Status.Fault, "Not saved", Modifier.padding(horizontal = 4.dp), detail = BackupCopy.SAVE_FAILED)
            }
            else -> Unit
        }
        Spacer(Modifier.height(8.dp))
        SheetNote(
            "A backup holds your brews, coffees, grinders, recipes, saved analyses and settings. " +
                "API keys never go into it.",
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    }

    if (importState != ImportState.Idle && importState != ImportState.Reading) {
        ImportPreviewSheet(
            state = importState,
            onConfirm = vm::confirm,
            onDismiss = vm::dismissImport,
        )
    }
}
