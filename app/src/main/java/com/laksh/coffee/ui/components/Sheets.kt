package com.laksh.coffee.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.LocalWindowClass
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.PillShape
import com.laksh.coffee.ui.theme.WidthClass
import com.laksh.coffee.ui.theme.WindowClass
import kotlinx.coroutines.launch

/**
 * How a sheet presents (v4 §2.4). The role says what the sheet is for; the window class decides
 * whether that is a bottom sheet or a dialog.
 */
enum class SheetRole {
    /** Delete, leave the brew, discard: a short question with a safe and a destructive answer. */
    Confirm,

    /** Something to read: import preview, shortcuts, a glossary entry, an AI report. */
    Content,

    /** A form that saves: bean, grinder, AI provider. A side sheet on wide windows. */
    Editor,

    /** A list to choose from: catalog, brewer filter, grind. */
    Picker,
}

/** The sticky editor header (Cancel, title, Save) and what leaving with unsaved changes asks. */
@Immutable
data class EditorChrome(
    val title: String,
    /** Null hides Save, for an editor that saves as you go. */
    val onSave: (() -> Unit)? = null,
    val saveLabel: String = "Save",
    val canSave: Boolean = true,
    /** Unsaved changes: closing by the scrim, back or Cancel asks first. */
    val dirty: Boolean = false,
    val discardBody: String = DISCARD_COFFEE,
) {
    companion object {
        const val DISCARD_TITLE = "Discard changes?"
        const val DISCARD_COFFEE = "You've changed this coffee and haven't saved it."
        const val KEEP_EDITING = "Keep editing"
        const val DISCARD = "Discard"
    }
}

/** True when [role] shows as a dialog rather than a bottom sheet at this window class. Pure. */
fun SheetRole.presentsAsDialog(wc: WindowClass): Boolean {
    // A short window that is at least medium wide has no room for a bottom sheet to rise into.
    val shortWide = wc.isShort && wc.width != WidthClass.Compact
    return when (this) {
        SheetRole.Confirm -> wc.width != WidthClass.Compact
        SheetRole.Content, SheetRole.Editor, SheetRole.Picker -> wc.usesPanes || shortWide
    }
}

/**
 * The one sheet. On phones it is a bottom sheet: surface1 with a 28dp top radius, no tonal tint,
 * a quiet handle, content padded clear of the navigation bar. On wide windows [role] turns it into
 * a centred dialog (confirm, content, picker) or a full-height side sheet (editor).
 *
 * The sheet is its own window, so the activity's FLAG_SECURE does not reach it: a sheet that can
 * show a secret passes [properties] with SecureFlagPolicy.SecureOn (the dialog forms honour it).
 *
 * Callers that hide the sheet before acting (`sheetState.hide()` then the action) keep working
 * as a dialog: an unattached state hides at once.
 */
@Composable
fun CoffeeSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    role: SheetRole = SheetRole.Content,
    editor: EditorChrome? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (role.presentsAsDialog(LocalWindowClass.current)) {
        CoffeeDialog(role, onDismissRequest, modifier, properties, editor, content)
    } else {
        CoffeeBottomSheet(onDismissRequest, modifier, sheetState, properties, editor, content)
    }
}

@Composable
private fun CoffeeBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    sheetState: SheetState,
    properties: ModalBottomSheetProperties,
    editor: EditorChrome?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CoffeeTheme.colors
    val scope = rememberCoroutineScope()
    var askDiscard by remember { mutableStateOf(false) }
    val dirty = editor?.dirty == true
    ModalBottomSheet(
        onDismissRequest = {
            // A swipe or scrim tap on unsaved changes asks; "Keep editing" brings the sheet back.
            if (dirty) askDiscard = true else onDismissRequest()
        },
        modifier = modifier,
        sheetState = sheetState,
        properties = properties,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.surface1,
        contentColor = colors.inkHigh,
        tonalElevation = 0.dp,
        scrimColor = colors.scrim,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(36.dp, 4.dp)
                    .clip(PillShape)
                    .background(colors.inkFaint),
            )
        },
    ) {
        if (editor != null) {
            EditorHeader(editor, onCancel = { if (dirty) askDiscard = true else onDismissRequest() })
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            content = content,
        )
    }
    if (askDiscard && editor != null) {
        DiscardConfirm(
            editor = editor,
            onKeep = {
                askDiscard = false
                scope.launch { runCatching { sheetState.show() } }
            },
            onDiscard = {
                askDiscard = false
                onDismissRequest()
            },
        )
    }
}

