package com.andsi.airlyrics.settings.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.andsi.airlyrics.core.prefs.prefs
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AiIntroSettings(
    val baseUrl: String,
    val model: String,
    val apiKey: String
)

data class AiPromptSetting(
    val id: String,
    val name: String,
    val template: String
)

data class WebSearchSettings(
    val engine: String,
    val enabledEngines: Set<String>,
    val resultCount: Int,
    val googleApiKey: String,
    val googleCx: String,
    val tinyFishApiKey: String,
    val customUrl: String,
    val customHeaderName: String,
    val customHeaderValue: String
)

/**
 * Preferences for the in-app lyrics page: translation visibility, the AI endpoint, prompts and
 * web search. API keys are encrypted with an Android Keystore AES-GCM key and app data is
 * excluded from backups, so the key never leaves the device in plain text.
 */
object NowPlayingSettingsStore {
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"

    private const val PREFS_NAME = "now_playing_page"
    private const val KEY_SHOW_TRANSLATION = "showTranslation"
    private const val KEY_AI_BASE_URL = "aiBaseUrl"
    private const val KEY_AI_MODEL = "aiModel"
    private const val KEY_AI_API_KEY = "aiApiKeyEncrypted"
    private const val KEY_AI_PROMPTS = "aiPrompts"
    private const val KEY_AI_SELECTED_PROMPT = "aiSelectedPrompt"
    private const val KEY_AI_WEB_SEARCH = "aiWebSearch"
    private const val KEY_SEARCH_ENGINE = "searchEngine"
    private const val KEY_SEARCH_ENABLED_ENGINES = "searchEnabledEngines"
    private const val KEY_SEARCH_COUNT = "searchResultCount"
    private const val KEY_GOOGLE_KEY = "googleApiKeyEncrypted"
    private const val KEY_GOOGLE_CX = "googleCx"
    private const val KEY_TINYFISH_KEY = "tinyFishApiKeyEncrypted"
    private const val KEY_CUSTOM_URL = "customSearchUrl"
    private const val KEY_CUSTOM_HEADER_NAME = "customSearchHeaderName"
    private const val KEY_CUSTOM_HEADER_VALUE = "customSearchHeaderValueEncrypted"
    const val DEFAULT_SEARCH_ENGINE = "bing"
    val DEFAULT_ENABLED_ENGINES = setOf("bing", "google")
    const val DEFAULT_SEARCH_COUNT = 5
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "airlyrics_ai_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private fun store(context: Context) = prefs(context, PREFS_NAME)

    fun isTranslationVisible(context: Context): Boolean = store(context).getBoolean(KEY_SHOW_TRANSLATION, true)

    fun setTranslationVisible(context: Context, visible: Boolean) {
        store(context).setBoolean(KEY_SHOW_TRANSLATION, visible)
    }

    fun getAiSettings(context: Context): AiIntroSettings {
        val preferences = store(context)
        return AiIntroSettings(
            baseUrl = preferences.getString(KEY_AI_BASE_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
            model = preferences.getString(KEY_AI_MODEL)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
            apiKey = preferences.getString(KEY_AI_API_KEY)?.let(::decrypt).orEmpty()
        )
    }

    fun setAiSettings(context: Context, settings: AiIntroSettings) {
        val encryptedKey = settings.apiKey.trim().takeIf { it.isNotEmpty() }?.let(::encrypt)
        store(context).edit(commit = true) {
            putString(KEY_AI_BASE_URL, settings.baseUrl.trim().ifBlank { DEFAULT_BASE_URL })
            putString(KEY_AI_MODEL, settings.model.trim().ifBlank { DEFAULT_MODEL })
            if (encryptedKey == null) remove(KEY_AI_API_KEY) else putString(KEY_AI_API_KEY, encryptedKey)
        }
    }

    /** User-edited prompt list, or null while the localized built-in presets are in use. */
    fun getPrompts(context: Context): List<AiPromptSetting>? {
        val raw = store(context).getString(KEY_AI_PROMPTS) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                AiPromptSetting(item.optString("id"), item.optString("name"), item.optString("template"))
                    .takeIf { it.id.isNotBlank() && it.template.isNotBlank() }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Pass null to go back to the built-in presets. */
    fun setPrompts(context: Context, prompts: List<AiPromptSetting>?) {
        val value = prompts?.takeIf { it.isNotEmpty() }?.let { list ->
            JSONArray().apply {
                list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("template", it.template)) }
            }.toString()
        }
        store(context).setString(KEY_AI_PROMPTS, value)
    }

