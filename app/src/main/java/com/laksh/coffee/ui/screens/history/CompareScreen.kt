package com.laksh.coffee.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laksh.coffee.CoffeeApp
import com.laksh.coffee.core.screenDate
import com.laksh.coffee.core.screenTime
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.MethodLabels
import com.laksh.coffee.domain.ShotTarget
import com.laksh.coffee.ui.catalog.SheetTitle
import com.laksh.coffee.ui.components.CoffeeSheet
import com.laksh.coffee.ui.components.DetailTopBar
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.QuietPanel
import com.laksh.coffee.ui.components.RadioRow
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.SheetRole
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.asHeading
import com.laksh.coffee.ui.components.brewerFromLabel
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.LocalUnits
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Num

/** Every logged brew, live, for the compare page and its picker. */
@Composable
internal fun rememberAllLogs(): List<BrewLogEntity> {
    val app = LocalContext.current.applicationContext as CoffeeApp
    val logs by app.repository.allLogs.collectAsStateWithLifecycle(initialValue = app.repository.allLogsNow.orEmpty())
    return logs
}

/**
 * Two brews as one table (v4 §3.8): the first brew's column, the second's, and on every row that
 * changed, the change in words. Two columns at every width; on a phone they are just narrow.
 */
@Composable
fun CompareScreen(
    a: Long,
    b: Long,
    onBack: () -> Unit,
    onPickOther: (Long) -> Unit,
) {
    val c = CoffeeTheme.colors
    val logs = rememberAllLogs()
    val app = LocalContext.current.applicationContext as CoffeeApp
    val first = logs.firstOrNull { it.id == a }
    val second = logs.firstOrNull { it.id == b }
    val units = LocalUnits.current
    // Saved, so an open picker survives a rotation.
    var picking by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DetailTopBar(onBack = onBack, title = "Compare")
        if (first == null || second == null) {
            QuietPanel("One of these brews is no longer in the log.", Modifier.padding(horizontal = Dimens.gutter))
            return@Column
        }
        val rows = remember(first, second, units) {
            val recipes = app.repository.recipesNow.orEmpty().associateBy { it.id }
            val grinder = first.grinderId?.let { id -> app.repository.activeGrindersNow?.firstOrNull { it.id == id } }
            val method = (Brewers.byId(first.brewerId) ?: brewerFromLabel(first.brewer))?.method
            BrewCompare.rows(
                first,
                second,
                units = units,
                labels = method?.let(MethodLabels::of) ?: MethodLabels("WATER", "", "TIME", false),
                plannedSec = { l -> recipes[l.recipeId]?.let(ShotTarget::targetSec) },
                lowerIsFiner = grinder?.lowerIsFiner ?: true,
                date = { "${screenDate(it)} · ${screenTime(it)}" },
            )
        }
        Column(Modifier.padding(horizontal = Dimens.gutter).widthIn(max = 960.dp)) {
            Text("Two brews", style = MaterialTheme.typography.headlineMedium, color = c.inkHigh, modifier = Modifier.asHeading())
            Spacer(Modifier.height(4.dp))
            Text(
                "The words on the right say how the second brew differs from the first.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.inkMid,
            )
            Spacer(Modifier.height(16.dp))
            SoftCard(Modifier.fillMaxWidth()) {
                Row {
                    Spacer(Modifier.weight(0.8f))
                    EyebrowLabel("FIRST", Modifier.weight(1f))
                    EyebrowLabel("SECOND", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                rows.forEachIndexed { i, row ->
                    if (i > 0) HairLine()
                    CompareLine(row)
                }
            }
            Spacer(Modifier.height(16.dp))
            SecondaryButton("Compare with another brew", { picking = true }, icon = Icons.Rounded.CompareArrows, onCanvas = true)
            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (picking && first != null) {
        CompareWithPicker(base = first, logs = logs, current = b, onPick = { picking = false; onPickOther(it) }, onDismiss = { picking = false })
    }
}

@Composable
private fun CompareLine(row: CompareRow) {
    val c = CoffeeTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${row.label.lowercase()}: first ${row.a}, second ${row.b}" + (row.diff?.let { ", $it" } ?: "")
            },
        verticalAlignment = Alignment.Top,
    ) {
        Text(row.label, style = Micro, color = c.inkLow, modifier = Modifier.weight(0.8f).padding(top = 3.dp))
        Text(row.a, style = MaterialTheme.typography.bodyMedium, color = c.inkMid, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(row.b, style = MaterialTheme.typography.bodyMedium, color = c.inkHigh)
            if (row.diff != null) {
                Text(row.diff, style = Num.Inline, color = c.inkHigh, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/**
 * "Compare with…": the same recipe first, then the same coffee, then the rest (§3.8), as a
 * picker (a sheet on phones, a dialog when wide).
 */
@Composable
internal fun CompareWithPicker(
    base: BrewLogEntity,
    logs: List<BrewLogEntity>,
    current: Long?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CoffeeTheme.colors
    val ordered = remember(base, logs) { BrewCompare.pickerOrder(base, logs) }
    CoffeeSheet(onDismissRequest = onDismiss, role = SheetRole.Picker) {
        SheetTitle("Compare with…")
        if (ordered.isEmpty()) {
            Text("This is the only brew in the log so far.", style = MaterialTheme.typography.bodyMedium, color = c.inkMid)
            return@CoffeeSheet
        }
        Box(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            LazyColumn(Modifier.fillMaxWidth().selectableGroup()) {
                var group: String? = null
                ordered.forEach { l ->
                    val g = BrewCompare.pickerGroup(base, l)
                    if (g != group) {
                        group = g
                        item(key = "h-$g") {
                            EyebrowLabel(g, Modifier.padding(top = 12.dp, bottom = 6.dp).asHeading())
                        }
                    }
                    item(key = l.id) {
                        RadioRow(
                            title = l.recipeName.ifBlank { "Brew" },
                            subtitle = listOfNotNull(
                                "${screenDate(l.startedAt)} · ${screenTime(l.startedAt)}",
                                l.beanLabel.takeIf { it.isNotBlank() },
                                l.overall.takeIf { it > 0 }?.let { "$it/10" },
                            ).joinToString(" · "),
                            selected = l.id == current,
                            onClick = { onPick(l.id) },
                        )
                    }
                }
            }
        }
    }
}
