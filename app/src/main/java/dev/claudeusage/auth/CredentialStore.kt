package dev.claudeusage.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Credentials(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch millis. */
    val expiresAt: Long,
    val scopes: List<String> = emptyList(),
    val accountUuid: String? = null,
    val email: String? = null,
    val organizationUuid: String? = null,
) {
    /** Refresh with 5 minutes to spare, like Claude Code. */
    fun needsRefresh(now: Long): Boolean = now + REFRESH_MARGIN >= expiresAt

    override fun toString(): String = "Credentials(expiresAt=$expiresAt, scopes=$scopes)"

    companion object {
        const val REFRESH_MARGIN = 5 * 60_000L
    }
}

/**
 * Keeps the OAuth tokens encrypted with an AES-256-GCM key that lives in the Android Keystore (hardware-backed
 * where the phone has it) and never leaves it. The file is in `no_backup`, and backups are off for the whole
 * app, so the tokens stay on this device. A key that can't decrypt any more (restored device, cleared lock
 * screen) just means signing in again.
 */
class CredentialStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "credentials.bin")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): Credentials? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            require(bytes.size > 1 + IV_SIZE && bytes[0] == VERSION)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false) ?: error("no key"), GCMParameterSpec(128, bytes, 1, IV_SIZE))
            cipher.updateAAD(AAD)
            val plain = cipher.doFinal(bytes, 1 + IV_SIZE, bytes.size - 1 - IV_SIZE)
            json.decodeFromString(Credentials.serializer(), String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "Stored credentials unreadable (${e.javaClass.simpleName}); discarding")
            clear()
            null
        }
    }

    @Synchronized
    fun save(credentials: Credentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(AAD)
        val sealed = cipher.doFinal(json.encodeToString(Credentials.serializer(), credentials).toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        check(iv.size == IV_SIZE)
        // Write-then-rename, so a crash mid-write never leaves a torn file (the refresh token is single-use:
        // losing the new one would sign the user out).
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(byteArrayOf(VERSION))
            out.write(iv)
            out.write(sealed)
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Couldn't store credentials")
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "CredentialStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "claude-usage-credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val VERSION: Byte = 1
        val AAD = "dev.claudeusage/credentials/v1".toByteArray()
    }
}
