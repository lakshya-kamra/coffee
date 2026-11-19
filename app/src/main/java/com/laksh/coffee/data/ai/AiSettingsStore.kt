package com.laksh.coffee.data.ai

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.laksh.coffee.CoffeeApp
import com.laksh.coffee.domain.ai.AiError
import com.laksh.coffee.domain.ai.AiProvider
import com.laksh.coffee.domain.ai.AiProviders
import com.laksh.coffee.domain.ai.AuthStyle
import com.laksh.coffee.domain.ai.BaseUrl
import com.laksh.coffee.domain.ai.BaseUrlCheck
import com.laksh.coffee.domain.ai.ModelCatalog
import com.laksh.coffee.domain.ai.ProviderApi
import com.laksh.coffee.domain.backup.AiDto
import com.laksh.coffee.domain.backup.AiProviderDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

// Its own file, apart from coffee_settings, so the key never rides along in the Settings object
// the whole UI collects. files/datastore is not in the backup rules, so it never leaves the phone.
private val Context.aiStore by preferencesDataStore(
    name = "coffee_ai",
    produceMigrations = { context ->
        listOf(
            AiPrefsMigration(
                upgradedInstall = { (context.applicationContext as? CoffeeApp)?.repository?.isEmpty() == false },
            ),
        )
    },
)

/** What the provider list shows for a saved key: its last four characters and the host it is bound to. */
data class KeyBadge(val hint: String, val host: String)

data class AiSettings(
    /** providers.json id; "" until a provider is chosen (a fresh install). */
    val providerId: String = "",
    val providerName: String = "",
    val api: ProviderApi = ProviderApi.OpenAiChat,
    val auth: AuthStyle = AuthStyle.Bearer,
    /** The provider's address: the catalog's for a preset, the saved one for custom. "" when not set. */
    val baseUrl: String = "",
    val model: String = "",
    /**
     * A key is saved for the active provider AND it was saved for this address's host. A key
     * saved for another host reads as no key here, so it is never sent anywhere but where it
     * was pasted for.
     */
    val hasKey: Boolean = false,
    val keyHint: String = "",
    val lastTestOkAt: Long? = null,
    val lastTestSignature: String = "",
    /** The host the active provider's saved key belongs to, or "" when none is saved. */
    val keyHost: String = "",
    /** Photos of the brew go along with an analysis (when the model can see them). */
    val sendPhotos: Boolean = true,
    /** The user's answer for a model the catalog doesn't know; null = never said. */
    val visionOverride: Boolean? = null,
    /** Every provider with a saved key. */
    val keys: Map<String, KeyBadge> = emptyMap(),
    /** The address saved for the custom provider, whichever provider is active. "" when none. */
    val customBaseUrl: String = "",
) {
    val host: String = safeHost(baseUrl)
    val isCustom: Boolean get() = providerId == AiProviders.CUSTOM
    val isCommandCode: Boolean get() = providerId == AiProviders.COMMANDCODE || host == ModelCatalog.COMMANDCODE_HOST
    val providerChosen: Boolean get() = providerId.isNotEmpty()

    /** A key is saved, but for a different host: show "Key saved for {keyHost}. Paste a key for {host}". */
    val keyForOtherHost: Boolean get() = keyHost.isNotEmpty() && keyHost != host

    val ready: Boolean = providerId.isNotEmpty() &&
        hasKey &&
        keyHost == host &&
        model.isNotBlank() &&
        BaseUrl.check(baseUrl) is BaseUrlCheck.Valid

    /** The catalog's word when it knows the model, else the user's, else no. */
    val modelSeesImages: Boolean get() = ModelCatalog.seesImages(providerId, model) ?: (visionOverride == true)

    /** True when the catalog lists this model, so the "can see images" question isn't asked. */
    val modelKnown: Boolean get() = ModelCatalog.model(providerId, model) != null

    val modelLabel: String get() = ModelCatalog.label(providerId, model)

    val signature: String get() = AiPrefs.signature(providerId, baseUrl, model, keyHint)
    val testedOk: Boolean get() = lastTestOkAt != null && lastTestSignature == signature
}

