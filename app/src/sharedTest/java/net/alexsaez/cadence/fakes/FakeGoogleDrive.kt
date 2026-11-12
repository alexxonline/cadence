package net.alexsaez.cadence.fakes

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.alexsaez.cadence.drive.DriveItem
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory Google Drive served over HTTP by [MockWebServer], speaking the subset of the
 * Drive v3 REST protocol the app uses (files.list with `q`, files.get?alt=media, about).
 */
class FakeGoogleDrive {
    data class Node(
        val id: String,
        val name: String,
        val mimeType: String,
        val parentId: String?,
        val content: ByteArray? = null,
        val sharedWithMe: Boolean = false,
    )

    private val nodes = CopyOnWriteArrayList<Node>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val ids = AtomicInteger()

    /** Bearer token accepted by the server; anything else gets HTTP 401. */
    @Volatile
    var validToken = "test-token"

    /** Max items per files.list page, to exercise pagination. */
    @Volatile
    var pageSize = 1000

    @Volatile
    var userEmail = "reader@example.com"

    @Volatile
    var userName = "Test Reader"

    /** When set, files.list fails with this HTTP status. */
    @Volatile
    var failListingWith: Int? = null

    val server = MockWebServer().apply { dispatcher = DriveDispatcher() }

    fun start() = server.start()
    fun shutdown() = server.shutdown()
    val baseUrl get() = server.url("/")

    fun folder(name: String, parentId: String = "root", shared: Boolean = false, id: String = newId("folder")): String {
        nodes += Node(id, name, DriveItem.FOLDER_MIME_TYPE, parentId, sharedWithMe = shared)
        return id
    }

    fun audioFile(
        name: String,
        parentId: String = "root",
        sizeBytes: Int = 64 * 1024,
        mimeType: String = "audio/mpeg",
        id: String = newId("audio"),
    ): String {
        nodes += Node(id, name, mimeType, parentId, content = contentFor(id, sizeBytes))
        return id
    }

    fun otherFile(name: String, parentId: String = "root", mimeType: String = "text/plain"): String {
        val id = newId("file")
        nodes += Node(id, name, mimeType, parentId, content = "not audio".toByteArray())
        return id
    }

    fun contentOf(id: String): ByteArray = nodes.first { it.id == id }.content!!

    fun downloadRequests(): List<RecordedRequest> =
        requests.filter { it.requestUrl?.queryParameter("alt") == "media" }

    private fun newId(prefix: String) = "$prefix-${ids.incrementAndGet()}"

    private fun contentFor(id: String, size: Int) = ByteArray(size) { i -> ((i * 31 + id.hashCode()) and 0xFF).toByte() }

    private inner class DriveDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            if (request.getHeader("Authorization") != "Bearer $validToken") {
                return json(401, """{"error":{"code":401,"message":"Invalid Credentials"}}""")
            }
            val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
            val path = url.encodedPath
            return when {
                path == "/drive/v3/about" -> json(
                    200,
                    buildJsonObject {
                        putJsonObject("user") {
                            put("displayName", userName)
                            put("emailAddress", userEmail)
                        }
                    }.toString(),
                )
                path == "/drive/v3/files" -> listFiles(url.queryParameter("q").orEmpty(), url.queryParameter("pageToken"))
                path.startsWith("/drive/v3/files/") && url.queryParameter("alt") == "media" -> {
                    val id = path.removePrefix("/drive/v3/files/")
                    val node = nodes.firstOrNull { it.id == id && it.content != null }
                        ?: return json(404, """{"error":{"code":404,"message":"File not found"}}""")
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", node.mimeType)
                        .setBody(Buffer().write(node.content!!))
                }
                else -> json(404, """{"error":{"code":404}}""")
            }
        }

        private fun listFiles(q: String, pageToken: String?): MockResponse {
            failListingWith?.let { return json(it, """{"error":{"code":$it}}""") }
            val foldersOnly = q.contains("mimeType = '${DriveItem.FOLDER_MIME_TYPE}'")
            val parent = Regex("'([^']+)' in parents").find(q)?.groupValues?.get(1)
            val matches = nodes.filter { n ->
                (if (q.startsWith("sharedWithMe")) n.sharedWithMe else n.parentId == parent) &&
                    (!foldersOnly || n.mimeType == DriveItem.FOLDER_MIME_TYPE)
            }
            val start = pageToken?.toInt() ?: 0
            val page = matches.drop(start).take(pageSize)
            val next = if (start + page.size < matches.size) (start + page.size).toString() else null
            return json(200, buildJsonObject {
                putJsonArray("files") {
                    page.forEach { n ->
                        addJsonObject {
                            put("id", n.id)
                            put("name", n.name)
                            put("mimeType", n.mimeType)
                            n.content?.let { put("size", it.size.toString()) }
                        }
                    }
                }
                next?.let { put("nextPageToken", it) }
            }.toString())
        }

        private fun json(code: Int, body: String) = MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }
}
