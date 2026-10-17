package pl.u1companion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.u1companion.BuildConfig
import pl.u1companion.data.settings.AppSettings
import pl.u1companion.data.settings.SettingsRepository
import pl.u1companion.slicing.SlicingRepository
import pl.u1companion.update.AvailableUpdate
import pl.u1companion.update.UpdateCheckResult
import pl.u1companion.update.UpdateInstaller
import pl.u1companion.update.UpdateRepository
import pl.u1companion.update.UpdateState
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val slicing: SlicingRepository,
    private val updates: UpdateRepository,
    private val installer: UpdateInstaller,
) : ViewModel() {
    val current = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val slicerTest = MutableStateFlow<String?>(null)
    val updateState = updates.state
    val available = MutableStateFlow<AvailableUpdate?>(null)
    val updateMessage = MutableStateFlow<String?>(null)

    fun save(s: AppSettings) = viewModelScope.launch { settings.update { s } }

    fun testSlicer() = viewModelScope.launch {
        slicerTest.value = "Łączenie…"
        slicerTest.value = runCatching { slicing.health() }
            .fold({ "OK: ${it.status}, slicer ${it.slicerVersion ?: "?"}" }, { "Błąd: ${it.message}" })
    }

    fun checkUpdates() = viewModelScope.launch {
        updateMessage.value = null
        when (val r = updates.check()) {
            is UpdateCheckResult.Available -> available.value = r.update
            UpdateCheckResult.UpToDate -> updateMessage.value = "Masz najnowszą wersję (${BuildConfig.VERSION_NAME})"
            is UpdateCheckResult.NotConfigured -> updateMessage.value = r.reason
            is UpdateCheckResult.Error -> updateMessage.value = r.message
        }
    }

    /** Wywoływane po wyraźnej zgodzie użytkownika. */
    fun downloadAndInstall(u: AvailableUpdate) = viewModelScope.launch {
        if (!installer.canRequestInstall()) {
            updateMessage.value = "Zezwól aplikacji na instalowanie aplikacji z nieznanych źródeł"
            return@launch
        }
        runCatching {
            val apk: File = updates.downloadAndVerify(u)
            updates.setState(UpdateState.Installing(u))
            installer.install(apk)
        }.onFailure { updateMessage.value = it.message }
    }

    fun unknownSourcesIntent() = installer.unknownSourcesSettingsIntent()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.current.collectAsStateWithLifecycle()
    val test by vm.slicerTest.collectAsStateWithLifecycle()
    val upState by vm.updateState.collectAsStateWithLifecycle()
    val available by vm.available.collectAsStateWithLifecycle()
    val upMsg by vm.updateMessage.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var token by remember { mutableStateOf("") }
    var slicerUrl by remember { mutableStateOf("") }
    var slicerToken by remember { mutableStateOf("") }
    LaunchedEffect(s) { token = s.thingiverseToken; slicerUrl = s.slicerServerUrl; slicerToken = s.slicerServerToken }

    Scaffold(topBar = { TopAppBar(title = { Text("Ustawienia") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Serwer slicingu (LAN)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(slicerUrl, { slicerUrl = it }, label = { Text("Adres, np. http://192.168.5.20:8484") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(slicerToken, { slicerToken = it }, label = { Text("Token serwera") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.save(s.copy(slicerServerUrl = slicerUrl, slicerServerToken = slicerToken)); vm.testSlicer() }) { Text("Zapisz i testuj") }
            }
            test?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            HorizontalDivider()
            Text("Serwisy z modelami", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(token, { token = it }, label = { Text("Thingiverse App Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("Token wygenerujesz na thingiverse.com/apps/create (typ: Desktop/App). Printables działa bez klucza.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.save(s.copy(thingiverseToken = token)) }) { Text("Zapisz") }

            HorizontalDivider()
            Text("Aktualizacje aplikacji", style = MaterialTheme.typography.titleMedium)
            Text("Wersja ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · źródło: github.com/${BuildConfig.UPDATE_REPO_OWNER}/${BuildConfig.UPDATE_REPO_NAME}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = vm::checkUpdates, enabled = upState !is UpdateState.Checking) { Text("Sprawdź aktualizacje") }
            when (val st = upState) {
                is UpdateState.Downloading -> LinearProgressIndicator(progress = { st.fraction }, modifier = Modifier.fillMaxWidth())
                is UpdateState.Verifying -> Text("Weryfikacja SHA-256 i podpisu…")
                is UpdateState.Installing -> Text("Potwierdź instalację w oknie systemowym")
                is UpdateState.Failed -> Text(st.message, color = MaterialTheme.colorScheme.error)
                else -> {}
            }
            available?.let { u ->
                Text("Dostępna wersja ${u.versionName} (${u.versionCode})", style = MaterialTheme.typography.titleSmall)
                if (u.releaseNotes.isNotBlank()) Text(u.releaseNotes.take(1500), style = MaterialTheme.typography.bodySmall)
                if (u.sha256 == null && u.sha256AssetUrl == null) Text("Uwaga: release bez sumy SHA-256 — instalacja zostanie zablokowana.", color = MaterialTheme.colorScheme.error)
                Button(onClick = { vm.downloadAndInstall(u) }) { Text("Pobierz i zainstaluj") }
            }
            upMsg?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodySmall)
                if (msg.contains("nieznanych źródeł")) OutlinedButton(onClick = { ctx.startActivity(vm.unknownSourcesIntent()) }) { Text("Otwórz ustawienia systemowe") }
            }
        }
    }
}