    fun getSelectedPromptId(context: Context): String? = store(context).getString(KEY_AI_SELECTED_PROMPT)

    fun setSelectedPromptId(context: Context, id: String) {
        store(context).setString(KEY_AI_SELECTED_PROMPT, id)
    }

    fun isAiWebSearchOn(context: Context): Boolean = store(context).getBoolean(KEY_AI_WEB_SEARCH, false)

    fun setAiWebSearchOn(context: Context, on: Boolean) {
        store(context).setBoolean(KEY_AI_WEB_SEARCH, on)
    }

    fun getWebSearchSettings(context: Context): WebSearchSettings {
        val preferences = store(context)
        val enabled = if (preferences.contains(KEY_SEARCH_ENABLED_ENGINES)) {
            preferences.getStringSet(KEY_SEARCH_ENABLED_ENGINES)
        } else DEFAULT_ENABLED_ENGINES
        return WebSearchSettings(
            engine = preferences.getString(KEY_SEARCH_ENGINE)?.takeIf { it.isNotBlank() } ?: DEFAULT_SEARCH_ENGINE,
            enabledEngines = enabled,
            resultCount = preferences.getInt(KEY_SEARCH_COUNT, DEFAULT_SEARCH_COUNT).coerceIn(1, 10),
            googleApiKey = preferences.getString(KEY_GOOGLE_KEY)?.let(::decrypt).orEmpty(),
            googleCx = preferences.getString(KEY_GOOGLE_CX).orEmpty(),
            tinyFishApiKey = preferences.getString(KEY_TINYFISH_KEY)?.let(::decrypt).orEmpty(),
            customUrl = preferences.getString(KEY_CUSTOM_URL).orEmpty(),
            customHeaderName = preferences.getString(KEY_CUSTOM_HEADER_NAME).orEmpty(),
            customHeaderValue = preferences.getString(KEY_CUSTOM_HEADER_VALUE)?.let(::decrypt).orEmpty()
        )
    }

    fun setWebSearchSettings(context: Context, settings: WebSearchSettings) {
        fun secret(value: String): String? = value.trim().takeIf { it.isNotEmpty() }?.let(::encrypt)
        val google = secret(settings.googleApiKey)
        val tinyFish = secret(settings.tinyFishApiKey)
        val header = secret(settings.customHeaderValue)
        store(context).edit(commit = true) {
            putString(KEY_SEARCH_ENGINE, settings.engine)
            putStringSet(KEY_SEARCH_ENABLED_ENGINES, settings.enabledEngines.toSet())
            putInt(KEY_SEARCH_COUNT, settings.resultCount.coerceIn(1, 10))
            if (google == null) remove(KEY_GOOGLE_KEY) else putString(KEY_GOOGLE_KEY, google)
            putString(KEY_GOOGLE_CX, settings.googleCx.trim())
            if (tinyFish == null) remove(KEY_TINYFISH_KEY) else putString(KEY_TINYFISH_KEY, tinyFish)
            putString(KEY_CUSTOM_URL, settings.customUrl.trim())
            putString(KEY_CUSTOM_HEADER_NAME, settings.customHeaderName.trim())
            if (header == null) remove(KEY_CUSTOM_HEADER_VALUE) else putString(KEY_CUSTOM_HEADER_VALUE, header)
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
