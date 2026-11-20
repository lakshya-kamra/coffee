package com.laksh.coffee.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.laksh.coffee.data.backup.ImportOptions
import com.laksh.coffee.data.backup.ImportPreview
import com.laksh.coffee.data.backup.ImportResult
import com.laksh.coffee.data.backup.ImportState
import com.laksh.coffee.domain.backup.MergeMode
import com.laksh.coffee.ui.catalog.SheetNote
import com.laksh.coffee.ui.catalog.SheetTitle
import com.laksh.coffee.ui.components.CoffeeSheet
import com.laksh.coffee.ui.components.SheetRole
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.SegmentedControl
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusChip
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.ToggleRow
import com.laksh.coffee.ui.theme.CoffeeTheme

/**
 * What the picked file holds, how to bring it in, and then what happened. Nothing on the phone
 * changes until Import is tapped; "Replace everything" asks once more, in words, first.
 */
@Composable
fun ImportPreviewSheet(
    state: ImportState,
    onConfirm: (ImportOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val importing = state is ImportState.Importing
    // An import under way cannot be swiped away: the sheet is where its result will appear.
    val locked by rememberUpdatedState(importing)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !locked },
    )
    // Something to read before acting: the content role (a centred dialog when wide, v4 §2.4).
    CoffeeSheet(onDismissRequest = { if (!importing) onDismiss() }, sheetState = sheetState, role = SheetRole.Content) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SheetTitle("Restore from a backup")
            Spacer(Modifier.height(8.dp))
            // One call site for both states, so the choices made in the preview are still
            // shown (and still true) while the import runs.
            val preview = (state as? ImportState.Ready)?.preview ?: (state as? ImportState.Importing)?.preview
            if (preview != null) Preview(preview, importing = importing, onConfirm = onConfirm)
            when (state) {
                is ImportState.Done -> Result(state.result, onDismiss)
                is ImportState.Failed -> {
                    StatusLine(Status.Fault, "Not imported", detail = null)
                    Spacer(Modifier.height(6.dp))
                    Body(BackupCopy.errorText(state.error))
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(text = "Close", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = CoffeeTheme.colors.inkHigh)
}

@Composable
private fun Preview(p: ImportPreview, importing: Boolean, onConfirm: (ImportOptions) -> Unit) {
    var mode by remember { mutableIntStateOf(0) }
    var bringSettings by remember { mutableStateOf(false) }
    var bringAi by remember { mutableStateOf(false) }
    var confirmingReplace by remember { mutableStateOf(false) }
    val replace = mode == 1
    val options = ImportOptions(
        mode = if (replace) MergeMode.Replace else MergeMode.Add,
        bringSettings = bringSettings && p.hasSettings,
        bringAi = bringAi && p.hasAi,
    )

    Body(BackupCopy.previewLine(p))
    BackupCopy.previewNotes(p).forEach { note ->
        Spacer(Modifier.height(6.dp))
        SheetNote(note)
    }

    Spacer(Modifier.height(16.dp))
    SegmentedControl(
        options = listOf("Add to this phone", "Replace everything"),
        selectedIndex = mode,
        onSelect = {
            if (!importing) {
                mode = it
                confirmingReplace = false
            }
        },
        modifier = Modifier.fillMaxWidth(),
        onCard = true,
    )
    Spacer(Modifier.height(8.dp))
    if (replace) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(Status.Fault, "Deletes")
            Spacer(Modifier.width(8.dp))
            SheetNote("Everything on this phone now is deleted first.")
        }
    } else {
        SheetNote("What is already here stays. A record that is in both is replaced only when the backup's copy is newer.")
    }

    Spacer(Modifier.height(8.dp))
    ToggleRow(
        label = "Also bring settings",
        checked = bringSettings && p.hasSettings,
        onChange = { if (!importing && p.hasSettings) bringSettings = it },
        subtitle = if (p.hasSettings) "Units, theme, your brewers and defaults" else "This backup has no settings",
    )
    HairLine()
    ToggleRow(
        label = "Also bring AI provider and model",
        checked = bringAi && p.hasAi,
        onChange = { if (!importing && p.hasAi) bringAi = it },
        subtitle = BackupCopy.aiLine(p),
    )

    Spacer(Modifier.height(16.dp))
    if (confirmingReplace && !importing) {
        Body(BackupCopy.REPLACE_CONFIRM)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(text = "Cancel", onClick = { confirmingReplace = false }, modifier = Modifier.weight(1f), height = 56.dp)
            // Red with a word on it: the one destructive action in the app's data screens.
            PrimaryButton(
                text = "Replace everything",
                onClick = { onConfirm(options) },
                modifier = Modifier.weight(1f),
                containerColor = CoffeeTheme.colors.fault,
                contentColor = CoffeeTheme.colors.bg,
            )
        }
    } else {
        PrimaryButton(
            text = if (importing) "Importing…" else "Import",
            onClick = { if (replace) confirmingReplace = true else onConfirm(options) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !importing && p.counts.total > 0,
        )
    }
}

@Composable
private fun Result(r: ImportResult, onDismiss: () -> Unit) {
    StatusLine(Status.Good, "Done", detail = null)
    Spacer(Modifier.height(6.dp))
    Body(BackupCopy.resultLine(r))
    r.skipped.forEach { note ->
        Spacer(Modifier.height(6.dp))
        SheetNote("$note.")
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(text = "Close", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
}
