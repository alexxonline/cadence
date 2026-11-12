package net.alexsaez.cadence

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.alexsaez.cadence.drive.DriveFolder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end tests of the whole app — UI, view models, Drive REST client, downloads,
 * persistence and playback logic — with Google sign-in, Google Drive and the audio hardware
 * replaced by fakes (see [TestEnvironment]).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AudiobookAppTest {

    private val env = TestEnvironment()
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(env).around(compose)

    private var scenario: ActivityScenario<MainActivity>? = null

    // Drive content shared by the tests.
    private lateinit var audiobooksFolder: String
    private lateinit var mobyDick: String
    private lateinit var dune1: String
    private lateinit var dune2: String
    private lateinit var dune10: String

    @Before
    fun createDriveContent() {
        val drive = env.drive
        audiobooksFolder = drive.folder("Audiobooks")
        drive.folder("Photos")
        // Single-file audiobook.
        mobyDick = drive.audioFile("Moby Dick.m4b", audiobooksFolder, sizeBytes = 300_000, mimeType = "audio/mp4")
        // Multi-file audiobook from numbered files next to each other.
        drive.audioFile("The Hobbit - Part 2.mp3", audiobooksFolder)
        drive.audioFile("The Hobbit - Part 1.mp3", audiobooksFolder)
        // Multi-file audiobook from a folder; created out of order on purpose.
        val dune = drive.folder("Dune", audiobooksFolder)
        dune10 = drive.audioFile("Dune 10.mp3", dune)
        dune2 = drive.audioFile("Dune 2.mp3", dune)
        dune1 = drive.audioFile("Dune 1.mp3", dune)
        // Multi-disc audiobook.
        val foundation = drive.folder("Foundation", audiobooksFolder)
        drive.audioFile("01.mp3", drive.folder("CD 1", foundation))
        drive.audioFile("01.mp3", drive.folder("CD 2", foundation))
        // Not audio: ignored.
        drive.otherFile("notes.txt", audiobooksFolder)
    }

    @After
    fun closeApp() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun restartApp() {
        scenario?.close()
        env.restartApp()
        launch()
    }

    private fun signedInWithAudiobooksFolder() {
        env.givenSignedInWithFolder(DriveFolder(audiobooksFolder, "Audiobooks"))
    }

    // ---------------------------------------------------------------------------------------

    @Test
    fun signInWithGoogle_pickDriveFolder_listsAudiobooks() {
        launch()

        compose.waitFor(hasTestTag("login_screen"))
        compose.onNodeWithTag("sign_in_button").performClick()

        // Folder picker starts at the Drive roots.
        compose.waitFor(hasText("My Drive"))
        compose.onNodeWithText("Shared with me").assertIsDisplayed()
        compose.onNodeWithText("My Drive").performClick()

        compose.waitFor(hasText("Audiobooks"))
        compose.onNodeWithText("Photos").assertIsDisplayed()
        compose.onNodeWithText("Audiobooks").performClick()
        compose.waitFor(hasText("Dune"))
        compose.onNodeWithTag("use_folder_button").assertTextContains("Audiobooks", substring = true).performClick()

        // Library: single file, numbered files, folder and multi-disc audiobooks.
        compose.waitFor(hasTestTag("book:Moby Dick"))
        compose.onNodeWithTag("library_folder_name").assertTextContains("Audiobooks")
        compose.onNodeWithTag("book:Moby Dick").assertTextContains("Single file", substring = true)
        compose.onNodeWithTag("book:Dune").assertTextContains("3 files", substring = true)
        compose.onNodeWithTag("book:The Hobbit").assertTextContains("2 files", substring = true)
        compose.onNodeWithTag("book:Foundation").assertTextContains("2 files", substring = true)
        compose.assertNotExists(hasText("notes", substring = true))
        compose.assertNotExists(hasText("Photos"))

        assertEquals(1, env.auth.signInCalls.get())
        assertEquals("reader@example.com", env.auth.currentUser?.email)
        // Every Drive call carried the OAuth token from the sign-in.
        assertTrue(env.drive.requests.all { it.getHeader("Authorization") == "Bearer test-token" })
        // The chosen folder is remembered.
        assertEquals(DriveFolder(audiobooksFolder, "Audiobooks"), runBlocking { env.container.playbackStore.selectedFolder.first() })
    }

    @Test
    fun signInFailure_showsErrorAndStaysOnLogin() {
        env.auth.signInError = "Sign-in was cancelled"
        launch()

        compose.waitFor(hasTestTag("sign_in_button"))
        compose.onNodeWithTag("sign_in_button").performClick()

        compose.waitFor(hasTestTag("login_error"))
        compose.onNodeWithTag("login_error").assertTextContains("cancelled", substring = true)
        compose.onNodeWithTag("sign_in_button").assertIsDisplayed()
    }

    @Test
    fun downloadSingleFileBook_playLocally_andRememberPositionAcrossRestarts() {
        signedInWithAudiobooksFolder()
        launch()

        compose.waitFor(hasTestTag("book:Moby Dick"))
        compose.onNodeWithTag("book:Moby Dick").performClick()

        // Download.
        compose.waitFor(hasTestTag("download_button"))
        compose.onNodeWithTag("download_button").performClick()
        compose.waitFor(hasTestTag("downloaded_label"))
        val localFile = File(env.downloadsDir, "$mobyDick.m4b")
        assertArrayEquals(env.drive.contentOf(mobyDick), localFile.readBytes())

        // Play from the local copy.
        compose.onNodeWithTag("play_pause").performClick()
        compose.waitUntil(TIMEOUT) { env.player.state.value.isPlaying }
        assertEquals(android.net.Uri.fromFile(localFile).toString(), env.player.queue.single().uri)

        // The (fake) speaker plays 65 seconds, then the user pauses.
        env.player.advanceBy(65_000)
        compose.waitFor(hasTestTag("position_text") and hasText("1:05"))
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.waitUntil(TIMEOUT) { !env.player.state.value.isPlaying }
        compose.waitUntil(TIMEOUT) { env.savedPosition(mobyDick) == 65_000L }

        // Kill and relaunch the app: only disk state survives.
        restartApp()

        compose.waitFor(hasTestTag("continue_listening"))
        compose.onNodeWithTag("continue_listening")
            .assertTextContains("Moby Dick", substring = true)
            .assertTextContains("1:05", substring = true)
        compose.onNodeWithTag("continue_listening").performClick()

        compose.waitFor(hasTestTag("resume_hint"))
        compose.onNodeWithTag("position_text").assertTextContains("1:05")
        compose.onNodeWithTag("play_pause").performClick()
        compose.waitUntil(TIMEOUT) { env.player.state.value.isPlaying }
        assertEquals(0 to 65_000L, env.player.lastQueueStart)
        // Nothing was downloaded again.
        assertEquals(1, env.drive.downloadRequests().size)
    }

    @Test
    fun multiFileBook_playsFilesInNameSequence_andRemembersEachFile() {
        signedInWithAudiobooksFolder()
        launch()

        compose.waitFor(hasTestTag("book:Dune"))
        compose.onNodeWithContentDescription("Download Dune").performClick()
        compose.waitFor(hasContentDescription("Dune downloaded"))
        assertEquals(3, env.drive.downloadRequests().size)

        compose.onNodeWithTag("book:Dune").performClick()
        compose.waitFor(hasTestTag("track_2"))
        compose.onNodeWithTag("track_0").assertTextContains("Dune 1")
        compose.onNodeWithTag("track_1").assertTextContains("Dune 2")
        compose.onNodeWithTag("track_2").assertTextContains("Dune 10")

        compose.onNodeWithTag("play_pause").performClick()
        compose.waitUntil(TIMEOUT) { env.player.state.value.isPlaying }
        assertEquals(listOf(dune1, dune2, dune10), env.player.queue.map { it.id })
        compose.onNodeWithTag("current_track").assertTextContains("File 1 of 3", substring = true)

        // Play through the end of the first file and 30 s into the second one.
        env.player.advanceBy(10 * 60_000L - 1_000)
        compose.waitUntil(TIMEOUT) { (env.savedPosition(dune1) ?: 0) >= 10 * 60_000L - 1_000 }
        env.player.advanceBy(31_000)
        compose.waitFor(hasTestTag("current_track") and hasText("File 2 of 3", substring = true))
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.waitUntil(TIMEOUT) { env.savedPosition(dune2) == 30_000L }
        compose.waitFor(hasTestTag("track_0") and hasText("Finished"))

        // Jump to the last file, listen a bit: every file keeps its own position.
        compose.onNodeWithTag("track_2").performClick()
        compose.waitUntil(TIMEOUT) { env.player.state.value.currentItemId == dune10 && env.player.state.value.isPlaying }
        env.player.advanceBy(7_000)
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.waitUntil(TIMEOUT) { env.savedPosition(dune10) == 7_000L }
        compose.waitFor(hasTestTag("track_1") and hasText("Stopped at 0:30"))

        // Back to file 2 resumes it at 0:30.
        compose.onNodeWithTag("track_1").performClick()
        compose.waitUntil(TIMEOUT) {
            env.player.state.value.currentItemId == dune2 && env.player.state.value.positionMs == 30_000L
        }
        compose.onNodeWithContentDescription("Pause").performClick()

        restartApp()

        compose.waitFor(hasTestTag("continue_listening") and hasText("Dune", substring = true))
        compose.onNodeWithTag("continue_listening").assertTextContains("File 2 of 3 · 0:30", substring = true)
        // Resume straight from the library card.
        compose.onNodeWithTag("continue_play").performClick()
        compose.waitUntil(TIMEOUT) { env.player.state.value.isPlaying }
        assertEquals(1 to 30_000L, env.player.lastQueueStart)
    }

    @Test
    fun headphonesUnplugged_pausesPlaybackAndSavesPosition() {
        signedInWithAudiobooksFolder()
        launch()

        compose.waitFor(hasTestTag("book:Moby Dick"))
        compose.onNodeWithTag("book:Moby Dick").performClick()
        compose.waitFor(hasTestTag("download_button"))
        compose.onNodeWithTag("download_button").performClick()
        compose.waitFor(hasTestTag("downloaded_label"))
        compose.onNodeWithTag("play_pause").performClick()
        compose.waitUntil(TIMEOUT) { env.player.state.value.isPlaying }

        env.player.advanceBy(20_000)
        env.player.simulateHeadphonesUnplugged()

        compose.waitFor(hasContentDescription("Play"))
        compose.waitUntil(TIMEOUT) { env.savedPosition(mobyDick) == 20_000L }
        compose.onNodeWithTag("position_text").assertTextContains("0:20")
    }

    @Test
    fun driveErrors_areReported_andCachedLibraryStillShown() {
        signedInWithAudiobooksFolder()
        launch()
        compose.waitFor(hasTestTag("book:Dune"))
        compose.waitUntil(TIMEOUT) { File(env.storageDir, "library").listFiles()?.isNotEmpty() == true }

        // Drive goes down; the app restarts and must still show the cached library.
        env.drive.failListingWith = 503
        restartApp()

        compose.waitFor(hasText("Could not refresh the library", substring = true))
        compose.onNodeWithTag("book:Dune").assertIsDisplayed()
        compose.onNodeWithTag("book:Moby Dick").assertIsDisplayed()
    }

    @Test
    fun signOut_returnsToLogin_andRemovesDownloads() {
        signedInWithAudiobooksFolder()
        launch()

        compose.waitFor(hasTestTag("book:Moby Dick"))
        compose.onNodeWithContentDescription("Download Moby Dick").performClick()
        compose.waitFor(hasContentDescription("Moby Dick downloaded"))
        assertTrue(env.downloadsDir.listFiles()!!.isNotEmpty())

        compose.onNodeWithContentDescription("Sign out").performClick()

        compose.waitFor(hasTestTag("login_screen"))
        assertEquals(1, env.auth.signOutCalls.get())
        compose.waitUntil(TIMEOUT) { env.downloadsDir.listFiles().isNullOrEmpty() }
        assertNull(runBlocking { env.container.playbackStore.selectedFolder.first() })
    }

    // ---------------------------------------------------------------------------------------

    private fun TestEnvironment.savedPosition(trackId: String): Long? =
        runBlocking { container.playbackStore.position(trackId)?.positionMs }

    private fun ComposeTestRule.waitFor(matcher: SemanticsMatcher) =
        waitUntil(TIMEOUT) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeTestRule.assertNotExists(matcher: SemanticsMatcher) =
        assertTrue(onAllNodes(matcher).fetchSemanticsNodes().isEmpty())

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
