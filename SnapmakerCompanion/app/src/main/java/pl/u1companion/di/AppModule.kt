package pl.u1companion.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import pl.u1companion.BuildConfig
import pl.u1companion.data.local.AppDatabase
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Klient do urządzeń w LAN (drukarka, serwer slicingu): długie timeouty, bez cache. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LanClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** Klient do internetu (serwisy z modelami). */
    @Provides
    @Singleton
    fun okHttp(@ApplicationContext ctx: Context): OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(ctx.cacheDir, "http"), 50L * 1024 * 1024))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "U1Companion/${BuildConfig.VERSION_NAME} (Android; private use)").build())
        }
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
        .build()

    @Provides
    @Singleton
    @LanClient
    fun lanOkHttp(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .cache(null)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)   // slicing / duże uploady
        .writeTimeout(10, TimeUnit.MINUTES)
        .pingInterval(20, TimeUnit.SECONDS)  // WebSocket Moonraker
        .build()

    @Provides
    @Singleton
    fun db(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "u1companion.db").build()
}
