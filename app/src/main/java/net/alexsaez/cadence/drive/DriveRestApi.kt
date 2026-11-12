package net.alexsaez.cadence.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException

/**
 * Drive v3 REST client built on OkHttp. [baseUrl] points at https://www.googleapis.com/ in
 * production and at a MockWebServer in tests.
 */
class DriveRestApi(
    private val client: OkHttpClient,
    private val tokens: AccessTokenProvider,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) : DriveApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listChildren(folderId: String, foldersOnly: Boolean): List<DriveItem> {
        val query = buildString {
            if (folderId == DriveFolder.SHARED_WITH_ME.id) {
                append("sharedWithMe")
            } else {
                append("'").append(folderId.replace("'", "\\'")).append("' in parents")
            }
            append(" and trashed = false")
            if (foldersOnly) append(" and mimeType = '").append(DriveItem.FOLDER_MIME_TYPE).append("'")
        }
        val result = mutableListOf<DriveItem>()
        var pageToken: String? = null
        do {
            val url = baseUrl.newBuilder()
                .addPathSegments("drive/v3/files")
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size)")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("orderBy", "folder,name_natural")
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = authorized(url) { response ->
                json.decodeFromString<FileListDto>(response.body.string())
            }
            page.files.mapTo(result) {
                DriveItem(it.id, it.name, it.mimeType, it.size?.toLongOrNull())
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return result
    }

    override suspend fun download(
        fileId: String,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val url = baseUrl.newBuilder()
            .addPathSegments("drive/v3/files")
            .addPathSegment(fileId)
            .addQueryParameter("alt", "media")
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        authorized(url) { response ->
            destination.parentFile?.mkdirs()
            val temp = File(destination.parentFile, destination.name + ".part")
            val body = response.body
            val total = body.contentLength()
            var read = 0L
            try {
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            onProgress(read, total)
                        }
                    }
                }
                if (destination.exists()) destination.delete()
                if (!temp.renameTo(destination)) throw IOException("Could not move download into place")
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }

    override suspend fun fetchUser(accessToken: String): GoogleUser {
        val url = baseUrl.newBuilder()
            .addPathSegments("drive/v3/about")
            .addQueryParameter("fields", "user(displayName,emailAddress,photoLink)")
            .build()
        return withContext(Dispatchers.IO) {
            execute(url, accessToken).use { response ->
                if (!response.isSuccessful) {
                    throw DriveException("Drive profile request failed (${response.code})", response.code)
                }
                val user = json.decodeFromString<AboutDto>(response.body.string()).user
                GoogleUser(user.emailAddress.orEmpty(), user.displayName, user.photoLink)
            }
        }
    }

    /** Runs a GET with a bearer token, retrying once with a fresh token on HTTP 401. */
    private suspend fun <T> authorized(url: HttpUrl, handle: suspend (Response) -> T): T =
        withContext(Dispatchers.IO) {
            var token = tokens.accessToken()
            var response = execute(url, token)
            if (response.code == 401) {
                response.close()
                tokens.invalidate(token)
                token = tokens.accessToken()
                response = execute(url, token)
            }
            response.use {
                if (!it.isSuccessful) {
                    val detail = runCatching { it.body.string().take(300) }.getOrDefault("")
                    throw DriveException("Drive request failed (${it.code}) $detail".trim(), it.code)
                }
                handle(it)
            }
        }

    private fun execute(url: HttpUrl, token: String): Response = try {
        client.newCall(
            Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        ).execute()
    } catch (e: IOException) {
        throw DriveException("Network error: ${e.message}", cause = e)
    }

    @Serializable
    private data class FileListDto(
        val files: List<FileDto> = emptyList(),
        val nextPageToken: String? = null,
    )

    @Serializable
    private data class FileDto(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: String? = null,
    )

    @Serializable
    private data class AboutDto(val user: UserDto)

    @Serializable
    private data class UserDto(
        val displayName: String? = null,
        val emailAddress: String? = null,
        val photoLink: String? = null,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://www.googleapis.com/"
        const val SCOPE_DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
    }
}
