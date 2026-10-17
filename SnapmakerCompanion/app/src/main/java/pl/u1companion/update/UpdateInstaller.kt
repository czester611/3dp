package pl.u1companion.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Instalacja przez PackageInstaller (sesja). Dla aplikacji z sideloadu system zawsze pokaże
 * okno potwierdzenia (STATUS_PENDING_USER_ACTION) - nie da się (i nie chcemy) instalować po cichu.
 * Wymaga uprawnienia REQUEST_INSTALL_PACKAGES oraz zgody "Instaluj nieznane aplikacje" dla tej aplikacji.
 */
@Singleton
class UpdateInstaller @Inject constructor(@ApplicationContext private val ctx: Context) {

    fun canRequestInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Intencja do ekranu systemowego "Zezwalaj z tego źródła". */
    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pi = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
            session.commit(pi.intentSender)
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "pl.u1companion.update.INSTALL_RESULT"
    }
}

/** Odbiera status sesji instalacji; przy PENDING_USER_ACTION uruchamia systemowe okno potwierdzenia. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("U1Update", "Aktualizacja zainstalowana")
            else -> Log.w("U1Update", "Instalacja nieudana: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            // TODO: przekazać wynik do UpdateRepository.state (np. przez singleton/EntryPoint) i pokazać w UI
        }
    }
}
