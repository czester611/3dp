package pl.u1companion.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.u1companion.core.model.FileKind
import pl.u1companion.core.model.SourceId
import pl.u1companion.download.DownloadRepository
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Wbudowana przeglądarka dla serwisów bez API (MakerWorld, Thangs, ...).
 * Użytkownik loguje się / przechodzi Cloudflare normalnie; kliknięcie "Download" na stronie
 * trafia do DownloadListener -> pobieramy plik z ciasteczkami sesji i dodajemy do biblioteki.
 */
@HiltViewModel
class WebSourceViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val http: OkHttpClient,
    private val downloads: DownloadRepository,
) : ViewModel() {
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun download(source: SourceId, url: String, userAgent: String, contentDisposition: String?, mime: String?, modelKey: String?) = viewModelScope.launch {
        val name = URLUtil.guessFileName(url, contentDisposition, mime)
        if (FileKind.fromFileName(name) == FileKind.OTHER) _events.send("Pobieram $name (nierozpoznany typ pliku)")
        runCatching {
            withContext(Dispatchers.IO) {
                val cookies = CookieManager.getInstance().getCookie(url)
                val req = Request.Builder().url(url).header("User-Agent", userAgent).apply { cookies?.let { header("Cookie", it) } }.build()
                val dir = File(ctx.filesDir, "library/${source.name.lowercase()}/web").apply { mkdirs() }
                val target = File(dir, name.replace(Regex("[^A-Za-z0-9._ -]"), "_"))
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    r.body!!.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                }
                downloads.registerLocal(target.name, target.absolutePath, target.length(), url, modelKey)
            }
        }.onSuccess { _events.send("Dodano do biblioteki: $name") }
            .onFailure { _events.send("Pobieranie nieudane: ${it.message}") }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSourceScreen(source: SourceId, url: String, onBack: () -> Unit, modelKey: String? = null, vm: WebSourceViewModel = hiltViewModel()) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(source.displayName) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        setDownloadListener { dlUrl, ua, cd, mime, _ -> vm.download(source, dlUrl, ua, cd, mime, modelKey) }
                        loadUrl(url)
                    }
                },
            )
        }
    }
}
