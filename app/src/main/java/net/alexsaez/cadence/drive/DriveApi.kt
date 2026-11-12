package net.alexsaez.cadence.drive

import java.io.File

/** Minimal subset of the Google Drive v3 API that the app needs. */
interface DriveApi {
    /** Lists the direct children of [folderId]. Use [foldersOnly] to restrict to folders. */
    suspend fun listChildren(folderId: String, foldersOnly: Boolean = false): List<DriveItem>

    /** Downloads the content of [fileId] into [destination], reporting (bytesRead, totalBytes). */
    suspend fun download(
        fileId: String,
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    )

    /** Fetches the profile of the user owning [accessToken]. */
    suspend fun fetchUser(accessToken: String): GoogleUser
}

/** Supplies OAuth access tokens for Drive requests. */
interface AccessTokenProvider {
    suspend fun accessToken(): String

    /** Called when the server rejected [token] so a fresh one is obtained next time. */
    suspend fun invalidate(token: String)
}
