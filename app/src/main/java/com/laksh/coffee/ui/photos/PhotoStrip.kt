package com.laksh.coffee.ui.photos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.laksh.coffee.core.timeLabel
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.photos.PhotoKinds
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.components.pressSquash
import com.laksh.coffee.ui.components.rememberLivePulse
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.WellShape

/** Where a new photo comes from. */
enum class PhotoSource { Camera, Library }

internal val PhotoTileSize = 72.dp

/**
 * A row of photo tiles, oldest first, ending in an add tile. [onAdd] null hides the add tile
 * (the owner is full). [adding] draws that many placeholders for photos still being saved.
 * [onCanvas] is true when the strip sits on the page itself rather than in a card, so the empty
 * tiles take the card colour and stay visible.
 */
@Composable
fun PhotoStrip(
    photos: List<PhotoEntity>,
    onAdd: ((PhotoSource, String) -> Unit)?,
    onOpen: (PhotoEntity) -> Unit,
    allowedKinds: List<String>,
    modifier: Modifier = Modifier,
    adding: Int = 0,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onCanvas: Boolean = false,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(photos, key = { it.uid }, contentType = { "photo" }) { photo ->
            PhotoTile(photo = photo, onClick = { onOpen(photo) }, modifier = Modifier.animateItem(), onCanvas = onCanvas)
        }
        items(adding, key = { "adding-$it" }, contentType = { "adding" }) {
            SavingTile(Modifier.animateItem(), onCanvas)
        }
        if (onAdd != null) {
            item(key = "add", contentType = "add") {
                AddTile(
                    kinds = allowedKinds,
                    suggested = suggestedKind(photos, allowedKinds),
                    onAdd = onAdd,
                    modifier = Modifier.animateItem(),
                    onCanvas = onCanvas,
                )
            }
        }
    }
}

/** The first allowed kind this owner has no photo of yet, so bed then cup come up by themselves. */
internal fun suggestedKind(photos: List<PhotoEntity>, kinds: List<String>): String {
    val have = photos.mapTo(HashSet()) { it.kind }
    return kinds.firstOrNull { it !in have } ?: kinds.firstOrNull() ?: PhotoKinds.OTHER
}

/** "Bed photo, 08:14", or what is wrong with it. */
internal fun photoDescription(photo: PhotoEntity, missing: Boolean): String {
    val what = "${PhotoKinds.label(photo.kind)} photo, ${timeLabel(photo.takenAt)}"
    return if (missing) "$what, not on this phone" else what
}

@Composable
fun PhotoTile(
    photo: PhotoEntity,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: Dp = PhotoTileSize,
    showKind: Boolean = true,
    onCanvas: Boolean = false,
) {
    val c = CoffeeTheme.colors
    val image by rememberThumbnail(photo)
    val interaction = remember { MutableInteractionSource() }
    val missing = image is PhotoImage.Missing
    val description = remember(photo.kind, photo.takenAt, missing) { photoDescription(photo, missing) }
    // Fixed, not themed: the label sits on the photo, whatever the photo is.
    val shade = remember { Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.62f)) }

    Box(
        modifier
            .size(size)
            .then(if (onClick != null) Modifier.pressSquash(interaction, 0.96f) else Modifier)
            .clip(WellShape)
            .background(if (onCanvas) c.surface1 else c.surface2)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = description },
    ) {
        when (val img = image) {
            is PhotoImage.Ready -> {
                Image(
                    bitmap = img.bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                if (showKind) {
                    Box(Modifier.fillMaxSize().drawBehind { drawRect(shade) })
                    Text(
                        PhotoKinds.label(photo.kind).uppercase(),
                        style = Micro,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 6.dp),
                    )
                }
            }
            PhotoImage.Missing -> Text(
                "NOT ON THIS PHONE",
                style = Micro,
                color = c.inkLow,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(6.dp),
            )
            PhotoImage.Loading -> Unit
        }
    }
}

@Composable
private fun SavingTile(modifier: Modifier = Modifier, onCanvas: Boolean = false) {
    val c = CoffeeTheme.colors
    val pulse = rememberLivePulse()
    val fill = c.surface3
    Box(
        modifier
            .size(PhotoTileSize)
            .clip(WellShape)
            .background(if (onCanvas) c.surface1 else c.surface2)
            .drawBehind { drawRect(fill, alpha = pulse.value) }
            .semantics { contentDescription = "Saving photo" },
    )
}

@Composable
private fun AddTile(
    kinds: List<String>,
    suggested: String,
    onAdd: (PhotoSource, String) -> Unit,
    modifier: Modifier = Modifier,
    onCanvas: Boolean = false,
) {
    val c = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier
                .size(PhotoTileSize)
                .pressSquash(interaction, 0.96f)
                .clip(WellShape)
                .background(if (onCanvas) c.surface1 else c.surface2)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button) { menu = true }
                .semantics { contentDescription = "Add photo" },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = c.inkMid, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text("ADD", style = Micro, color = c.inkLow)
        }
        AddPhotoMenu(
            expanded = menu,
            onDismiss = { menu = false },
            kinds = kinds,
            suggested = suggested,
            onPick = { source, kind ->
                menu = false
                onAdd(source, kind)
            },
        )
    }
}

/**
 * The small menu behind every "add photo" control: what the photo shows (when there is a choice),
 * then where it comes from. Place it in the same Box as its anchor.
 */
@Composable
fun AddPhotoMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    kinds: List<String>,
    suggested: String,
    onPick: (PhotoSource, String) -> Unit,
) {
    val c = CoffeeTheme.colors
    var kind by remember(suggested, expanded) { mutableStateOf(suggested) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = c.surface1,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(Dp.Hairline, c.hairline),
    ) {
        if (kinds.size > 1) KindChips(kinds = kinds, selected = kind, onSelect = { kind = it })
        DropdownMenuItem(
            text = { Text("Take photo", color = c.inkHigh) },
            onClick = { onPick(PhotoSource.Camera, kind) },
            leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null, tint = c.inkMid) },
        )
        DropdownMenuItem(
            text = { Text("Choose from library", color = c.inkHigh) },
            onClick = { onPick(PhotoSource.Library, kind) },
            leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null, tint = c.inkMid) },
        )
    }
}

@Composable
private fun ColumnScope.KindChips(kinds: List<String>, selected: String, onSelect: (String) -> Unit) {
    Text(
        "WHAT IT SHOWS",
        style = Micro,
        color = CoffeeTheme.colors.inkLow,
        modifier = Modifier.padding(start = 14.dp, top = 6.dp),
    )
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        kinds.forEach { k ->
            SelectableChip(text = PhotoKinds.label(k), selected = k == selected, onClick = { onSelect(k) })
        }
    }
}
