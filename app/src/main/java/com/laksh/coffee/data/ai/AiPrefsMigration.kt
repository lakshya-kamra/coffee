package com.laksh.coffee.data.ai

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.laksh.coffee.domain.ai.AiProvider
import com.laksh.coffee.domain.ai.AiProviders
import com.laksh.coffee.domain.ai.BaseUrl
import kotlinx.coroutines.CancellationException

/** Key names of the `coffee_ai` DataStore, layout 2: one key, model and test result per provider. */
internal object AiPrefs {
    const val LAYOUT = "ai_layout"
    const val LAYOUT_VERSION = 2
    const val PROVIDER = "provider"
    const val SEND_PHOTOS = "send_photos"

    fun baseUrl(id: String) = "p.$id.base_url"
    fun model(id: String) = "p.$id.model"
    fun keyCt(id: String) = "p.$id.key_ct"
    fun keyHint(id: String) = "p.$id.key_hint"
    fun keyHost(id: String) = "p.$id.key_host"
    fun testOkAt(id: String) = "p.$id.test_ok_at"
    fun testSig(id: String) = "p.$id.test_sig"
    fun vision(id: String) = "p.$id.vision"

    /** "$providerId|$baseUrl|$model|$keyHint": a passed test only counts for exactly this setup. */
    fun signature(providerId: String, baseUrl: String, model: String, keyHint: String): String =
        "$providerId|$baseUrl|$model|$keyHint"

    // ---- 1.x (layout 1): one address, one model, one key.
    const val V1_BASE_URL = "base_url"
    const val V1_MODEL = "model"
    const val V1_KEY_CT = "key_ct"
    const val V1_KEY_HINT = "key_hint"
    const val V1_KEY_HOST = "key_host"
    const val V1_TEST_OK_AT = "last_test_ok_at"
    const val V1_TEST_SIG = "last_test_sig"
    val V1_KEYS = setOf(V1_BASE_URL, V1_MODEL, V1_KEY_CT, V1_KEY_HINT, V1_KEY_HOST, V1_TEST_OK_AT, V1_TEST_SIG)

    /** What 1.x used when nothing was saved. */
    const val V1_DEFAULT_BASE_URL = "https://api.commandcode.ai/provider/v1"
    const val V1_DEFAULT_MODEL = "z-ai/glm-5.3-flash"
}

/**
 * Moves the 1.x AI settings (one address, one key) to one key per provider, once.
 *
 * The key's ciphertext, hint and host are moved as they are, so the saved key keeps working
 * and stays bound to the host it was pasted for. Nothing is decrypted here.
 */
class AiPrefsMigration(
    /** True when this install already holds brews or gear, i.e. it was upgraded from 1.x. */
    private val upgradedInstall: suspend () -> Boolean,
) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[intPreferencesKey(AiPrefs.LAYOUT)] == null

    override suspend fun migrate(currentData: Preferences): Preferences {
        val old: Map<String, Any?> = currentData.asMap().mapKeys { it.key.name }
        // A fresh install has no 1.x key and no data; only then is no provider chosen.
        val upgraded = try {
            upgradedInstall()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        val out = mutablePreferencesOf()
        migrateV1(old, upgraded).forEach { (name, value) ->
            when (value) {
                is String -> out[stringPreferencesKey(name)] = value
                is Long -> out[longPreferencesKey(name)] = value
                is Int -> out[intPreferencesKey(name)] = value
                is Boolean -> out[booleanPreferencesKey(name)] = value
                else -> Unit
            }
        }
        return out.toPreferences()
    }

    override suspend fun cleanUp() = Unit

    companion object {
        /**
         * The pure core. [old] is the 1.x file by key name. Returns the layout-2 file: 1.x keys
         * gone, everything else kept.
         *
         * - Any 1.x key present: the address decides the provider (its preset when the host is
         *   one of the catalog's, else "custom" with the address kept), and the model, key and
         *   test result move under it.
         * - Nothing saved, but the install has data: Command Code with the 1.x default model,
         *   which is what 1.x used without being asked.
         * - Nothing saved and no data: no provider chosen.
         */
        fun migrateV1(
            old: Map<String, Any?>,
            upgradedInstall: Boolean = false,
            providers: List<AiProvider> = AiProviders.all,
        ): Map<String, Any?> {
            val out = old.filterKeys { it !in AiPrefs.V1_KEYS }.filterValues { it != null }.toMutableMap()
            out[AiPrefs.LAYOUT] = AiPrefs.LAYOUT_VERSION

            val touched = AiPrefs.V1_KEYS.any { old[it] != null }
            if (!touched && !upgradedInstall) {
                out[AiPrefs.PROVIDER] = ""
                return out
            }

            val oldBaseUrl = (old[AiPrefs.V1_BASE_URL] as? String)?.takeIf { it.isNotBlank() } ?: AiPrefs.V1_DEFAULT_BASE_URL
            val model = (old[AiPrefs.V1_MODEL] as? String)?.takeIf { it.isNotBlank() } ?: AiPrefs.V1_DEFAULT_MODEL
            val preset = AiProviders.forHost(BaseUrl.hostOf(oldBaseUrl), providers)
            val pid = preset?.id ?: AiProviders.CUSTOM
            val newBaseUrl = preset?.baseUrl ?: oldBaseUrl

            out[AiPrefs.PROVIDER] = pid
            if (preset == null) out[AiPrefs.baseUrl(pid)] = oldBaseUrl
            out[AiPrefs.model(pid)] = model

            val hint = (old[AiPrefs.V1_KEY_HINT] as? String).orEmpty()
            (old[AiPrefs.V1_KEY_CT] as? String)?.let { ct ->
                out[AiPrefs.keyCt(pid)] = ct
                out[AiPrefs.keyHint(pid)] = hint
                (old[AiPrefs.V1_KEY_HOST] as? String)?.let { out[AiPrefs.keyHost(pid)] = it }
            }
            val testedAt = old[AiPrefs.V1_TEST_OK_AT] as? Long
            val testedSig = old[AiPrefs.V1_TEST_SIG] as? String
            if (testedAt != null && testedSig != null) {
                out[AiPrefs.testOkAt(pid)] = testedAt
                // The signature gained the provider id. A test that was current stays current;
                // one that was already out of date is moved as it was and stays out of date.
                // A preset whose address differs from the one that was tested needs a fresh test.
                val wasCurrent = testedSig == "$oldBaseUrl|$model|$hint" && newBaseUrl == oldBaseUrl
                out[AiPrefs.testSig(pid)] =
                    if (wasCurrent) AiPrefs.signature(pid, newBaseUrl, model, hint) else testedSig
            }
            return out
        }
    }
}
