package net.alexsaez.cadence.library

import kotlinx.serialization.Serializable

/** One audio file of an audiobook. */
@Serializable
data class AudioTrack(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long? = null,
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val title: String get() = name.substringBeforeLast('.')
}

/**
 * An audiobook: either a single audio file, or several files that are played in sequence
 * (all the audio files of a folder, or root-level files sharing a name with a running number).
 */
@Serializable
data class Audiobook(
    val id: String,
    val title: String,
    val tracks: List<AudioTrack>,
) {
    val isMultiFile: Boolean get() = tracks.size > 1
    val totalBytes: Long? get() = if (tracks.all { it.sizeBytes != null }) tracks.sumOf { it.sizeBytes!! } else null
}