@Composable
private fun CoffeeDialog(
    role: SheetRole,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    properties: ModalBottomSheetProperties,
    editor: EditorChrome?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CoffeeTheme.colors
    val wc = LocalWindowClass.current
    val scope = rememberCoroutineScope()
    var askDiscard by remember { mutableStateOf(false) }
    val currentEditor by rememberUpdatedState(editor)
    val dismiss by rememberUpdatedState(onDismissRequest)
    // 0 = gone, 1 = settled. Drives alpha and a transform only (graphicsLayer), never layout.
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.EffectsDefault) }
    val close: () -> Unit = {
        if (currentEditor?.dirty == true) {
            askDiscard = true
        } else {
            scope.launch {
                shown.animateTo(0f, Motion.EffectsFast)
                dismiss()
            }
        }
    }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            dismissOnBackPress = properties.shouldDismissOnBackPress,
            // The scrim below handles outside taps, so unsaved changes can ask first.
            dismissOnClickOutside = false,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Our own scrim fades with the panel; the window's stock dim would double it.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = shown.value }
                    .background(colors.scrim)
                    .pointerInput(properties) {
                        detectTapGestures { if (properties.shouldDismissOnClickOutside) close() }
                    },
            )
            val side = role == SheetRole.Editor
            // A centred panel sits in the space above the keyboard. Without this a picker that opens
            // ready to type stays centred on the whole screen and the keyboard hides its list.
            BoxWithConstraints(Modifier.fillMaxSize().then(if (side) Modifier else Modifier.imePadding())) {
                val maxW = maxWidth
                val maxH = maxHeight

                val shape: Shape = if (side) SideSheetShape else DialogShape
                val panel = when (role) {
                    SheetRole.Editor -> Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(min(560.dp, maxW))
                    SheetRole.Picker -> Modifier
                        .align(Alignment.Center)
                        .width(min(480.dp, maxW - 32.dp))
                        .heightIn(max = min(560.dp, maxH - 48.dp))
                    SheetRole.Confirm -> Modifier
                        .align(Alignment.Center)
                        .width(min(if (wc.usesPanes) 420.dp else 400.dp, maxW - 32.dp))
                    SheetRole.Content -> Modifier
                        .align(Alignment.Center)
                        .width(min(640.dp, maxW - 48.dp))
                        .heightIn(max = maxH * if (wc.isShort) 0.92f else 0.8f)
                }
                val dx = with(LocalDensity.current) { 24.dp.toPx() }
                Column(
                    modifier
                        .then(panel)
                        .graphicsLayer {
                            val p = shown.value
                            alpha = p
                            if (side) {
                                translationX = (1f - p) * dx
                            } else {
                                val s = 0.96f + 0.04f * p
                                scaleX = s
                                scaleY = s
                            }
                        }
                        .clip(shape)
                        .background(colors.surface1)
                        // Taps on the panel's own padding must not fall through to the scrim.
                        .pointerInput(Unit) {}
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                e.key == Key.Escape -> {
                                    close()
                                    true
                                }
                                (e.isCtrlPressed || e.isMetaPressed) && e.key == Key.S -> {
                                    val save = currentEditor?.onSave
                                    if (save != null && currentEditor?.canSave == true) save()
                                    save != null
                                }
                                else -> false
                            }
                        }
                        .then(if (side) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)) else Modifier)
                        .semantics { editor?.title?.let { paneTitle = it } },
                ) {
                    if (editor != null) EditorHeader(editor, onCancel = close)
                    Column(
                        Modifier
                            .then(if (side) Modifier.weight(1f) else Modifier.weight(1f, fill = false))
                            .fillMaxWidth()
                            .padding(horizontal = DialogPadH)
                            .padding(top = if (editor != null) 8.dp else 24.dp, bottom = 20.dp),
                        content = content,
                    )
                }
            }
        }
    }

    if (askDiscard && editor != null) {
        DiscardConfirm(
            editor = editor,
            onKeep = { askDiscard = false },
            onDiscard = {
                askDiscard = false
                onDismissRequest()
            },
        )
    }
}

private val DialogShape = RoundedCornerShape(28.dp)
private val SideSheetShape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
private val DialogPadH: Dp = 24.dp

/** Cancel on the left, the title in the middle, Save on the right; sticks above the form. */
@Composable
private fun EditorHeader(editor: EditorChrome, onCancel: () -> Unit) {
    val colors = CoffeeTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietButton("Cancel", onCancel)
            Text(
                text = editor.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.inkHigh,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).asHeading(),
            )
            val save = editor.onSave
            if (save != null) {
                PrimaryButton(editor.saveLabel, save, height = 40.dp, enabled = editor.canSave)
            } else {
                Spacer(Modifier.width(64.dp))
            }
        }
        HairLine()
    }
}

@Composable
private fun DiscardConfirm(editor: EditorChrome, onKeep: () -> Unit, onDiscard: () -> Unit) {
    CoffeeConfirm(
        title = EditorChrome.DISCARD_TITLE,
        body = editor.discardBody,
        confirmLabel = EditorChrome.DISCARD,
        dismissLabel = EditorChrome.KEEP_EDITING,
        onConfirm = onDiscard,
        onDismiss = onKeep,
    )
}

/**
 * Every "are you sure": a bottom sheet on phones, a centred dialog from medium up (§2.4). The
 * safe answer ([dismissLabel]) takes keyboard focus first, so Enter never deletes anything by
 * accident; Esc and back cancel.
 */
@Composable
fun CoffeeConfirm(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    body: String? = null,
    dismissLabel: String = "Cancel",
    destructive: Boolean = true,
) {
    val colors = CoffeeTheme.colors
    val wide = SheetRole.Confirm.presentsAsDialog(LocalWindowClass.current)
    val safe = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // A frame after the window attaches. Fails quietly in touch mode, where nothing takes focus.
        withFrameNanos { }
        runCatching { safe.requestFocus() }
    }
    CoffeeSheet(onDismissRequest = onDismiss, role = SheetRole.Confirm) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.inkHigh,
            modifier = Modifier
                .padding(top = if (wide) 0.dp else 8.dp)
                .asHeading()
                .semantics { paneTitle = title },
        )
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.inkMid)
        }
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, if (wide) Alignment.End else Alignment.Start),
        ) {
            SecondaryButton(
                text = dismissLabel,
                onClick = onDismiss,
                modifier = (if (wide) Modifier else Modifier.weight(1f)).focusRequester(safe),
            )
            PrimaryButton(
                text = confirmLabel,
                onClick = onConfirm,
                height = 48.dp,
                modifier = if (wide) Modifier else Modifier.weight(1f),
                containerColor = if (destructive) colors.fault else colors.inverse,
                contentColor = if (destructive) MaterialTheme.colorScheme.onError else colors.onInverse,
            )
        }
    }
}
