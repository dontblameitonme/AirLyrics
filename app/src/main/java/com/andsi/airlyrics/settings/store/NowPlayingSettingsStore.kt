package com.andsi.airlyrics.settings.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.andsi.airlyrics.core.prefs.prefs
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AiIntroSettings(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val prompt: String
)

/**
 * Preferences for the in-app lyrics page: translation visibility and the AI song-intro
 * endpoint. The API key is encrypted with an Android Keystore AES-GCM key and app data is
 * excluded from backups, so the key never leaves the device in plain text.
 */
object NowPlayingSettingsStore {
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"

    private const val PREFS_NAME = "now_playing_page"
    private const val KEY_SHOW_TRANSLATION = "showTranslation"
    private const val KEY_AI_BASE_URL = "aiBaseUrl"
    private const val KEY_AI_MODEL = "aiModel"
    private const val KEY_AI_PROMPT = "aiPrompt"
    private const val KEY_AI_API_KEY = "aiApiKeyEncrypted"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "airlyrics_ai_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private fun store(context: Context) = prefs(context, PREFS_NAME)

    fun isTranslationVisible(context: Context): Boolean = store(context).getBoolean(KEY_SHOW_TRANSLATION, true)

    fun setTranslationVisible(context: Context, visible: Boolean) {
        store(context).setBoolean(KEY_SHOW_TRANSLATION, visible)
    }

    /** [defaultPrompt] is localized by the caller and used until the user saves their own. */
    fun getAiSettings(context: Context, defaultPrompt: String): AiIntroSettings {
        val preferences = store(context)
        return AiIntroSettings(
            baseUrl = preferences.getString(KEY_AI_BASE_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
            model = preferences.getString(KEY_AI_MODEL)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
            apiKey = preferences.getString(KEY_AI_API_KEY)?.let(::decrypt).orEmpty(),
            prompt = preferences.getString(KEY_AI_PROMPT)?.takeIf { it.isNotBlank() } ?: defaultPrompt
        )
    }

    fun setAiSettings(context: Context, settings: AiIntroSettings, defaultPrompt: String) {
        val encryptedKey = settings.apiKey.trim().takeIf { it.isNotEmpty() }?.let(::encrypt)
        store(context).edit(commit = true) {
            putString(KEY_AI_BASE_URL, settings.baseUrl.trim().ifBlank { DEFAULT_BASE_URL })
            putString(KEY_AI_MODEL, settings.model.trim().ifBlank { DEFAULT_MODEL })
            // Keep following the localized default unless the user actually changed it.
            if (settings.prompt.isBlank() || settings.prompt == defaultPrompt) remove(KEY_AI_PROMPT)
            else putString(KEY_AI_PROMPT, settings.prompt)
            if (encryptedKey == null) remove(KEY_AI_API_KEY) else putString(KEY_AI_API_KEY, encryptedKey)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        "$iv:$data"
    }.getOrNull()

    /** Returns null when the keystore entry was lost (e.g. after a device restore). */
    private fun decrypt(stored: String): String? = runCatching {
        val (iv, data) = stored.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()
}
