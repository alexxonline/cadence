package net.alexsaez.cadence.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.alexsaez.cadence.download.DownloadRepository
import net.alexsaez.cadence.drive.DriveApi
import net.alexsaez.cadence.drive.DriveItem
import net.alexsaez.cadence.drive.GoogleUser
import net.alexsaez.cadence.fakes.FakeAudioPlayer
import net.alexsaez.cadence.fakes.InMemoryPlaybackStore
import net.alexsaez.cadence.library.AudioTrack
import net.alexsaez.cadence.library.Audiobook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val minute = 60_000L
    private val player = FakeAudioPlayer { 10 * minute }
    private val store = InMemoryPlaybackStore()

    private val single = Audiobook("file:a", "Moby Dick", listOf(AudioTrack("a", "Moby Dick.mp3", "audio/mpeg")))
    private val multi = Audiobook(
        "folder:d", "Dune",
        listOf(
            AudioTrack("d1", "Dune 1.mp3", "audio/mpeg"),
            AudioTrack("d2", "Dune 2.mp3", "audio/mpeg"),
            AudioTrack("d3", "Dune 3.mp3", "audio/mpeg"),
        ),
    )

    private val noDrive = object : DriveApi {
        override suspend fun listChildren(folderId: String, foldersOnly: Boolean) = emptyList<DriveItem>()
        override suspend fun download(fileId: String, destination: File, onProgress: (Long, Long) -> Unit) {
            destination.writeText("audio")
        }
        override suspend fun fetchUser(accessToken: String) = GoogleUser("x")
    }

    private fun TestScope.controller(downloaded: List<Audiobook> = listOf(single, multi)): PlaybackController {
        val dir = tmp.newFolder()
        downloaded.flatMap { it.tracks }.forEach { File(dir, "${it.id}.mp3").writeText("audio") }
        val downloads = DownloadRepository(noDrive, dir, backgroundScope)
        return PlaybackController(player, store, downloads, backgroundScope) { it.path }
    }

    @Test
    fun `refuses to play a book that is not downloaded`() = runTest {
        val c = controller(downloaded = emptyList())
        val result = runCatching { c.play(single) }
        assertTrue(result.exceptionOrNull() is NotDownloadedException)
    }

    @Test
    fun `plays local files and remembers last played and position`() = runTest {
        val c = controller()
        c.play(single)
        runCurrent()

        assertTrue(player.state.value.isPlaying)
        assertTrue(player.queue.single().uri.endsWith("a.mp3"))
        player.advanceBy(65_000)
        runCurrent()
        c.pause()
        runCurrent()

        assertEquals(65_000L, store.position("a")!!.positionMs)
        assertEquals("file:a", store.lastPlayed.first()!!.bookId)
        assertEquals(65_000L, c.nowPlaying.value!!.positionMs)
    }

    @Test
    fun `position is saved periodically while playing`() = runTest {
        val c = controller()
        c.play(single)
        runCurrent()
        player.advanceBy(3_000)
        runCurrent()
        player.advanceBy(3_000)
        runCurrent()
        assertEquals(6_000L, store.position("a")!!.positionMs)
    }

    @Test
    fun `resumes a book from its saved position`() = runTest {
        store.savePosition("d2", 42_000, 10 * minute)
        store.setLastPlayed("folder:d", "d2")
        val c = controller()

        c.play(multi)
        runCurrent()

        assertEquals(1 to 42_000L, player.lastQueueStart)
        assertEquals(listOf("d1", "d2", "d3"), player.queue.map { it.id })
    }

    @Test
    fun `moving to the next file marks the previous one finished and remembers each file`() =
        runTest {
            val c = controller()
            c.play(multi)
            runCurrent()
            player.advanceBy(10 * minute - 1_000)
            runCurrent()
            player.advanceBy(31_000)
            runCurrent()
            c.pause()
            runCurrent()

            assertTrue(store.position("d1")!!.isFinished)
            assertEquals(30_000L, store.position("d2")!!.positionMs)
            assertEquals("d2", store.lastTrackOf("folder:d"))
            assertEquals(1, c.nowPlaying.value!!.trackIndex)
        }

    @Test
    fun `switching books saves the position of the previous book`() = runTest {
        val c = controller()
        c.play(single)
        runCurrent()
        player.advanceBy(2_000)
        runCurrent()
        c.play(multi)
        runCurrent()
        assertEquals(2_000L, store.position("a")!!.positionMs)
        assertEquals("folder:d", store.lastPlayed.first()!!.bookId)
    }

    @Test
    fun `selecting a finished file starts it from the beginning`() = runTest {
        store.savePosition("d1", 10 * minute, 10 * minute)
        val c = controller()
        c.play(multi, trackIndex = 0)
        runCurrent()
        assertEquals(0 to 0L, player.lastQueueStart)
    }

    @Test
    fun `headphones unplugged pauses and saves position`() = runTest {
        val c = controller()
        c.play(single)
        runCurrent()
        player.advanceBy(12_345)
        runCurrent()
        player.simulateHeadphonesUnplugged()
        runCurrent()
        assertFalse(player.state.value.isPlaying)
        assertEquals(12_345L, store.position("a")!!.positionMs)
        assertFalse(c.nowPlaying.value!!.isPlaying)
    }

    @Test
    fun `skipping a file manually does not mark it finished`() = runTest {
        val c = controller()
        c.play(multi)
        runCurrent()
        player.advanceBy(20_000)
        runCurrent()
        c.nextTrack()
        runCurrent()
        assertFalse(store.position("d1")!!.isFinished)
        assertEquals(20_000L, store.position("d1")!!.positionMs)
    }

    @Test
    fun `seek and track navigation`() = runTest {
        val c = controller()
        c.play(multi)
        runCurrent()
        c.seekBy(30_000)
        runCurrent()
        assertEquals(30_000L, player.state.value.positionMs)
        c.seekBy(-60_000)
        runCurrent()
        assertEquals(0L, player.state.value.positionMs)
        c.nextTrack()
        runCurrent()
        assertEquals(1, c.nowPlaying.value!!.trackIndex)
        c.previousTrack()
        runCurrent()
        assertEquals(0, c.nowPlaying.value!!.trackIndex)
    }
}
