package com.laksh.coffee.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.laksh.coffee.domain.Glossary
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.GlossaryTerm
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.PillShape

/**
 * Words explained where they appear (v4 §4.5). [Term] is a word with a dotted underline; [TermChip]
 * is the same as a small pill (step kinds). Either opens the glossary entry from
 * shared/glossary.json: a bottom sheet on phones and tablets in portrait, a centred dialog on wide
 * windows (the content role of [CoffeeSheet]).
 *
 * [key] is a glossary id or alias ("drawdown", "TDS"). An unknown key draws the plain [text] with
 * no underline and no tap: a word that cannot be explained should not look explainable.
 */
@Composable
fun Term(
    key: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    /** The small "?" for people new to coffee (experience = new), on a screen's first use of a word. */
    showHint: Boolean = false,
) {
    val term = rememberTerm(key)
    val ink = color.takeOrElse { style.color.takeOrElse { CoffeeTheme.colors.inkHigh } }
    val label = text ?: term?.term ?: key
    if (term == null) {
        Text(label, style = style, color = ink, modifier = modifier)
        return
    }
    var open by rememberSaveable(key) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val dots = CoffeeTheme.colors.inkLow
    Row(
        modifier
            .focusRing(interaction, TermShape)
            .handPointer()
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = "Explain",
                onClick = { open = true },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = style,
            color = ink,
            modifier = Modifier.dottedUnderline(dots),
        )
        if (showHint) {
            Spacer(Modifier.width(4.dp))
            HintDot()
        }
    }
    if (open) TermSheet(term, onDismiss = { open = false })
}

/** A step kind or similar label as a pill that explains itself. See [Term]. */
@Composable
fun TermChip(
    key: String,
    text: String,
    modifier: Modifier = Modifier,
    showHint: Boolean = false,
) {
    val colors = CoffeeTheme.colors
    val term = rememberTerm(key)
    var open by rememberSaveable(key) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .then(if (term != null) Modifier.focusRing(interaction, PillShape) else Modifier)
            .heightIn(min = 22.dp)
            .clip(PillShape)
            .drawBehind { drawRect(colors.surface2) }
            .then(
                if (term != null) {
                    Modifier
                        .handPointer()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "Explain",
                            onClick = { open = true },
                        )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.toCaps(),
            style = Micro,
            color = colors.inkMid,
            maxLines = 1,
            modifier = if (term != null) Modifier.dottedUnderline(colors.inkLow) else Modifier,
        )
        if (showHint && term != null) {
            Spacer(Modifier.width(4.dp))
            HintDot()
        }
    }
    if (open && term != null) TermSheet(term, onDismiss = { open = false })
}

/** The glossary entry: the word, its plain meaning, the longer note and where it comes from. */
@Composable
fun TermSheet(term: GlossaryTerm, onDismiss: () -> Unit) {
    val colors = CoffeeTheme.colors
    val uri = LocalUriHandler.current
    CoffeeSheet(onDismissRequest = onDismiss, role = SheetRole.Content) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            EyebrowLabel("What it means")
            Spacer(Modifier.height(6.dp))
            Text(
                text = term.term,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.inkHigh,
                modifier = Modifier.asHeading(),
            )
            Spacer(Modifier.height(10.dp))
            Text(term.short, style = MaterialTheme.typography.bodyLarge, color = colors.inkHigh)
            if (term.more.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(term.more, style = MaterialTheme.typography.bodyMedium, color = colors.inkMid)
            }
            if (term.sources.isNotEmpty()) {
                SectionLabel("Sources", topGap = 20.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    term.sources.forEach { s ->
                        val linked = s.url.startsWith("https://")
                        val interaction = remember { MutableInteractionSource() }
                        Text(
                            text = if (s.note.isNotBlank()) "${s.who} · ${s.note}" else s.who,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (linked) colors.inkHigh else colors.inkMid,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (linked) {
                                Modifier
                                    .focusRing(interaction, TermShape)
                                    .handPointer()
                                    .clickable(
                                        interactionSource = interaction,
                                        indication = null,
                                        role = Role.Button,
                                        onClickLabel = "Open source",
                                    ) { runCatching { uri.openUri(s.url) } }
                                    .padding(vertical = 6.dp)
                                    .dottedUnderline(colors.inkLow)
                            } else {
                                Modifier.padding(vertical = 6.dp)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            // Inset so the keyboard focus ring is not clipped by the scrolling column.
            SecondaryButton("Got it", onDismiss, modifier = Modifier.fillMaxWidth().padding(4.dp))
        }
    }
}

@Composable
private fun rememberTerm(key: String): GlossaryTerm? = remember(key) {
    runCatching { Catalog.term(key) ?: Glossary.find(Catalog.glossary, key) }.getOrNull()
}

private val TermShape = RoundedCornerShape(4.dp)

/** A 1dp dotted rule just under the text: says "tap for more" without looking like a web link. */
private fun Modifier.dottedUnderline(color: Color): Modifier = drawWithCache {
    val w = 1.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()), 0f)
    val y = size.height - w / 2f
    onDrawBehind { drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = w, pathEffect = dash) }
}

@Composable
private fun HintDot() {
    val colors = CoffeeTheme.colors
    Box(
        Modifier
            .size(14.dp)
            .clip(CircleShape)
            .drawBehind { drawRect(colors.surface3) },
        contentAlignment = Alignment.Center,
    ) {
        Text("?", style = Micro, color = colors.inkMid)
    }
}
