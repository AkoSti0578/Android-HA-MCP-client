package nl.ako.hachat.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import nl.ako.hachat.core.AppJson

class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    private object K {
        val model = stringPreferencesKey("model")
        val maxTokens = intPreferencesKey("max_tokens")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val effort = stringPreferencesKey("effort")
        val showThinking = booleanPreferencesKey("show_thinking")
        val refusalFallback = booleanPreferencesKey("refusal_fallback")
        val streaming = booleanPreferencesKey("streaming")
        val readOnly = booleanPreferencesKey("read_only")
        val showToolCalls = booleanPreferencesKey("show_tool_calls")
        val mcpHeaderName = stringPreferencesKey("mcp_header_name")
        val writePatterns = stringPreferencesKey("write_patterns")
        val readPatterns = stringPreferencesKey("read_patterns")
        val overrides = stringPreferencesKey("tool_overrides")
        val models = stringPreferencesKey("models_cache")

        // versleuteld
        val apiKey = stringPreferencesKey("enc_api_key")
        val mcpUrl = stringPreferencesKey("enc_mcp_url")
        val mcpHeaderValue = stringPreferencesKey("enc_mcp_header_value")
    }

    private val stringList = ListSerializer(String.serializer())
    private val overrideMap = MapSerializer(String.serializer(), ToolOverride.serializer())
    private val modelList = ListSerializer(ModelInfo.serializer())

    val settings: Flow<AppSettings> = store.data.map(::fromPrefs)

    private fun fromPrefs(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            model = p[K.model] ?: d.model,
            maxTokens = p[K.maxTokens] ?: d.maxTokens,
            systemPrompt = p[K.systemPrompt] ?: d.systemPrompt,
            effort = p[K.effort]?.takeIf { it in EFFORT_LEVELS },
            showThinking = p[K.showThinking] ?: d.showThinking,
            refusalFallback = p[K.refusalFallback] ?: d.refusalFallback,
            streaming = p[K.streaming] ?: d.streaming,
            readOnlyMode = p[K.readOnly] ?: d.readOnlyMode,
            showToolCalls = p[K.showToolCalls] ?: d.showToolCalls,
            mcpHeaderName = p[K.mcpHeaderName] ?: d.mcpHeaderName,
            writePatterns = p[K.writePatterns]?.let { decode(stringList, it) } ?: d.writePatterns,
            readPatterns = p[K.readPatterns]?.let { decode(stringList, it) } ?: d.readPatterns,
            toolOverrides = p[K.overrides]?.let { decode(overrideMap, it) } ?: d.toolOverrides,
            models = p[K.models]?.let { decode(modelList, it) } ?: d.models,
        )
    }

    /** Ontsleutelde geheimen. Alleen gebruiken op het moment van een request. */
    val secrets: Flow<Secrets> = store.data.map { p ->
        Secrets(
            apiKey = p[K.apiKey]?.let { cipher.decrypt(it, "api_key") },
            mcpUrl = p[K.mcpUrl]?.let { cipher.decrypt(it, "mcp_url") },
            mcpHeaderValue = p[K.mcpHeaderValue]?.let { cipher.decrypt(it, "mcp_header") },
        )
    }

    /** Welke geheimen zijn opgeslagen — zonder ze te ontsleutelen (voor de UI). */
    val secretPresence: Flow<Triple<Boolean, Boolean, Boolean>> = store.data.map { p ->
        Triple(p[K.apiKey] != null, p[K.mcpUrl] != null, p[K.mcpHeaderValue] != null)
    }

    suspend fun current(): AppSettings = settings.first()
    suspend fun currentSecrets(): Secrets = secrets.first()

    suspend fun setApiKey(value: String?) = setSecret(K.apiKey, value?.trim(), "api_key")
    suspend fun setMcpUrl(value: String?) = setSecret(K.mcpUrl, value?.trim(), "mcp_url")
    suspend fun setMcpHeaderValue(value: String?) = setSecret(K.mcpHeaderValue, value?.trim(), "mcp_header")

    private suspend fun setSecret(key: Preferences.Key<String>, value: String?, purpose: String) {
        store.edit { p ->
            if (value.isNullOrEmpty()) p.remove(key) else p[key] = cipher.encrypt(value, purpose)
        }
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val old = fromPrefs(p)
            val new = block(old)
            p[K.model] = new.model
            p[K.maxTokens] = new.maxTokens.coerceIn(256, 128_000)
            p[K.systemPrompt] = new.systemPrompt
            if (new.effort == null) p.remove(K.effort) else p[K.effort] = new.effort
            p[K.showThinking] = new.showThinking
            p[K.refusalFallback] = new.refusalFallback
            p[K.streaming] = new.streaming
            p[K.readOnly] = new.readOnlyMode
            p[K.showToolCalls] = new.showToolCalls
            p[K.mcpHeaderName] = new.mcpHeaderName.trim()
            p[K.writePatterns] = AppJson.encodeToString(stringList, new.writePatterns)
            p[K.readPatterns] = AppJson.encodeToString(stringList, new.readPatterns)
            p[K.overrides] = AppJson.encodeToString(overrideMap, new.toolOverrides.filterValues { it != ToolOverride.AUTO })
            p[K.models] = AppJson.encodeToString(modelList, new.models)
        }
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, raw: String): T? =
        runCatching { AppJson.decodeFromString(serializer, raw) }.getOrNull()
}
