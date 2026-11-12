package net.alexsaez.cadence.drive

import kotlinx.serialization.Serializable

/** A file or folder in Google Drive. */
@Serializable
data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long? = null,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME_TYPE

    val isAudio: Boolean
        get() = !isFolder && (mimeType.startsWith("audio/") ||
            name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS)

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "mka", "amr",
        )
    }
}

/** A folder the user picked as the root of their audiobook library. */
@Serializable
data class DriveFolder(val id: String, val name: String) {
    companion object {
        /** Drive alias for the user's "My Drive" root. */
        val MY_DRIVE = DriveFolder("root", "My Drive")

        /** Virtual folder listing folders shared with the user. */
        val SHARED_WITH_ME = DriveFolder("sharedWithMe", "Shared with me")
    }
}

@Serializable
data class GoogleUser(
    val email: String,
    val displayName: String? = null,
    val photoUrl: String? = null,
)

class DriveException(message: String, val code: Int = 0, cause: Throwable? = null) :
    Exception(message, cause)