sealed interface KeyCheck {
    data object Saved : KeyCheck
    data class Rejected(val message: String) : KeyCheck
}

/**
 * The AI settings: which provider, and per provider its model, its key and its last test.
 *
 * One Keystore AES key (KeyVault) encrypts every provider's API key; each blob has its own IV.
 * A key is bound to the host it was pasted for and is only ever decrypted for that host.
 */
class AiSettingsStore(private val context: Context, private val vault: KeyVault = KeyVault()) {

    // A read error falls back to defaults ("no key") instead of crashing whoever collects it.
    private val prefs: Flow<Preferences> = context.aiStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    // The first read parses providers.json; keep that off whichever thread collects.
    val settings: Flow<AiSettings> = prefs
        .map { toSettings(it.named(), AiProviders.all) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    suspend fun snapshot(): AiSettings = settings.first()

    /** Makes [id] the active provider. Its own key, model and test result come with it. */
    suspend fun setProvider(id: String) {
        if (AiProviders.byId(id) == null) return
        context.aiStore.edit { it[str(AiPrefs.PROVIDER)] = id }
    }

    /** Custom only: stores a valid, normalised https address. Presets always use the catalog's. */
    suspend fun setBaseUrl(raw: String): BaseUrlCheck {
        val check = when (val c = BaseUrl.check(raw)) {
            // A dotless host is never a real API and is usually a pasted key. Storing it would
            // put the key in plain text on disk and hand it to DNS on the next request.
            is BaseUrlCheck.Valid ->
                if ('.' in c.host) c else BaseUrlCheck.Invalid(AiError.InvalidBaseUrl(AiError.InvalidBaseUrl.Reason.Malformed))
            is BaseUrlCheck.Invalid -> c
        }
        if (check is BaseUrlCheck.Valid) {
            context.aiStore.edit { it[str(AiPrefs.baseUrl(AiProviders.CUSTOM))] = check.normalized }
        }
        return check
    }

    /** Sets the active provider's model. The "can it see images" answer belonged to the old one. */
    suspend fun setModel(id: String) {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return
        context.aiStore.edit { p ->
            val pid = p[str(AiPrefs.PROVIDER)].orEmpty()
            if (pid.isEmpty()) return@edit
            if (p[str(AiPrefs.model(pid))] != trimmed) p.remove(str(AiPrefs.vision(pid)))
            p[str(AiPrefs.model(pid))] = trimmed
        }
    }

    /**
     * Saves the key for [providerId] and [forHost], the host the settings screen shows beside
     * the key field. The binding is checked inside the write: if that provider's address points
     * anywhere else, nothing is stored, so a key is never filed under a server it was not
     * pasted for.
     */
    suspend fun saveKey(raw: String, providerId: String, forHost: String): KeyCheck {
        val key = raw.trim()
        when {
            key.isEmpty() ->
                return KeyCheck.Rejected("Paste a key first.")
            AiProviders.byId(providerId) == null ->
                return KeyCheck.Rejected("Choose a provider first.")
            forHost.isBlank() ->
                return KeyCheck.Rejected("Save the address first.")
            key.any { it.code !in 0x21..0x7E } ->
                return KeyCheck.Rejected("That doesn't look like an API key: it has spaces or line breaks in the middle.")
            key.startsWith("http://", ignoreCase = true) || key.startsWith("https://", ignoreCase = true) ->
                return KeyCheck.Rejected("That's a web address, not an API key.")
            key.length < 8 ->
                return KeyCheck.Rejected("That's too short to be an API key.")
            key.length > 512 ->
                return KeyCheck.Rejected("That's too long to be an API key.")
        }
        val blob = try {
            // The other providers' saved keys are sealed with the same Keystore entry.
            val others = prefs.first().asMap()
                .filterKeys { it.name.startsWith("p.") && it.name.endsWith(".key_ct") && it.name != AiPrefs.keyCt(providerId) }
                .values.filterIsInstance<String>()
            withContext(Dispatchers.IO) { vault.encrypt(key, stillNeeded = others) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return KeyCheck.Rejected("This phone's key store refused the key.")
        }
        try {
            context.aiStore.edit { p ->
                // Throwing here abandons the whole edit, so the old key and host stay as they were.
                if (hostOf(p.named(), providerId) != forHost) throw HostMoved()
                p[str(AiPrefs.keyCt(providerId))] = blob
                p[str(AiPrefs.keyHint(providerId))] = key.takeLast(4)
                p[str(AiPrefs.keyHost(providerId))] = forHost
                p.remove(longPreferencesKey(AiPrefs.testOkAt(providerId)))
                p.remove(str(AiPrefs.testSig(providerId)))
            }
        } catch (e: HostMoved) {
            return KeyCheck.Rejected("The address changed. Check it, then save the key again.")
        } catch (e: IOException) {
            return KeyCheck.Rejected("Couldn't save the key on this phone. Try again.")
        }
        return KeyCheck.Saved
    }

    /** The saved address no longer matches the host a key was pasted for. */
    private class HostMoved : Exception()

    /** Forgets [providerId]'s key. The Keystore entry goes only when no provider has a key left. */
    suspend fun clearKey(providerId: String) {
        var anyLeft = true
        context.aiStore.edit { p ->
            p.clearKeyOf(providerId)
            anyLeft = p.asMap().keys.any { it.name.startsWith("p.") && it.name.endsWith(".key_ct") }
        }
        if (!anyLeft) withContext(Dispatchers.IO) { vault.deleteKey() }
    }

    /**
     * The plain key, but only when it was saved for [providerId] and for [forHost], the host the
     * caller is about to send it to, and that is still the host of that provider's address.
     * Checked here, at the moment of use, rather than against an earlier snapshot of the settings.
     */
    @Throws(KeyVaultException::class)
    suspend fun readKey(providerId: String, forHost: String): String? {
        val p = prefs.first()
        val blob = p[str(AiPrefs.keyCt(providerId))] ?: return null
        val named = p.named()
        if (forHost.isEmpty() || named[AiPrefs.keyHost(providerId)] != forHost || hostOf(named, providerId) != forHost) return null
        return withContext(Dispatchers.IO) { vault.decrypt(blob) }
    }

    suspend fun markTested(providerId: String, signature: String, atMillis: Long) {
        context.aiStore.edit { p ->
            p[longPreferencesKey(AiPrefs.testOkAt(providerId))] = atMillis
            p[str(AiPrefs.testSig(providerId))] = signature
        }
    }

    /** "This model can see images", for a model the catalog doesn't know. Null forgets the answer. */
    suspend fun setVisionOverride(providerId: String, sees: Boolean?) {
        context.aiStore.edit { p ->
            if (sees == null) p.remove(str(AiPrefs.vision(providerId))) else p[str(AiPrefs.vision(providerId))] = if (sees) "yes" else "no"
        }
    }

    suspend fun setSendPhotos(on: Boolean) {
        context.aiStore.edit { it[booleanPreferencesKey(AiPrefs.SEND_PHOTOS)] = on }
    }

    /**
     * Which provider and model, for a backup. It is built from the provider, model, address and
     * "can see images" entries only: no key, key hint or key host is read here, so none can
     * end up in a file.
     */
    suspend fun backup(): AiDto = toBackup(prefs.first().named(), AiProviders.all)

    /**
     * Takes the provider and model choices from a backup. Keys never travel: a provider that
     * arrives here has whatever key this phone already holds for it, still bound to the host
     * it was pasted for, or none. The one exception takes a key away: when the file changes
     * the custom provider's address, the key saved for the old address is forgotten.
     */
    suspend fun restore(dto: AiDto) {
        context.aiStore.edit { p ->
            val edits = restoreEdits(p.named(), dto, AiProviders.all)
            edits.set.forEach { (name, value) ->
                when (value) {
                    is Boolean -> p[booleanPreferencesKey(name)] = value
                    else -> p[str(name)] = value.toString()
                }
            }
            edits.remove.forEach { name ->
                // Each name is stored under one type; removing by the wrong type is a no-op.
                p.remove(str(name))
                p.remove(longPreferencesKey(name))
            }
        }
    }

    private fun MutablePreferences.clearKeyOf(providerId: String) {
        remove(str(AiPrefs.keyCt(providerId)))
        remove(str(AiPrefs.keyHint(providerId)))
        remove(str(AiPrefs.keyHost(providerId)))
        remove(longPreferencesKey(AiPrefs.testOkAt(providerId)))
        remove(str(AiPrefs.testSig(providerId)))
    }

    private fun str(name: String) = stringPreferencesKey(name)

    companion object {
        private fun Preferences.named(): Map<String, Any?> = asMap().mapKeys { it.key.name }

        /** The address requests for [providerId] go to: the catalog's for a preset, the saved one for custom. */
        internal fun baseUrlOf(prefs: Map<String, Any?>, provider: AiProvider?): String = when {
            provider == null -> ""
            provider.isCustom -> (prefs[AiPrefs.baseUrl(provider.id)] as? String).orEmpty()
            else -> provider.baseUrl
        }

        private fun hostOf(prefs: Map<String, Any?>, providerId: String): String =
            safeHost(baseUrlOf(prefs, AiProviders.byId(providerId)))

        /** What [restore] writes and removes, by key name. */
        internal class RestoreEdits(val set: Map<String, Any>, val remove: Set<String>)

        private const val MAX_MODEL_ID = 200

        /** The pure half of [backup]. */
        internal fun toBackup(prefs: Map<String, Any?>, providers: List<AiProvider>): AiDto {
            fun s(key: String): String? = (prefs[key] as? String)?.takeIf { it.isNotBlank() }
            val active = providers.firstOrNull { it.id == prefs[AiPrefs.PROVIDER] }?.id
            val rows = providers.mapNotNull { p ->
                val model = s(AiPrefs.model(p.id))
                val baseUrl = if (p.isCustom) s(AiPrefs.baseUrl(p.id)) else null
                val vision = when (prefs[AiPrefs.vision(p.id)]) {
                    "yes" -> true
                    "no" -> false
                    else -> null
                }
                // Only providers the person has touched: the one in use, or one with a choice saved.
                if (p.id != active && model == null && baseUrl == null && vision == null) return@mapNotNull null
                AiProviderDto(id = p.id, baseUrl = baseUrl, model = model ?: p.defaultModel?.id.orEmpty(), vision = vision)
            }
            return AiDto(
                activeProvider = active,
                sendPhotos = prefs[AiPrefs.SEND_PHOTOS] as? Boolean ?: true,
                providers = rows,
            )
        }

        /** The custom address [dto] would set, normalised as it would be stored; null when it has none that passes. */
        internal fun customAddressIn(dto: AiDto): String? {
            val raw = dto.providers.lastOrNull { it.id == AiProviders.CUSTOM && it.baseUrl != null }?.baseUrl ?: return null
            val check = BaseUrl.check(raw)
            return if (check is BaseUrlCheck.Valid && '.' in check.host) check.normalized else null
        }

        /**
         * The pure half of [restore]. A provider this phone doesn't offer is skipped, a custom
         * address has to pass the same checks as one typed here, and a passed connection test
         * is forgotten when the address changes, since that test was of another server.
         *
         * So is the key. A key is bound to a host name, and a file can name the same host with
         * another path or port (another account on a shared gateway): the binding would still
         * hold and the next analysis would hand the key, the brews and the photos to an address
         * nobody typed on this phone. An address that arrives in a file never inherits a key.
         */
        internal fun restoreEdits(current: Map<String, Any?>, dto: AiDto, providers: List<AiProvider>): RestoreEdits {
            val set = LinkedHashMap<String, Any>()
            val remove = LinkedHashSet<String>()
            dto.providers.forEach { row ->
                val provider = providers.firstOrNull { it.id == row.id } ?: return@forEach
                val id = provider.id
                val model = row.model.trim()
                if (model.isNotEmpty() && model.length <= MAX_MODEL_ID && model.none { it.code < 0x20 || it.code == 0x7F }) {
                    set[AiPrefs.model(id)] = model
                }
                when (row.vision) {
                    true -> set[AiPrefs.vision(id)] = "yes"
                    false -> set[AiPrefs.vision(id)] = "no"
                    null -> remove += AiPrefs.vision(id)
                }
                if (provider.isCustom && row.baseUrl != null) {
                    val check = BaseUrl.check(row.baseUrl)
                    if (check is BaseUrlCheck.Valid && '.' in check.host) {
                        if (current[AiPrefs.baseUrl(id)] != check.normalized) {
                            remove += AiPrefs.testOkAt(id)
                            remove += AiPrefs.testSig(id)
                            if (current[AiPrefs.keyCt(id)] != null) {
                                remove += AiPrefs.keyCt(id)
                                remove += AiPrefs.keyHint(id)
                                remove += AiPrefs.keyHost(id)
                            }
                        }
                        set[AiPrefs.baseUrl(id)] = check.normalized
                    }
                }
            }
            dto.activeProvider?.let { wanted ->
                if (providers.any { it.id == wanted }) set[AiPrefs.PROVIDER] = wanted
            }
            set[AiPrefs.SEND_PHOTOS] = dto.sendPhotos
            return RestoreEdits(set, remove)
        }

        /** The pure reading of the layout-2 file, by key name. */
        internal fun toSettings(prefs: Map<String, Any?>, providers: List<AiProvider>): AiSettings {
            fun s(key: String): String? = prefs[key] as? String
            val keys = providers.mapNotNull { p ->
                if (s(AiPrefs.keyCt(p.id)) == null) return@mapNotNull null
                p.id to KeyBadge(hint = s(AiPrefs.keyHint(p.id)).orEmpty(), host = s(AiPrefs.keyHost(p.id)).orEmpty())
            }.toMap()
            val sendPhotos = prefs[AiPrefs.SEND_PHOTOS] as? Boolean ?: true
            val customBaseUrl = s(AiPrefs.baseUrl(AiProviders.CUSTOM)).orEmpty()
            val provider = providers.firstOrNull { it.id == s(AiPrefs.PROVIDER) }
                ?: return AiSettings(sendPhotos = sendPhotos, keys = keys, customBaseUrl = customBaseUrl)
            val pid = provider.id
            val baseUrl = baseUrlOf(prefs, provider)
            val host = safeHost(baseUrl)
            val badge = keys[pid]
            return AiSettings(
                providerId = pid,
                providerName = provider.name,
                api = provider.api,
                auth = provider.auth,
                baseUrl = baseUrl,
                model = s(AiPrefs.model(pid))?.takeIf { it.isNotBlank() } ?: provider.defaultModel?.id.orEmpty(),
                hasKey = badge != null && badge.host.isNotEmpty() && badge.host == host,
                keyHint = badge?.hint.orEmpty(),
                lastTestOkAt = prefs[AiPrefs.testOkAt(pid)] as? Long,
                lastTestSignature = s(AiPrefs.testSig(pid)).orEmpty(),
                keyHost = badge?.host.orEmpty(),
                sendPhotos = sendPhotos,
                visionOverride = when (s(AiPrefs.vision(pid))) {
                    "yes" -> true
                    "no" -> false
                    else -> null
                },
                keys = keys,
                customBaseUrl = customBaseUrl,
            )
        }
    }
}

private fun safeHost(baseUrl: String): String = runCatching { BaseUrl.hostOf(baseUrl) }.getOrDefault("")
