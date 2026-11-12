package net.alexsaez.cadence.drive

import kotlinx.coroutines.test.runTest
import net.alexsaez.cadence.fakes.FakeGoogleDrive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DriveRestApiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val drive = FakeGoogleDrive()
    private val tokens = object : AccessTokenProvider {
        var current = "test-token"
        val invalidated = mutableListOf<String>()
        var refreshed = "test-token"
        override suspend fun accessToken() = current
        override suspend fun invalidate(token: String) {
            invalidated += token
            current = refreshed
        }
    }
    private lateinit var api: DriveRestApi

    @Before
    fun setUp() {
        drive.start()
        api = DriveRestApi(OkHttpClient(), tokens, drive.baseUrl)
    }

    @After
    fun tearDown() = drive.shutdown()

    @Test
    fun `lists children across pages with the bearer token`() = runTest {
        val books = drive.folder("Books")
        repeat(5) { drive.audioFile("Track $it.mp3", parentId = books) }
        drive.folder("Sub", parentId = books)
        drive.pageSize = 2

        val items = api.listChildren(books)

        assertEquals(6, items.size)
        assertEquals(1, items.count { it.isFolder })
        assertTrue(items.filter { !it.isFolder }.all { it.isAudio && it.sizeBytes == 64L * 1024 })
        assertEquals(3, drive.requests.size)
        assertTrue(drive.requests.all { it.getHeader("Authorization") == "Bearer test-token" })
    }

    @Test
    fun `folders only query`() = runTest {
        val books = drive.folder("Books")
        drive.audioFile("a.mp3", parentId = books)
        drive.folder("Sub", parentId = books)
        assertEquals(listOf("Sub"), api.listChildren(books, foldersOnly = true).map { it.name })
    }

    @Test
    fun `expired token is invalidated and the request retried once`() = runTest {
        drive.folder("Books")
        tokens.current = "expired"
        tokens.refreshed = "test-token"

        val items = api.listChildren("root")

        assertEquals(listOf("Books"), items.map { it.name })
        assertEquals(listOf("expired"), tokens.invalidated)
    }

    @Test
    fun `downloads file content with progress`() = runTest {
        val id = drive.audioFile("big.mp3", sizeBytes = 300_000)
        val dest = tmp.newFile("big.mp3").also { it.delete() }
        var lastProgress = 0L to 0L

        api.download(id, dest) { read, total -> lastProgress = read to total }

        assertArrayEquals(drive.contentOf(id), dest.readBytes())
        assertEquals(300_000L to 300_000L, lastProgress)
        assertTrue(dest.parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun `http errors surface as DriveException`() = runTest {
        drive.failListingWith = 500
        try {
            api.listChildren("root")
            fail("expected exception")
        } catch (e: DriveException) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun `fetches the signed in user`() = runTest {
        val user = api.fetchUser("test-token")
        assertEquals(GoogleUser("reader@example.com", "Test Reader"), user)
    }
}
