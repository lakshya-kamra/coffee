package com.laksh.coffee.ui.photos

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.photos.PhotoKinds
import com.laksh.coffee.data.photos.PhotoOwner
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.SectionLabel
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.LocalWindowClass
import com.laksh.coffee.ui.theme.Motion
import kotlinx.coroutines.delay

@Composable
private fun photosViewModel(owner: PhotoOwner): PhotosViewModel =
    viewModel(key = PhotosViewModel.key(owner), factory = PhotosViewModel.factory(owner))

/**
 * Opens the camera or the system photo picker and hands the result to [vm]. No permission is
 * asked for: the picker returns only what the person chose, and the camera app writes one file
 * the app lent it. What is pending is saveable, so a capture survives the activity being
 * recreated behind the camera.
 */
@Composable
internal fun rememberPhotoAdder(vm: PhotosViewModel): (PhotoSource, String) -> Unit {
    var pendingKind by rememberSaveable { mutableStateOf(PhotoKinds.OTHER) }
    var pendingCamera by rememberSaveable { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addFromLibrary(uri, pendingKind)
    }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val name = pendingCamera
        pendingCamera = null
        if (name != null) {
            if (saved) vm.addFromCamera(name, pendingKind) else vm.discardCamera(name)
        }
    }

    return remember(vm, pick, take) {
        add@{ source: PhotoSource, kind: String ->
            if (!vm.canAdd()) return@add
            pendingKind = kind
            when (source) {
                PhotoSource.Library -> try {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (e: ActivityNotFoundException) {
                    vm.noPicker()
                }
                PhotoSource.Camera -> {
                    val target = vm.newCameraTarget() ?: return@add
                    pendingCamera = target.name
                    try {
                        take.launch(target.uri)
                    } catch (e: ActivityNotFoundException) {
                        pendingCamera = null
                        vm.discardCamera(target.name)
                        vm.noCameraApp()
                    }
                }
            }
        }
    }
}

/** The viewer for whichever photo [openUid] names, closing itself if that photo goes away. */
@Composable
private fun ViewerHost(
    vm: PhotosViewModel,
    ui: PhotosUi,
    openUid: String?,
    onClose: () -> Unit,
    kinds: List<String>,
    onStep: (String) -> Unit,
) {
    if (openUid == null) return
    val index = ui.photos.indexOfFirst { it.uid == openUid }
    val photo = ui.photos.getOrNull(index)
    if (photo == null) {
        if (ui.loaded) LaunchedEffect(openUid) { onClose() }
        return
    }
    PhotoViewerDialog(
        photo = photo,
        kinds = kinds,
        canSend = vm.owner is PhotoOwner.Brew,
        position = index,
        count = ui.photos.size,
        // Clamped, so a key held past either end stays on the last photo.
        onStep = { by -> ui.photos.getOrNull((index + by).coerceIn(0, ui.photos.lastIndex))?.let { onStep(it.uid) } },
        onDismiss = onClose,
        onKind = { vm.setKind(photo, it) },
        onCaption = { vm.setCaption(photo, it) },
        onSendToAi = { vm.setSendToAi(photo, it) },
        onDelete = {
            onClose()
            vm.delete(photo)
        },
    )
}

