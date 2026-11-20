package com.laksh.coffee.ui.ai

import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.data.ai.AiSettings
import com.laksh.coffee.domain.ai.AiProvider
import com.laksh.coffee.domain.ai.ModelCatalog
import com.laksh.coffee.ui.components.CoffeeSheet
import com.laksh.coffee.ui.components.SheetRole
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.RadioRow
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.SkeletonBar
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.ToggleRow
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.WellShape
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one place to choose a provider, add your own key for it, pick a model and check that key,
 * plan and model work together. Inline in Gear › App, and inside [AiSettingsSheet] from any
 * analysis card. Crema appears only on the sheet's Save button, the sheet's one primary action.
 */
@Composable
fun AiSettingsSection(
    modifier: Modifier = Modifier,
    inSheet: Boolean = false,
    vm: AiSettingsViewModel = viewModel(factory = AiSettingsViewModel.Factory),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val c = CoffeeTheme.colors
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showProviders by rememberSaveable { mutableStateOf(false) }
    val settings = s.settings
    val provider = s.provider
    val headline = s.headline

    // A shown key must not outlive the screen: hide it when the app goes to the background, and
    // keep it out of screenshots and the Recents thumbnail while it is on screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.hideKey() }
    SecureWindow(active = s.revealedKey != null || (s.keyVisible && s.keyDraft.isNotEmpty()))

    SoftCard(
        modifier.fillMaxWidth(),
        // Inside the sheet the sheet is the card, so only a little air is added.
        contentPadding = if (inSheet) PaddingValues(vertical = 4.dp) else PaddingValues(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EyebrowLabel("AI ANALYSIS")
            Spacer(Modifier.weight(1f))
            Text(
                settings.host.uppercase(),
                style = Micro,
                color = c.inkLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        StatusLine(headline.status, headline.lead, detail = headline.detail, live = headline.live)
        s.testedNote?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = aiText(12, 16), color = c.inkLow)
        }
        Spacer(Modifier.height(16.dp))
        HairLine()
        Spacer(Modifier.height(16.dp))

        ProviderField(row = s.providers.firstOrNull { it.selected }, onClick = { showProviders = true })

        if (provider == null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Use your own key from any of ${s.providers.size} providers, or any OpenAI-compatible " +
                    "service. The provider bills you directly; a brew read is usually well under a cent.",
                style = aiText(13, 18),
                color = c.inkMid,
            )
        } else {
            if (provider.isCustom) {
                Spacer(Modifier.height(12.dp))
                CustomAddress(s, vm)
            }

            Spacer(Modifier.height(16.dp))
            if (s.showKeyField) {
                KeyEntry(s, inSheet, vm, onPaste = { vm.pasteKey(readClipboard(context)) })
            } else {
                SavedKey(s, vm)
            }
            if (provider.keyHint.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(provider.keyHint, style = aiText(12, 16), color = c.inkMid)
            }
            if (provider.keyUrl.isNotBlank()) {
                // Opens the provider's own key page in the browser. The app itself sends nothing.
                QuietButton(
                    "Get a key",
                    { runCatching { uri.openUri(provider.keyUrl) } },
                    Modifier.offset(x = (-8).dp),
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                )
            } else {
                Spacer(Modifier.height(6.dp))
            }
            Text(
                "Encrypted with this phone's key store. Never exported, never in a URL.",
                style = aiText(12, 16),
                color = c.inkLow,
            )

            Spacer(Modifier.height(16.dp))
            EyebrowLabel("MODEL")
            Spacer(Modifier.height(8.dp))
            ModelList(
                s = s,
                provider = provider,
                vm = vm,
                onBrowse = {
                    showModels = true
                    vm.loadModels()
                },
            )

            if (settings.model.isNotBlank() && !settings.modelKnown) {
                ToggleRow(
                    label = "This model can see images",
                    checked = settings.visionOverride == true,
                    onChange = vm::setSeesImages,
                    subtitle = "The app doesn't know this model. Turn this on only if its maker says it takes images.",
                )
            }
            if (settings.modelSeesImages) {
                ToggleRow(
                    label = "Send photos with analyses",
                    checked = settings.sendPhotos,
                    onChange = vm::setSendPhotos,
                    subtitle = "Up to 3 photos of the brew you're analysing. Untick any photo to keep it out.",
                )
            }

            Spacer(Modifier.height(16.dp))
            SecondaryButton(
                "Test connection",
                vm::testConnection,
                Modifier.fillMaxWidth(),
                enabled = settings.ready && s.test !is TestUi.Running,
            )
            when (val t = s.test) {
                // The headline above already shows the title; this is the plain-language why.
                is TestUi.Failed -> {
                    Spacer(Modifier.height(8.dp))
                    Text(t.error.message, style = aiText(13, 18), color = c.inkMid)
                }
                is TestUi.Running -> {
                    Spacer(Modifier.height(8.dp))
                    ElapsedLine(t.sinceMs, prefix = "Waiting for ${settings.modelLabel} · ")
                }
                else -> Unit
            }
        }

        Spacer(Modifier.height(16.dp))
        HairLine()
        Spacer(Modifier.height(12.dp))
        Text(footnote(settings, provider), style = aiText(12, 17), color = c.inkLow)
    }

    if (showProviders) {
        ProviderPickerSheet(
            rows = s.providers,
            leftOut = s.leftOut,
            onPick = vm::selectProvider,
            onDismiss = { showProviders = false },
        )
    }
    if (showModels) {
        ModelPickerSheet(s, vm, onDismiss = { showModels = false })
    }
}

