package com.laksh.coffee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.core.clock
import com.laksh.coffee.core.grams
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.ui.nav.SharedKeys
import com.laksh.coffee.ui.nav.sharedBoundsModifier
import com.laksh.coffee.ui.theme.CardShape
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.LocalUnits
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Num

private val HeaderShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

/**
 * The recipe card, used on the home carousel and in the library. It reads like a flight:
 * who and which brewer, the name, then the route from coffee to water with the ratio under
 * the line. The recipe's own colour survives only as the small identity dot.
 *
 * [sharedKeyPrefix] names the list this card sits in so its header can fly into the
 * detail page; the Home and Recipes lists use different prefixes so they never
 * claim the same key while a tab slides.
 */
@Composable
fun RecipeCard(
    recipe: Recipe,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    sharedKeyPrefix: String? = null,
) {
    val colors = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val nodes = remember(recipe) { recipe.pourNodes() }
    // Temperatures follow the unit you chose; the coffee and water stay in grams, as scales read.
    val units = LocalUnits.current

    Column(
        modifier
            .testTag("recipe-card")
            .pressSquash(interaction, 0.97f)
            .clip(CardShape)
            .background(colors.surface1)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        // The header is the shared element. It carries its own surface1 fill so the flying
        // copy is a card fragment rather than text on transparent. The shared modifier goes
        // first so it measures the header's full bounds.
        Column(
            Modifier
                .then(
                    if (sharedKeyPrefix != null) {
                        sharedBoundsModifier(
                            key = SharedKeys.recipeHeader(sharedKeyPrefix, recipe.id),
                            clip = CardShape,
                        )
                    } else {
                        Modifier
                    },
                )
                .fillMaxWidth()
                .background(colors.surface1, HeaderShape)
                .padding(start = 18.dp, end = 18.dp, top = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The only place a recipe's accent colour appears.
                Box(
                    Modifier
                        .size(if (compact) 6.dp else 8.dp)
                        .clip(CircleShape)
                        .background(Color(recipe.accent)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = recipe.author.toCaps(),
                    style = Micro,
                    color = colors.inkLow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = recipe.brewer.shortName.toCaps(),
                    style = Micro,
                    color = colors.inkLow,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = recipe.name,
                style = if (compact) {
                    MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                color = colors.inkHigh,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp)) {
            RouteRow(
                from = grams(recipe.doseG),
                fromUnit = "g",
                fromLabel = "COFFEE",
                to = grams(recipe.waterG),
                toUnit = "g",
                toLabel = "WATER",
                nodes = nodes,
                midLabel = recipe.ratioLabel,
                style = if (compact) Num.Small else Num.Medium,
                mode = RouteMode.Planned,
            )
            if (compact) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${clock(recipe.totalSec)} · ${units.tempWithUnit(recipe.tempC)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMid,
                    maxLines = 1,
                )
            } else {
                Spacer(Modifier.height(14.dp))
                HairLine()
                Spacer(Modifier.height(4.dp))
                DataGrid(
                    cells = listOf(
                        Cell("TIME", clock(recipe.totalSec)),
                        Cell("TEMP", units.temp(recipe.tempC), units.tempUnit),
                    ),
                    columns = 2,
                    compact = true,
                )
                // The grind is a sentence as often as a word ("Coarse, a step coarser than your
                // usual paper-filter setting"), so it gets the card's width, not a third of it.
                HairLine()
                Spacer(Modifier.height(10.dp))
                EyebrowLabel("GRIND")
                Spacer(Modifier.height(4.dp))
                WordClippedText(
                    text = recipe.grind.ifBlank { "—" },
                    maxLines = 2,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkHigh,
                )
            }
        }
    }
}
