package com.laksh.coffee.ui.photos

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.runtime.key
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.laksh.coffee.core.screenDate
import com.laksh.coffee.core.screenTime
import com.laksh.coffee.ui.components.CoffeeConfirm
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.photos.PhotoKinds
import com.laksh.coffee.data.photos.PhotoPick
import com.laksh.coffee.data.photos.PhotoRepository
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.components.ToggleRow
import com.laksh.coffee.ui.theme.CoffeeTheme
import kotlinx.coroutines.delay

/**
 * One photo, full screen: pinch or double-tap to zoom, say what it shows, caption it, keep it
 * out of analyses, or delete it. Every change is saved as it is made.
 *
 * [kinds] is what this photo may be called (one entry hides the choice). [canSend] is false for
 * photos that are never sent, such as a bag photo.
 */
@Composable
fun PhotoViewerDialog(
    photo: PhotoEntity,
    kinds: List<String>,
    canSend: Boolean,
    onDismiss: () -> Unit,
    onKind: (String) -> Unit,
    onCaption: (String) -> Unit,
    onSendToAi: (Boolean) -> Unit,
    onDelete: () -> Unit,
    position: Int = 0,
    count: Int = 1,
    onStep: (Int) -> Unit = {},
) {
    val c = CoffeeTheme.colors
    val image by rememberFullPhoto(photo)
    var confirmDelete by remember { mutableStateOf(false) }
    val missing = image is PhotoImage.Missing
    // The phone's own date and clock style (v4 §4.15).
    val title = remember(photo.kind, photo.takenAt) {
        "${PhotoKinds.label(photo.kind)} · ${screenDate(photo.takenAt)} ${screenTime(photo.takenAt)}"
    }
    val many = count > 1
    val keys = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Holds focus so Left/Right step through the photos and Esc closes (v4 §3.13).
        LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }
        Column(
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .systemBarsPadding()
                .imePadding()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> many.also { if (it) onStep(-1) }
                        Key.DirectionRight -> many.also { if (it) onStep(1) }
                        Key.Escape -> true.also { onDismiss() }
                        else -> false
                    }
                }
                .focusRequester(keys)
                .focusable(),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconWell(Icons.Rounded.Close, "Close", onDismiss, shortcut = "Esc")
                Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = c.inkHigh,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                    if (many) {
                        Text(
                            "${position + 1} of $count",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.inkMid,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                IconWell(Icons.Rounded.Delete, if (missing) "Remove photo" else "Delete photo", { confirmDelete = true }, tint = c.fault)
            }

            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                when (val img = image) {
                    is PhotoImage.Ready -> key(photo.uid) { ZoomablePhoto(img.bitmap, photoDescription(photo, missing = false)) }
                    PhotoImage.Missing -> NotOnThisPhone(onRemove = { confirmDelete = true })
                    PhotoImage.Loading -> Unit
                }
                if (many) {
                    // Always there and focusable, not only on hover: touch and keyboard need them too.
                    IconWell(
                        Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        "Previous photo",
                        { onStep(-1) },
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                        size = 48.dp,
                        enabled = position > 0,
                        shortcut = "Left",
                    )
                    IconWell(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        "Next photo",
                        { onStep(1) },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                        size = 48.dp,
                        enabled = position < count - 1,
                        shortcut = "Right",
                    )
                }
            }

            // Scrolls on its own, so the caption stays reachable above a keyboard on a short screen.
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (kinds.size > 1) {
                    Column {
                        EyebrowLabel("WHAT IT SHOWS")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            kinds.forEach { k ->
                                SelectableChip(
                                    text = PhotoKinds.label(k),
                                    selected = photo.kind == k,
                                    onClick = { onKind(k) },
                                    onCanvas = true,
                                )
                            }
                        }
                    }
                }
                CaptionField(photo = photo, onCaption = onCaption)
                if (canSend) {
                    if (photo.kind in PhotoPick.KINDS) {
                        ToggleRow(
                            label = "Send with analyses",
                            checked = photo.sendToAi,
                            onChange = onSendToAi,
                            subtitle = "Goes with an analysis of this brew when the model can see images. " +
                                "Off keeps it on the phone.",
                        )
                    } else {
                        Text(
                            "Only bed and cup photos go with an analysis. This one stays on the phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.inkMid,
                        )
                    }
                }
            }
        }

        if (confirmDelete) {
            // "Keep it" takes focus first, so Enter never deletes a photo by accident.
            CoffeeConfirm(
                title = if (missing) "Remove this photo?" else "Delete this photo?",
                body = if (missing) "Its file is not on this phone, so only the empty tile goes." else "It can't be undone.",
                confirmLabel = if (missing) "Remove" else "Delete",
                dismissLabel = "Keep it",
                onConfirm = {
                    confirmDelete = false
                    onDelete()
                },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}

@Composable
private fun CaptionField(photo: PhotoEntity, onCaption: (String) -> Unit) {
    // Text of its own, keyed on the photo: a save coming back from Room never moves the cursor.
    var text by rememberSaveable(photo.uid) { mutableStateOf(photo.caption) }
    val latestText by rememberUpdatedState(text)
    val save by rememberUpdatedState(onCaption)
    val stored by rememberUpdatedState(photo.caption)
    // Saved shortly after the last keystroke, and once more when the viewer closes mid-word.
    LaunchedEffect(text) {
        if (text.trim() == stored) return@LaunchedEffect
        delay(400)
        save(text)
    }
    DisposableEffect(photo.uid) {
        onDispose { if (latestText.trim() != stored) save(latestText) }
    }
    FieldWell(
        label = "CAPTION",
        value = text,
        onChange = { text = it.take(PhotoRepository.MAX_CAPTION) },
        modifier = Modifier.fillMaxWidth(),
        placeholder = "What should you remember about it?",
        onCanvas = true,
    )
}

@Composable
private fun NotOnThisPhone(onRemove: () -> Unit) {
    val c = CoffeeTheme.colors
    Column(
        Modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Not on this phone", style = MaterialTheme.typography.titleLarge, color = c.inkHigh)
        Spacer(Modifier.height(6.dp))
        Text(
            "The log remembers this photo, but its file is not here. That happens after a restore " +
                "that brought the log without the pictures.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.inkMid,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        SecondaryButton("Remove", onRemove, modifier = Modifier.width(160.dp), onCanvas = true)
    }
}

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Pinch, drag and double-tap. Scale and offset are read only in the layer, so a gesture never recomposes. */
@Composable
private fun ZoomablePhoto(bitmap: ImageBitmap, description: String) {
    val scale = remember(bitmap) { mutableFloatStateOf(1f) }
    val offset = remember(bitmap) { mutableStateOf(Offset.Zero) }
    // A plain holder: only the gesture callbacks read it.
    val box = remember { arrayOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = box[0].width * (s - 1f) / 2f
        val maxY = box[0].height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    val transform = rememberTransformableState { zoom, pan, _ ->
        val s = (scale.floatValue * zoom).coerceIn(1f, MAX_ZOOM)
        scale.floatValue = s
        offset.value = clamp(offset.value + pan, s)
    }
    Image(
        bitmap = bitmap,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { box[0] = it }
            .pointerInput(bitmap) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale.floatValue > 1f) {
                            scale.floatValue = 1f
                            offset.value = Offset.Zero
                        } else {
                            scale.floatValue = DOUBLE_TAP_ZOOM
                        }
                    },
                )
            }
            .transformable(transform)
            .graphicsLayer {
                scaleX = scale.floatValue
                scaleY = scale.floatValue
                translationX = offset.value.x
                translationY = offset.value.y
            },
    )
}