/** The settings card in a sheet, opened from any analysis card's Add key or AI settings. */
@Composable
fun AiSettingsSheet(onDismiss: () -> Unit) {
    val c = CoffeeTheme.colors
    // The sheet is its own window, which the activity's FLAG_SECURE never reaches, and it can
    // show the key (Show key, or the eye on a pasted one). So the sheet is always secure.
    CoffeeSheet(
        onDismissRequest = onDismiss,
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn, shouldDismissOnBackPress = true),
        // A form: a side sheet at expanded and up (v4 §2.4). It saves as you go, so no header.
        role = SheetRole.Editor,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("AI analysis", style = MaterialTheme.typography.headlineSmall, color = c.inkHigh)
            Spacer(Modifier.height(12.dp))
            // The card behind flips to Ready on its own through its flow once the key is saved.
            // Its own key: the tabs now share the activity's ViewModel store, and the sheet must
            // not share drafts with Gear's App section.
            AiSettingsSection(
                inSheet = true,
                vm = viewModel(key = "ai-settings-sheet", factory = AiSettingsViewModel.Factory),
            )
            Spacer(Modifier.height(8.dp))
            QuietButton("Done", onDismiss, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

// ------------------------------------------------------------------------- key

@Composable
private fun KeyEntry(s: AiSettingsUi, inSheet: Boolean, vm: AiSettingsViewModel, onPaste: () -> Unit) {
    val canSave = s.keyDraft.isNotBlank()
    FieldWell(
        label = "API KEY",
        // vm.typed, not s: the field needs its own text back in the same frame (see Typed).
        value = vm.typed.key,
        onChange = vm::setKeyDraft,
        placeholder = "Paste your ${s.settings.providerName.substringBefore(" (")} key",
        isPassword = true,
        passwordVisible = s.keyVisible,
        onTogglePassword = vm::toggleKeyVisible,
        trailing = {
            IconWell(
                Icons.Rounded.ContentPaste,
                "Paste",
                onClick = onPaste,
                size = 32.dp,
                onCanvas = false,
                iconSize = 18.dp,
            )
        },
        error = s.keyError,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (canSave) vm.saveKeyAndTest() }),
    )
    // Says out loud which server the key will be bound to, since a typed but unsaved address
    // counts (Save and test saves it first).
    if (s.keyTargetHost.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Will be saved for ${s.keyTargetHost}",
            style = aiText(12, 16),
            color = CoffeeTheme.colors.inkLow,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (inSheet) {
            PrimaryButton(
                "Save and test",
                vm::saveKeyAndTest,
                Modifier.weight(1f),
                icon = Icons.Rounded.Key,
                height = 52.dp,
                enabled = canSave,
            )
        } else {
            SecondaryButton(
                "Save and test",
                vm::saveKeyAndTest,
                Modifier.weight(1f),
                icon = Icons.Rounded.Key,
                enabled = canSave,
            )
        }
        if (s.replacingKey && s.settings.hasKey) {
            Spacer(Modifier.width(8.dp))
            QuietButton("Cancel", vm::cancelReplace)
        }
    }
}

@Composable
private fun SavedKey(s: AiSettingsUi, vm: AiSettingsViewModel) {
    val c = CoffeeTheme.colors
    val revealed = s.revealedKey
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            EyebrowLabel("API KEY")
            Spacer(Modifier.height(4.dp))
            Text(
                "•••• •••• ${s.settings.keyHint}",
                style = Num.Inline,
                color = c.inkHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconWell(
            if (revealed != null) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            if (revealed != null) "Hide key" else "Show key",
            vm::toggleRevealSaved,
            size = 36.dp,
            onCanvas = false,
        )
        Spacer(Modifier.width(4.dp))
        QuietButton("Replace", vm::startReplace)
        QuietButton("Remove", vm::removeKey, color = c.fault)
    }
    if (revealed != null) {
        Spacer(Modifier.height(8.dp))
        // The whole key, so it can be checked against the provider's dashboard.
        Box(
            Modifier
                .fillMaxWidth()
                .clip(WellShape)
                .background(c.surface2)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                revealed,
                style = Num.Inline.copy(fontSize = 13.sp),
                color = c.inkHigh,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("Hides again after 30 s, or when you leave the app.", style = aiText(12, 16), color = c.inkLow)
    }
}

// ----------------------------------------------------------------------- model

@Composable
private fun ModelList(s: AiSettingsUi, provider: AiProvider, vm: AiSettingsViewModel, onBrowse: () -> Unit) {
    val c = CoffeeTheme.colors
    val settings = s.settings
    provider.models.forEach { m ->
        RadioRow(
            title = m.label,
            selected = settings.model == m.id,
            onClick = { vm.selectModel(m.id) },
            subtitle = m.why.ifBlank { m.id },
            // The price on a line of its own, so a long reason can never push it into a ragged wrap.
            detail = m.price?.let { ModelCatalog.priceLine(it) },
            badge = if (m.isDefault) "DEFAULT" else null,
            // Neutral: seeing photos is a fact about the model, not a verdict.
            note = if (m.vision) "Sees photos" else null,
        )
    }
    val other = settings.model.isNotBlank() && !settings.modelKnown
    RadioRow(
        title = "Use another model id",
        selected = other,
        onClick = vm::openCustom,
        subtitle = if (other) settings.model else "Any id ${provider.name.substringBefore(" (")} offers",
    )
    AnimatedVisibility(
        visible = s.customOpen,
        enter = expandVertically(Motion.size()) + fadeIn(Motion.decel(200)),
        exit = shrinkVertically(Motion.size()) + fadeOut(Motion.decel(140)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            FieldWell(
                label = "MODEL ID",
                value = vm.typed.customModel,
                onChange = vm::setCustomModelDraft,
                modifier = Modifier.weight(1f),
                placeholder = provider.defaultModel?.id ?: "vendor/model-name",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { vm.commitCustomModel() }),
            )
            SecondaryButton(
                "Use",
                vm::commitCustomModel,
                height = 52.dp,
                enabled = s.customModelDraft.isNotBlank(),
            )
        }
    }
    if (s.canBrowse) {
        QuietButton("Browse all models", onBrowse, icon = Icons.Rounded.ChevronRight)
    }
    Text(
        when {
            provider.models.any { it.price != null } ->
                "Prices per 1M tokens${checkedOn(provider)?.let { ", checked $it" }.orEmpty()}. " +
                    "A typical brew read is a few thousand tokens."
            else -> "Model ids differ between providers. Browse the list or type one. Prices are not known here, " +
                "so the app shows token counts without a cost."
        },
        style = aiText(12, 17),
        color = c.inkLow,
    )
}

@Composable
private fun ModelPickerSheet(s: AiSettingsUi, vm: AiSettingsViewModel, onDismiss: () -> Unit) {
    val c = CoffeeTheme.colors
    val shown = remember(s.models, s.modelQuery) { s.filteredModels }
    CoffeeSheet(onDismissRequest = onDismiss, role = SheetRole.Picker) {
        Text("All models", style = MaterialTheme.typography.headlineSmall, color = c.inkHigh)
        Spacer(Modifier.height(4.dp))
        Text(
            "From ${s.settings.host}, as it lists them.",
            style = aiText(13, 18),
            color = c.inkMid,
        )
        Spacer(Modifier.height(12.dp))
        FieldWell(
            label = null,
            value = vm.typed.modelQuery,
            onChange = vm::setModelQuery,
            placeholder = "Search models",
            leadingIcon = Icons.Rounded.Search,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        )
        Spacer(Modifier.height(12.dp))
        when (val m = s.models) {
            is ModelsUi.Failed -> {
                StatusLine(Status.Fault, m.error.title)
                Spacer(Modifier.height(4.dp))
                Text(m.error.message, style = aiText(13, 18), color = c.inkMid)
                Spacer(Modifier.height(12.dp))
                SecondaryButton("Try again", vm::loadModels)
            }
            is ModelsUi.Loaded -> {
                if (shown.isEmpty()) {
                    Text(
                        if (m.models.isEmpty()) "This address lists no chat models." else "No model matches “${s.modelQuery.trim()}”.",
                        style = aiText(14, 20),
                        color = c.inkMid,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                        items(shown, key = { it.id }, contentType = { "model" }) { r ->
                            RadioRow(
                                title = r.name,
                                selected = r.id == s.settings.model,
                                onClick = {
                                    vm.selectModel(r.id, seesImages = r.vision)
                                    onDismiss()
                                },
                                subtitle = r.id + (r.contextLength?.let { " · ${it / 1000}k" } ?: ""),
                                detail = r.price?.let { ModelCatalog.priceLine(it) },
                                badge = if (r.suggested) "SUGGESTED" else null,
                                note = if (r.vision == true) "Sees photos" else null,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
            else -> Column(Modifier.fillMaxWidth()) {
                repeat(3) {
                    SkeletonBar(1f, 44.dp)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------- custom address

/** Custom only: the service's own https address. Presets always use the catalog's. */
@Composable
private fun CustomAddress(s: AiSettingsUi, vm: AiSettingsViewModel) {
    val c = CoffeeTheme.colors
    Column(Modifier.fillMaxWidth()) {
        // Saved only on Done or the Save tap, never on focus loss, so a key pasted here by
        // mistake is refused before anything is written to disk.
        FieldWell(
            label = "BASE URL",
            value = vm.typed.baseUrl ?: s.baseUrlDraft,
            onChange = vm::setBaseUrlDraft,
            error = s.baseUrlError,
            placeholder = "https://api.example.com/v1",
            helper = "Any OpenAI-compatible API root, usually ending in /v1. https only.",
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { vm.commitBaseUrl() }),
        )
        if (s.baseUrlEdited) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                "Save address",
                vm::commitBaseUrl,
                height = 40.dp,
                enabled = s.baseUrlDraft.isNotBlank(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "A saved key is only ever sent to the address it was saved for. A new address needs its own key.",
            style = aiText(12, 17),
            color = c.inkLow,
        )
    }
}

// ---------------------------------------------------------------------- helpers

/** "17 Nov 2026" from the catalog's pricesCheckedOn, or null when it isn't a date. */
private fun checkedOn(provider: AiProvider): String? = runCatching {
    LocalDate.parse(provider.pricesCheckedOn).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
}.getOrNull()

private fun footnote(settings: AiSettings, provider: AiProvider?): String {
    if (provider == null) {
        return "The app goes online only when you tap a button that runs an analysis or a connection test, " +
            "or Browse all models, and only to the provider you choose. Until a key is saved, nothing is sent."
    }
    val host = settings.host.ifBlank { "the address above" }
    val photos = if (settings.modelSeesImages && settings.sendPhotos) {
        ", and up to 3 photos of that brew"
    } else {
        ""
    }
    val plan = if (settings.isCommandCode) {
        " CommandCode's Provider API needs their \$15/month Provider plan; the coding plans don't include it."
    } else {
        ""
    }
    val prices = if (provider.models.any { it.price != null }) {
        checkedOn(provider)?.let { " Prices checked $it." }.orEmpty()
    } else {
        ""
    }
    return "Your key is encrypted on this phone and only ever sent to $host. The app goes online only when " +
        "you tap a button that runs an analysis or a connection test (Analyse, Save & analyse, Find patterns, " +
        "Re-run, Try again, Save and test, Test connection) or Browse all models, and only to $host. " +
        "An analysis sends the brews it reads: recipe, coffee, grinder, water, grind, times, bed, taste and " +
        "your notes$photos." + plan + prices
}

/** Read only when Paste is tapped; nothing here watches the clipboard. */
private fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.coerceToText(context)?.toString()
}

/** FLAG_SECURE while a key is on screen, so it can't be captured in a screenshot or Recents. */
@Composable
private fun SecureWindow(active: Boolean) {
    val activity = LocalActivity.current
    DisposableEffect(activity, active) {
        val window = activity?.window
        if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (active) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
