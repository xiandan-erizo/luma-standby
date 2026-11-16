package app.luma.standby.domain.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.model.LumaTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.lumaDataStore: DataStore<Preferences> by preferencesDataStore(name = "luma_settings")

/**
 * Single source of truth for persisted settings. DataStore + JSON so the same
 * codec is used by app settings and theme import/export.
 */
class ThemeRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    val settings: Flow<LumaSettings> = context.lumaDataStore.data.map { prefs ->
        val raw = prefs[KEY_SETTINGS]
        if (raw == null) LumaSettings() else decodeSettings(raw) ?: LumaSettings()
    }

    suspend fun current(): LumaSettings = settings.first()

    suspend fun update(transform: (LumaSettings) -> LumaSettings) {
        context.lumaDataStore.edit { prefs ->
            val raw = prefs[KEY_SETTINGS]
            val current = if (raw == null) LumaSettings() else decodeSettings(raw) ?: LumaSettings()
            prefs[KEY_SETTINGS] = encodeSettings(transform(current))
        }
    }

    suspend fun save(settings: LumaSettings) {
        context.lumaDataStore.edit { prefs -> prefs[KEY_SETTINGS] = encodeSettings(settings) }
    }

    fun encodeSettings(settings: LumaSettings): String =
        json.encodeToString(LumaSettings.serializer(), settings)

    fun decodeSettings(raw: String): LumaSettings? =
        runCatching { json.decodeFromString(LumaSettings.serializer(), raw) }.getOrNull()

    fun exportTheme(theme: LumaTheme): String = json.encodeToString(LumaTheme.serializer(), theme)

    fun importTheme(raw: String): LumaTheme? =
        runCatching { json.decodeFromString(LumaTheme.serializer(), raw) }.getOrNull()

    private companion object {
        val KEY_SETTINGS = stringPreferencesKey("luma_settings_v1")
    }
}
