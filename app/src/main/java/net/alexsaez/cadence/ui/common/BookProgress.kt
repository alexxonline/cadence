package net.alexsaez.cadence.ui.common

import net.alexsaez.cadence.library.Audiobook
import net.alexsaez.cadence.storage.TrackProgress

/** Where the user is in a book, derived from the saved per-file positions. */
data class BookProgress(val trackIndex: Int, val progress: TrackProgress)

/** The most recently listened file of [book] with its saved position, or null if never played. */
fun Audiobook.resumePoint(positions: Map<String, TrackProgress>): BookProgress? =
    tracks.withIndex()
        .mapNotNull { (i, t) -> positions[t.id]?.let { BookProgress(i, it) } }
        .maxByOrNull { it.progress.updatedAt }

fun Audiobook.describeProgress(point: BookProgress?): String? {
    point ?: return null
    val position = formatDuration(point.progress.positionMs)
    return if (isMultiFile) "File ${point.trackIndex + 1} of ${tracks.size} · $position" else position
}
