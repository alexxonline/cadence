package net.alexsaez.cadence.player

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Smoke test of the production player: Media3 controller -> PlaybackService -> ExoPlayer
 * decoding a real local file on the device. (The end-to-end tests use a fake player instead.)
 */
@RunWith(AndroidJUnit4::class)
class Media3AudioPlayerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val player = Media3AudioPlayer(context, scope)
    private val dir = File(context.cacheDir, "media3-test").apply { mkdirs() }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { player.stop() }
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun playsLocalFilesInOrderAndResumesAtPosition() = runBlocking {
        val first = silentWav(File(dir, "a.wav"), seconds = 4)
        val second = silentWav(File(dir, "b.wav"), seconds = 4)
        val items = listOf(
            PlayableItem("a", Uri.fromFile(first).toString(), "Part 1", "Book"),
            PlayableItem("b", Uri.fromFile(second).toString(), "Part 2", "Book"),
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player.setQueue(items, startIndex = 1, startPositionMs = 1_000)
            player.play()
        }

        val playing = withTimeout(15_000) { player.state.first { it.isPlaying && it.currentItemId == "b" } }
        assertEquals(1, playing.currentIndex)
        assertTrue("starts at the requested position", playing.positionMs >= 1_000)
        assertEquals(4_000L, playing.durationMs)

        val advanced = withTimeout(10_000) { player.state.first { it.positionMs >= 2_000 } }
        assertTrue(advanced.isPlaying)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { player.pause() }
        withTimeout(5_000) { player.state.first { !it.isPlaying } }
        Unit
    }

    /** Writes a mono 8 kHz 16-bit PCM WAV file of silence. */
    private fun silentWav(file: File, seconds: Int): File {
        val sampleRate = 8_000
        val data = ByteArray(sampleRate * 2 * seconds)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }.array()
        file.writeBytes(ByteArrayOutputStream().apply { write(header); write(data) }.toByteArray())
        return file
    }
}
