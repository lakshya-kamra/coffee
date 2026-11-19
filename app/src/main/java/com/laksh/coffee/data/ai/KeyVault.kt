package com.laksh.coffee.data.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A saved key could not be encrypted or decrypted. The message never contains the key or the blob. */
class KeyVaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Encrypts the API key with an AES-256-GCM key held in the Android Keystore. The ciphertext on
 * disk is useless without this phone's Keystore entry, which cannot be exported or backed up.
 *
 * Every method blocks on the Keystore, so callers run them on Dispatchers.IO.
 */
class KeyVault(private val alias: String = DEFAULT_ALIAS) {

    /**
     * Returns "v1:<iv>:<ciphertext+tag>", both parts Base64.
     *
     * [stillNeeded] are the blobs already sealed with this entry (the other providers' keys).
     * One Keystore entry opens them all, so it is replaced after a failure only when that
     * loses nothing: there are no such blobs, or the entry is gone for good and can open none
     * of them. A passing refusal (the Keystore service under memory pressure, a slow OEM
     * build) gets one more try with the same entry, and otherwise fails with the entry intact.
     */
    @Synchronized
    @Throws(KeyVaultException::class)
    fun encrypt(plain: String, stillNeeded: List<String> = emptyList()): String {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        return try {
            seal(bytes)
        } catch (first: Exception) {
            try {
                // An entry that exists but no longer works (after a restore or a security
                // reset) can never decrypt anything again, so replacing it loses nothing.
                if (stillNeeded.isEmpty() || isPermanent(first) || stillNeeded.all(::lostForGood)) deleteKey()
                seal(bytes)
            } catch (second: Exception) {
                throw KeyVaultException("The key store refused to encrypt.", second)
            }
        }
    }

    /** True only when [blob] can never be opened again; a failure that might pass is false. */
    private fun lostForGood(blob: String): Boolean = try {
        decrypt(blob)
        false
    } catch (e: Exception) {
        isPermanent(e)
    }

    private fun isPermanent(e: Throwable): Boolean {
        var x: Throwable? = e
        var depth = 0
        while (x != null && depth < 6) {
            // Invalidated by a security change, unreadable key material, or sealed by an entry
            // that has since been replaced (the tag no longer matches).
            if (x is KeyPermanentlyInvalidatedException || x is UnrecoverableKeyException || x is AEADBadTagException) return true
            x = x.cause
            depth++
        }
        return false
    }

    @Synchronized
    @Throws(KeyVaultException::class)
    fun decrypt(blob: String): String {
        try {
            val parts = blob.split(':')
            if (parts.size != 3 || parts[0] != VERSION) {
                throw KeyVaultException("The saved key is in an unknown format.")
            }
            val iv = Base64.getDecoder().decode(parts[1])
            val sealed = Base64.getDecoder().decode(parts[2])
            val key = existingKey() ?: throw KeyVaultException("The key store no longer has this app's key.")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            return String(cipher.doFinal(sealed), Charsets.UTF_8)
        } catch (e: KeyVaultException) {
            throw e
        } catch (e: Exception) {
            throw KeyVaultException("The saved key couldn't be decrypted.", e)
        }
    }

    @Synchronized
    fun deleteKey() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun seal(bytes: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No IV passed: the Keystore insists on picking a fresh random one itself.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val sealed = cipher.doFinal(bytes)
        return VERSION + ":" + b64(cipher.iv) + ":" + b64(sealed)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun getOrCreateKey(): SecretKey = existingKey() ?: run {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    companion object {
        const val DEFAULT_ALIAS = "coffee_ai_api_key_v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val VERSION = "v1"
    }
}
