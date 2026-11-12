package net.alexsaez.cadence

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import net.alexsaez.cadence.di.AppContainer
import net.alexsaez.cadence.drive.DriveFolder
import net.alexsaez.cadence.drive.DriveRestApi
import net.alexsaez.cadence.drive.GoogleUser
import net.alexsaez.cadence.fakes.FakeAudioPlayer
import net.alexsaez.cadence.fakes.FakeAuthRepository
import net.alexsaez.cadence.fakes.FakeGoogleDrive
import net.alexsaez.cadence.storage.DataStorePlaybackStore
import okhttp3.OkHttpClient
import org.junit.rules.ExternalResource
import java.io.File
import java.util.UUID

/**
 * Builds the real [AppContainer] (real Drive REST client, downloads, DataStore persistence,
 * playback controller and UI) but with the outside world replaced:
 * - Google sign-in -> [FakeAuthRepository]
 * - Google Drive -> [FakeGoogleDrive], an HTTP server speaking the Drive v3 protocol
 * - Audio hardware -> [FakeAudioPlayer]
 */
class TestEnvironment : ExternalResource() {
    val app: TestCadenceApp = ApplicationProvider.getApplicationContext()
    val drive = FakeGoogleDrive()

    lateinit var auth: FakeAuthRepository
        private set
    lateinit var player: FakeAudioPlayer
        private set
    lateinit var container: AppContainer
        private set

    lateinit var storageDir: File
        private set

    private var appJob: Job? = null
    private var dataStoreJob: Job? = null

    val user = GoogleUser("reader@example.com", "Test Reader")

    override fun before() {
        drive.start()
        storageDir = File(app.filesDir, "test-${UUID.randomUUID()}")
        buildContainer(FakeAuthRepository())
    }

    override fun after() {
        shutdownContainer()
        app.testContainer = null
        drive.shutdown()
        storageDir.deleteRecursively()
    }

    /** Pretend the user signed in and chose [folder] during an earlier run of the app. */
    fun givenSignedInWithFolder(folder: DriveFolder) {
        auth.rememberedUser = user
        runBlocking { container.playbackStore.setSelectedFolder(folder) }
    }

    /**
     * Simulates the app process being killed and launched again: every in-memory object is
     * thrown away and rebuilt; only what the app stored on disk survives.
     */
    fun restartApp() {
        val remembered = auth.rememberedUser
        shutdownContainer()
        buildContainer(FakeAuthRepository(rememberedUser = remembered))
    }

    val downloadsDir get() = File(storageDir, "audiobooks")

    private fun buildContainer(fakeAuth: FakeAuthRepository) {
        val appJob = SupervisorJob().also { this.appJob = it }
        val dataStoreJob = SupervisorJob().also { this.dataStoreJob = it }
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + dataStoreJob),
        ) { File(storageDir, "playback.preferences_pb") }
        val api = DriveRestApi(OkHttpClient(), fakeAuth, drive.baseUrl)
        fakeAuth.profileLoader = { token -> api.fetchUser(token) }
        auth = fakeAuth
        player = FakeAudioPlayer { 10 * 60_000L }
        container = AppContainer(
            authRepository = fakeAuth,
            driveApi = api,
            audioPlayer = player,
            playbackStore = DataStorePlaybackStore(dataStore),
            storageDir = storageDir,
            appScope = CoroutineScope(appJob + Dispatchers.Main.immediate),
        )
        app.testContainer = container
    }

    private fun shutdownContainer() = runBlocking {
        appJob?.cancelAndJoin()
        dataStoreJob?.cancelAndJoin()
    }
}
