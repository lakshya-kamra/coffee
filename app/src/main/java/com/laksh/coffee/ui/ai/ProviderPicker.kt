package com.laksh.coffee.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.laksh.coffee.ui.components.CoffeeSheet
import com.laksh.coffee.ui.components.SheetRole
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.RadioRow
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusChip
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.WellShape

/**
 * The chosen provider as one tappable row: its name and, in a word and a colour, whether a key
 * is saved for it. Opens [ProviderPickerSheet]. With nothing chosen yet it asks for a choice.
 */
@Composable
internal fun ProviderField(row: ProviderRow?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(WellShape)
            .background(c.surface2)
            .clickable(role = Role.Button, onClickLabel = "Choose a provider", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            EyebrowLabel("PROVIDER")
            Spacer(Modifier.height(2.dp))
            Text(
                row?.name ?: "Choose who answers",
                style = MaterialTheme.typography.titleMedium,
                color = if (row != null) c.inkHigh else c.inkMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row != null) {
            Spacer(Modifier.width(8.dp))
            StatusChip(row.status, row.word.uppercase())
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = c.inkLow)
    }
}

/**
 * Every provider a phone can reach, in catalog order. Each row says whether a key is saved for
 * it; tapping one makes it the provider and closes the sheet. Nothing here touches the network.
 */
@Composable
internal fun ProviderPickerSheet(
    rows: List<ProviderRow>,
    leftOut: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CoffeeTheme.colors
    CoffeeSheet(onDismissRequest = onDismiss, role = SheetRole.Picker) {
        Text("Provider", style = MaterialTheme.typography.headlineSmall, color = c.inkHigh)
        Spacer(Modifier.height(4.dp))
        Text(
            "You bring the key, the provider bills you directly. Each provider keeps its own key and model here.",
            style = aiText(13, 18),
            color = c.inkMid,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(rows, key = { it.id }, contentType = { "provider" }) { row ->
                RadioRow(
                    title = row.name,
                    selected = row.selected,
                    onClick = {
                        onPick(row.id)
                        onDismiss()
                    },
                    note = row.word,
                    // "No key" is the usual state, not a verdict, so it stays quiet.
                    noteStatus = row.status.takeIf { it != Status.Neutral },
                    modifier = Modifier.animateItem(),
                )
            }
            if (leftOut.isNotEmpty()) {
                item(key = "left-out", contentType = "note") {
                    Text(
                        leftOutNote(leftOut),
                        style = aiText(12, 17),
                        color = c.inkLow,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** "Ollama and LM Studio run on a computer, not on this phone. To reach one, use Custom with an https URL." */
internal fun leftOutNote(names: List<String>): String {
    // The catalog names carry "(on this computer)", which is the web app's wording.
    val short = names.map { it.substringBefore(" (").trim() }.filter { it.isNotEmpty() }
    val list = when (short.size) {
        0 -> return ""
        1 -> short[0]
        else -> short.dropLast(1).joinToString(", ") + " and " + short.last()
    }
    val verb = if (short.size == 1) "runs" else "run"
    return "$list $verb on a computer, not on this phone. To reach one, use Custom with an https URL."
}
