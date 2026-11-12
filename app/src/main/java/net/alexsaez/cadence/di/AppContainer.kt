package net.alexsaez.cadence.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.alexsaez.cadence.auth.AuthRepository
import net.alexsaez.cadence.auth.GoogleAuthRepository
import net.alexsaez.cadence.download.DownloadRepository
import net.alexsaez.cadence.drive.AccessTokenProvider
import net.alexsaez.cadence.drive.DriveApi
import net.alexsaez.cadence.drive.DriveRestApi
import net.alexsaez.cadence.library.LibraryRepository
import net.alexsaez.cadence.player.AudioPlayer
import net.alexsaez.cadence.player.Media3AudioPlayer
import net.alexsaez.cadence.player.PlaybackController
import net.alexsaez.cadence.storage.DataStorePlaybackStore
import net.alexsaez.cadence.storage.PlaybackStore
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container. The three "outside world" dependencies — Google sign-in, Google
 * Drive and the audio hardware — are constructor parameters so tests can replace them.
 */
class AppContainer(
    val authRepository: AuthRepository,
    val driveApi: DriveApi,
    val audioPlayer: AudioPlayer,
    val playbackStore: PlaybackStore,
    storageDir: File,
    val appScope: CoroutineScope,
) {
    val libraryRepository = LibraryRepository(driveApi, File(storageDir, "library"))
    val downloadRepository = DownloadRepository(driveApi, File(storageDir, "audiobooks"), appScope)
    val playbackController = PlaybackController(audioPlayer, playbackStore, downloadRepository, appScope)

    companion object {
        fun production(context: Context): AppContainer {
            val app = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val http = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            // Drive needs tokens from auth, and auth loads the user profile from Drive.
            lateinit var auth: AuthRepository
            val tokens = object : AccessTokenProvider {
                override suspend fun accessToken() = auth.accessToken()
                override suspend fun invalidate(token: String) = auth.invalidate(token)
            }
            val drive = DriveRestApi(http, tokens)
            auth = GoogleAuthRepository(app) { token -> drive.fetchUser(token) }
            val store = DataStorePlaybackStore(
                PreferenceDataStoreFactory.create { app.preferencesDataStoreFile("playback") }
            )
            return AppContainer(
                authRepository = auth,
                driveApi = drive,
                audioPlayer = Media3AudioPlayer(app, scope),
                playbackStore = store,
                storageDir = app.filesDir,
                appScope = scope,
            )
        }
    }
}
