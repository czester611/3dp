package pl.u1companion.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.u1companion.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OTA z GitHub Releases: sprawdzenie -> pobranie -> weryfikacja -> instalacja (UpdateInstaller).
 *
 * Zasady bezpieczeństwa:
 *  1. Tylko HTTPS (api.github.com, github.com, objects.githubusercontent.com).
 *  2. Nigdy nie obniżamy wersji: instalujemy wyłącznie versionCode > BuildConfig.VERSION_CODE
 *     (Android i tak odrzuci downgrade bez -d, ale sprawdzamy wcześniej, by nie pobierać na darmo).
 *  3. SHA-256 pliku musi zgadzać się z sumą z release (digest assetu / plik .sha256 / opis), jeśli jest dostępna;
 *     gdy brak sumy - wymagamy wyraźnej zgody użytkownika (ustawienie "zezwól bez sumy kontrolnej", domyślnie wył.).
 *  4. Certyfikat podpisu pobranego APK musi być identyczny z certyfikatem zainstalowanej aplikacji
 *     (ten sam keystore dla każdej wersji) - inaczej odrzucamy przed instalacją.
 *  5. packageName pobranego APK == nasz packageName.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val owner = BuildConfig.UPDATE_REPO_OWNER
    private val repo = BuildConfig.UPDATE_REPO_NAME

    val isConfigured: Boolean get() = owner.isNotBlank() && repo.isNotBlank() && owner != "CHANGE_ME"

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext UpdateCheckResult.NotConfigured("Ustaw updateRepoOwner/updateRepoName w gradle.properties")
        _state.value = UpdateState.Checking
        val result = try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/releases/latest") // /latest pomija draft i prerelease
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            val body = http.newCall(req).execute().use { r ->
                when (r.code) {
                    404 -> return@use null // brak release lub prywatne repo
                    403, 429 -> throw IOException("Limit zapytań GitHub API (60/h bez tokenu) - spróbuj później")
                }
                if (!r.isSuccessful) throw IOException("GitHub HTTP ${r.code}")
                r.body?.string()
            }
            if (body == null) UpdateCheckResult.UpToDate
            else parse(json.decodeFromString(GhRelease.serializer(), body))
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Błąd sprawdzania aktualizacji", e)
        }
        _state.value = when (result) {
            is UpdateCheckResult.Error -> UpdateState.Failed(result.message)
            else -> UpdateState.Idle
        }
        result
    }

    internal fun parse(rel: GhRelease): UpdateCheckResult {
        val m = TAG_REGEX.matchEntire(rel.tagName.trim())
            ?: return UpdateCheckResult.Error("Nieznany format tagu: ${rel.tagName} (oczekiwano v<wersja>+<versionCode>)")
        val versionName = m.groupValues[1]
        val code = m.groupValues[2].toInt()
        if (code <= BuildConfig.VERSION_CODE) return UpdateCheckResult.UpToDate
        val apk = rel.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return UpdateCheckResult.Error("Release ${rel.tagName} nie zawiera pliku .apk")
        val shaFromDigest = apk.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
        val shaFromBody = rel.body?.let { SHA_IN_BODY.find(it)?.groupValues?.get(1) }
        val shaAsset = rel.assets.firstOrNull { it.name.equals(apk.name + ".sha256", ignoreCase = true) }
        return UpdateCheckResult.Available(
            AvailableUpdate(
                versionCode = code, versionName = versionName, tag = rel.tagName,
                apkUrl = apk.downloadUrl, apkName = apk.name, apkSize = apk.size,
                sha256 = (shaFromDigest ?: shaFromBody)?.lowercase(),
                sha256AssetUrl = shaAsset?.downloadUrl,
                releaseNotes = rel.body.orEmpty(), htmlUrl = rel.htmlUrl,
            ),
        )
    }

    /** Pobiera i weryfikuje APK. Zwraca plik gotowy do instalacji. */
    suspend fun downloadAndVerify(u: AvailableUpdate, allowWithoutChecksum: Boolean = false): File = withContext(Dispatchers.IO) {
        require(u.apkUrl.startsWith("https://")) { "Odrzucono nie-HTTPS URL aktualizacji" }
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val target = File(dir, "update-${u.versionCode}.apk")
        _state.value = UpdateState.Downloading(u, 0f)
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(u.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Pobieranie APK: HTTP ${r.code}")
            val body = r.body ?: throw IOException("Pusta odpowiedź")
            val total = body.contentLength().takeIf { it > 0 } ?: u.apkSize ?: -1L
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); sent += n
                        if (total > 0) _state.value = UpdateState.Downloading(u, sent.toFloat() / total)
                    }
                }
            }
        }
        _state.value = UpdateState.Verifying(u)
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        val expected = u.sha256 ?: u.sha256AssetUrl?.let { fetchShaAsset(it) }
        when {
            expected != null && !expected.equals(actual, ignoreCase = true) -> fail(target, "Suma SHA-256 nie zgadza się (oczekiwano $expected, jest $actual)")
            expected == null && !allowWithoutChecksum -> fail(target, "Release nie zawiera sumy SHA-256 - instalacja zablokowana")
        }
        verifyApk(target, u)
        _state.value = UpdateState.ReadyToInstall(u, target.absolutePath)
        target
    }

    private fun fetchShaAsset(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            r.body?.string()?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
        }
    }.getOrNull()

    /** Sprawdza packageName, versionCode i zgodność certyfikatu podpisu z zainstalowaną aplikacją. */
    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, u: AvailableUpdate) {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive: PackageInfo = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: fail(apk, "Plik nie jest poprawnym APK")
        if (archive.packageName != ctx.packageName) fail(apk, "Inny packageName: ${archive.packageName}")
        val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
        if (archiveCode <= BuildConfig.VERSION_CODE) fail(apk, "APK nie jest nowszy (versionCode $archiveCode) - odrzucono (brak downgrade)")
        if (archiveCode != u.versionCode.toLong()) fail(apk, "versionCode APK ($archiveCode) różni się od tagu (${u.versionCode})")
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        if (signatures(installed) != signatures(archive)) fail(apk, "APK podpisany innym kluczem niż zainstalowana aplikacja - odrzucono")
    }

    @Suppress("DEPRECATION")
    private fun signatures(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else p.signatures
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun fail(f: File, msg: String): Nothing {
        f.delete()
        _state.value = UpdateState.Failed(msg)
        throw SecurityException(msg)
    }

    fun setState(s: UpdateState) { _state.value = s }

    companion object {
        /** "v0.3.1+7" -> (0.3.1, 7). Dopuszczamy też "0.3.1+7". */
        val TAG_REGEX = Regex("^v?(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?)\\+(\\d+)$")
        val SHA_IN_BODY = Regex("SHA-?256:\\s*([0-9a-fA-F]{64})", RegexOption.IGNORE_CASE)
    }
}
