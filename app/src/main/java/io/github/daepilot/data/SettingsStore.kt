package io.github.daepilot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.daepilot.core.model.DnsConfig
import io.github.daepilot.core.model.UserConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "daepilot_config",
)

class SettingsStore(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val configKey = stringPreferencesKey("user_config_json")

    val config: Flow<UserConfig> = context.configDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { prefs ->
            prefs[configKey]?.let { raw ->
                runCatching { json.decodeFromString<UserConfig>(raw) }.getOrNull()
            } ?: UserConfig()
        }

    /** Latest committed config, without waiting for the collector of [config]. */
    suspend fun current(): UserConfig = config.first()

    suspend fun update(transform: (UserConfig) -> UserConfig) {
        context.configDataStore.edit { prefs ->
            val current = prefs[configKey]?.let { raw ->
                runCatching { json.decodeFromString<UserConfig>(raw) }.getOrNull()
            } ?: UserConfig()
            prefs[configKey] = json.encodeToString(UserConfig.serializer(), transform(current))
        }
    }

    suspend fun replace(config: UserConfig) {
        context.configDataStore.edit { prefs ->
            prefs[configKey] = json.encodeToString(UserConfig.serializer(), config)
        }
    }

    /**
     * Plain DNS through the node was the old default and is polluted on many
     * carriers. Migrate only that exact default; custom upstreams are kept.
     */
    suspend fun migrateLegacyDns() {
        update { config ->
            if (config.dns.foreignUpstream == DnsConfig.LEGACY_FOREIGN_UPSTREAM) {
                config.copy(
                    dns = config.dns.copy(foreignUpstream = DnsConfig.DEFAULT_FOREIGN_UPSTREAM),
                )
            } else {
                config
            }
        }
    }
}