/** The last thing that did not work, in words, clearing itself after a few seconds. */
@Composable
private fun PhotoMessage(message: String?, onShown: () -> Unit, modifier: Modifier = Modifier) {
    // Holds the words while the line folds away, so it never collapses around empty text.
    // A plain holder, not state: it is only ever read in the same pass that writes it.
    val shown = remember { arrayOf("") }
    if (message != null) shown[0] = message
    LaunchedEffect(message) {
        if (message != null) {
            delay(5_000)
            onShown()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = fadeIn(Motion.decel()) + expandVertically(Motion.size()),
        exit = fadeOut(Motion.decel(140)) + shrinkVertically(Motion.size()),
    ) {
        Text(
            shown[0],
            style = MaterialTheme.typography.bodySmall,
            color = CoffeeTheme.colors.inkMid,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Review's PHOTOS section: the strip is always there, add tile included, because this is the
 * moment the bed is in front of you.
 */
@Composable
fun ReviewPhotos(brewLogId: Long, modifier: Modifier = Modifier) {
    val owner = remember(brewLogId) { PhotoOwner.Brew(brewLogId) }
    val vm = photosViewModel(owner)
    val ui by vm.state.collectAsStateWithLifecycle()
    val add = rememberPhotoAdder(vm)
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val c = CoffeeTheme.colors

    Column(modifier) {
        SectionLabel("PHOTOS")
        SoftCard {
            // A short window has no room for the explainer (v4 §3.6 S): the strip says enough.
            if (!LocalWindowClass.current.isShort) {
                Text(
                    "A photo of the bed says more than any shape on this list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.inkHigh,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Kept on this phone, saved without location or camera details. Bed and cup photos " +
                        "go with an analysis of this brew when your model can see images; open one to keep it out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkMid,
                )
                Spacer(Modifier.height(12.dp))
            }
            PhotoStrip(
                photos = ui.photos,
                onAdd = add.takeIf { !ui.full },
                onOpen = { open = it.uid },
                allowedKinds = REVIEW_KINDS,
                adding = ui.adding,
            )
            PhotoMessage(ui.message, vm::clearMessage)
        }
    }
    ViewerHost(vm, ui, open, { open = null }, PhotoKinds.FOR_BREW, onStep = { open = it })
}

private val REVIEW_KINDS = listOf(PhotoKinds.BED, PhotoKinds.CUP)

/**
 * Brew detail's photos: a strip under THE BED when the brew has any, otherwise just a quiet
 * "Add photo". [openUid] is hoisted so the analysis card can open one of its thumbnails here.
 */
@Composable
fun BrewDetailPhotos(
    brewLogId: Long,
    openUid: String?,
    onOpen: (String?) -> Unit,
    modifier: Modifier = Modifier,
    gutter: Dp = 16.dp,
) {
    val owner = remember(brewLogId) { PhotoOwner.Brew(brewLogId) }
    val vm = photosViewModel(owner)
    val ui by vm.state.collectAsStateWithLifecycle()
    val add = rememberPhotoAdder(vm)
    var menu by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        if (ui.photos.isNotEmpty() || ui.adding > 0) {
            SectionLabel("PHOTOS", Modifier.padding(horizontal = gutter))
            PhotoStrip(
                photos = ui.photos,
                onAdd = add.takeIf { !ui.full },
                onOpen = { onOpen(it.uid) },
                allowedKinds = PhotoKinds.FOR_BREW,
                adding = ui.adding,
                contentPadding = PaddingValues(horizontal = gutter),
                onCanvas = true,
            )
        } else if (ui.loaded) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.padding(horizontal = (gutter - 8.dp).coerceAtLeast(0.dp))) {
                QuietButton(text = "Add photo", onClick = { menu = true }, icon = Icons.Rounded.AddAPhoto)
                AddPhotoMenu(
                    expanded = menu,
                    onDismiss = { menu = false },
                    kinds = PhotoKinds.FOR_BREW,
                    suggested = PhotoKinds.BED,
                    onPick = { source, kind ->
                        menu = false
                        add(source, kind)
                    },
                )
            }
        }
        PhotoMessage(ui.message, vm::clearMessage, Modifier.padding(horizontal = gutter))
    }
    ViewerHost(vm, ui, openUid, { onOpen(null) }, PhotoKinds.FOR_BREW, onStep = { onOpen(it) })
}

/** The bean editor's bag photo row. Only for a bean that is already saved: a photo needs a row to belong to. */
@Composable
fun BagPhotos(beanId: Long, modifier: Modifier = Modifier) {
    val owner = remember(beanId) { PhotoOwner.Bean(beanId) }
    val vm = photosViewModel(owner)
    val ui by vm.state.collectAsStateWithLifecycle()
    val add = rememberPhotoAdder(vm)
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth()) {
        EyebrowLabel("BAG PHOTO")
        Spacer(Modifier.height(8.dp))
        PhotoStrip(
            photos = ui.photos,
            onAdd = add.takeIf { !ui.full },
            onOpen = { open = it.uid },
            allowedKinds = PhotoKinds.FOR_BEAN,
            adding = ui.adding,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            // "Kept", not "stays": a backup saved with photos included carries it (Gear › App › Your data).
            "The label with the roast date and the lot. It is kept on this phone and is never sent with an analysis.",
            style = MaterialTheme.typography.bodySmall,
            color = CoffeeTheme.colors.inkLow,
        )
        PhotoMessage(ui.message, vm::clearMessage)
    }
    ViewerHost(vm, ui, open, { open = null }, PhotoKinds.FOR_BEAN, onStep = { open = it })
}

/** For callers that show a photo they hold (the analysis card's thumbnails). */
@Composable
fun SmallPhotoTile(photo: PhotoEntity, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    PhotoTile(photo = photo, onClick = onClick, modifier = modifier, size = 44.dp, showKind = false)
}
