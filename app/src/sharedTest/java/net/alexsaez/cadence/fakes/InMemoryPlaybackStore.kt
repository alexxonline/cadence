package net.alexsaez.cadence.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import net.alexsaez.cadence.drive.DriveFolder
import net.alexsaez.cadence.storage.LastPlayed
import net.alexsaez.cadence.storage.PlaybackStore
import net.alexsaez.cadence.storage.TrackProgress

class InMemoryPlaybackStore : PlaybackStore {
    private var time = 0L
    private val folder = MutableStateFlow<DriveFolder?>(null)
    private val last = MutableStateFlow<LastPlayed?>(null)
    private val pos = MutableStateFlow<Map<String, TrackProgress>>(emptyMap())
    private val bookTracks = mutableMapOf<String, String>()

    override val selectedFolder: Flow<DriveFolder?> = folder
    override suspend fun setSelectedFolder(folder: DriveFolder?) {
        this.folder.value = folder
    }

    override val lastPlayed: Flow<LastPlayed?> = last
    override suspend fun setLastPlayed(bookId: String, trackId: String) {
        bookTracks[bookId] = trackId
        last.value = LastPlayed(bookId, trackId)
    }

    override val positions: Flow<Map<String, TrackProgress>> = pos
    override suspend fun savePosition(trackId: String, positionMs: Long, durationMs: Long) {
        pos.update { it + (trackId to TrackProgress(positionMs, durationMs, ++time)) }
    }

    override suspend fun lastTrackOf(bookId: String): String? = bookTracks[bookId]

    val snapshot: Map<String, TrackProgress> get() = pos.value
}
