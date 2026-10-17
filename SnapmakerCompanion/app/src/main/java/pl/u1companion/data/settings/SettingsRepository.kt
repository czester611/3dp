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
import pl.u1companion.core.model.SourceId
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val thingiverseToken: String = "",
    /** MyMiniFactory API v2 - klucz aplikacji (parametr `key`). */
    val mmfApiKey: String = "",
    /** Cults3D GraphQL - login + klucz API (Basic auth). */
    val cultsUsername: String = "",
    val cultsApiKey: String = "",
    /** Adres serwera slicingu w LAN, np. http://192.168.1.20:8484 */
    val slicerServerUrl: String = "",
    val slicerServerToken: String = "",
    val defaultPrinterId: String = "",
)

/** Dane uwierzytelniające serwisu. */
sealed interface ApiCredential {
    data class Token(val value: String) : ApiCredential
    data class UserKey(val user: String, val key: String) : ApiCredential
}

/** Źródło kluczy API dla adapterów (interfejs - w testach podmieniany). */
fun interface ApiCredentials {
    suspend fun get(source: SourceId): ApiCredential?
}

/**
 * Ustawienia aplikacji w DataStore (prywatny katalog, allowBackup=false).
 * Sekrety (tokeny, klucze API) są szyfrowane AES-GCM kluczem z Android Keystore ([SecretCipher]).
 */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) : ApiCredentials {

    private object Keys {
        val THINGIVERSE_TOKEN = stringPreferencesKey("thingiverse_token")
        val MMF_KEY = stringPreferencesKey("mmf_api_key")
        val CULTS_USER = stringPreferencesKey("cults_user")
        val CULTS_KEY = stringPreferencesKey("cults_api_key")
        val SLICER_URL = stringPreferencesKey("slicer_url")
        val SLICER_TOKEN = stringPreferencesKey("slicer_token")
        val DEFAULT_PRINTER = stringPreferencesKey("default_printer")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    override suspend fun get(source: SourceId): ApiCredential? {
        val s = current()
        return when (source) {
            SourceId.THINGIVERSE -> s.thingiverseToken.takeIf { it.isNotBlank() }?.let { ApiCredential.Token(it) }
            SourceId.MYMINIFACTORY -> s.mmfApiKey.takeIf { it.isNotBlank() }?.let { ApiCredential.Token(it) }
            SourceId.CULTS3D -> if (s.cultsUsername.isNotBlank() && s.cultsApiKey.isNotBlank()) ApiCredential.UserKey(s.cultsUsername, s.cultsApiKey) else null
            else -> null
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.THINGIVERSE_TOKEN] = SecretCipher.encrypt(next.thingiverseToken.trim())
            prefs[Keys.MMF_KEY] = SecretCipher.encrypt(next.mmfApiKey.trim())
            prefs[Keys.CULTS_USER] = next.cultsUsername.trim()
            prefs[Keys.CULTS_KEY] = SecretCipher.encrypt(next.cultsApiKey.trim())
            prefs[Keys.SLICER_URL] = next.slicerServerUrl.trim().trimEnd('/')
            prefs[Keys.SLICER_TOKEN] = SecretCipher.encrypt(next.slicerServerToken.trim())
            prefs[Keys.DEFAULT_PRINTER] = next.defaultPrinterId
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        thingiverseToken = SecretCipher.decrypt(this[Keys.THINGIVERSE_TOKEN]),
        mmfApiKey = SecretCipher.decrypt(this[Keys.MMF_KEY]),
        cultsUsername = this[Keys.CULTS_USER].orEmpty(),
        cultsApiKey = SecretCipher.decrypt(this[Keys.CULTS_KEY]),
        slicerServerUrl = this[Keys.SLICER_URL].orEmpty(),
        slicerServerToken = SecretCipher.decrypt(this[Keys.SLICER_TOKEN]),
        defaultPrinterId = this[Keys.DEFAULT_PRINTER].orEmpty(),
    )
}
