package pl.u1companion.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Schemat wersjonowania (patrz docs/DESIGN.md, sekcja OTA):
 *  - versionCode: rosnąca liczba całkowita (jedyne kryterium "nowsza/starsza").
 *  - versionName: SemVer, np. 0.3.1
 *  - Tag release na GitHub: "v<versionName>+<versionCode>", np. "v0.3.1+7"
 *  - Asset APK: "U1Companion-<versionName>-<versionCode>.apk"
 *  - Suma kontrolna: asset "<nazwa APK>.sha256" (hex) LUB linia "SHA-256: <hex>" w opisie release.
 */
data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    val tag: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long?,
    val sha256: String?,
    val sha256AssetUrl: String?,
    val releaseNotes: String,
    val htmlUrl: String,
)

sealed interface UpdateCheckResult {
    data class Available(val update: AvailableUpdate) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class NotConfigured(val reason: String) : UpdateCheckResult
    data class Error(val message: String, val cause: Throwable? = null) : UpdateCheckResult
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Downloading(val update: AvailableUpdate, val fraction: Float) : UpdateState
    data class Verifying(val update: AvailableUpdate) : UpdateState
    data class ReadyToInstall(val update: AvailableUpdate, val apkPath: String) : UpdateState
    data class Installing(val update: AvailableUpdate) : UpdateState
    data class Failed(val message: String) : UpdateState
}

@Serializable
internal data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
internal data class GhAsset(
    val name: String,
    val size: Long? = null,
    @SerialName("browser_download_url") val downloadUrl: String,
    @SerialName("content_type") val contentType: String? = null,
    /** GitHub od 2025 r. udostępnia "digest": "sha256:<hex>" dla assetów (jeśli obecne - używamy). */
    val digest: String? = null,
)
