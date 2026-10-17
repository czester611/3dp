package pl.u1companion.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val thingiverseToken: String = "",
    /** Adres serwera slicingu w LAN, np. http://192.168.1.20:8484 */
    val slicerServerUrl: String = "",
    val slicerServerToken: String = "",
    val defaultPrinterId: String = "",
)

/**
 * Ustawienia aplikacji. Tokeny trzymamy w DataStore w prywatnym katalogu aplikacji
 * (allowBackup=false). TODO(etap 3): szyfrowanie tokenów kluczem z Android Keystore.
 */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val THINGIVERSE_TOKEN = stringPreferencesKey("thingiverse_token")
        val SLICER_URL = stringPreferencesKey("slicer_url")
        val SLICER_TOKEN = stringPreferencesKey("slicer_token")
        val DEFAULT_PRINTER = stringPreferencesKey("default_printer")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.THINGIVERSE_TOKEN] = next.thingiverseToken.trim()
            prefs[Keys.SLICER_URL] = next.slicerServerUrl.trim().trimEnd('/')
            prefs[Keys.SLICER_TOKEN] = next.slicerServerToken.trim()
            prefs[Keys.DEFAULT_PRINTER] = next.defaultPrinterId
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        thingiverseToken = this[Keys.THINGIVERSE_TOKEN].orEmpty(),
        slicerServerUrl = this[Keys.SLICER_URL].orEmpty(),
        slicerServerToken = this[Keys.SLICER_TOKEN].orEmpty(),
        defaultPrinterId = this[Keys.DEFAULT_PRINTER].orEmpty(),
    )
}
