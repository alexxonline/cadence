package net.alexsaez.cadence.player

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.alexsaez.cadence.download.DownloadRepository
import net.alexsaez.cadence.library.Audiobook
import net.alexsaez.cadence.storage.PlaybackStore
import java.io.File
import kotlin.math.abs

data class NowPlaying(
    val book: Audiobook,
    val trackIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val speed: Float,
    val ended: Boolean,
) {
    val track get() = book.tracks[trackIndex]
}

class NotDownloadedException(book: Audiobook) :
    IllegalStateException("\"${book.title}\" has not been downloaded yet")

/**
 * Connects the [AudioPlayer] with the [PlaybackStore]: starts books where the user left them and
 * persists the latest book/file played and the position of every file while listening.
 */
class PlaybackController(
    private val player: AudioPlayer,
    private val store: PlaybackStore,
    private val downloads: DownloadRepository,
    private val scope: CoroutineScope,
    private val fileToUri: (File) -> String = { Uri.fromFile(it).toString() },
) {
    private val currentBook = MutableStateFlow<Audiobook?>(null)

    val nowPlaying: StateFlow<NowPlaying?> = combine(currentBook, player.state) { book, s ->
        book ?: return@combine null
        val index = book.tracks.indexOfFirst { it.id == s.currentItemId }
        if (index < 0) return@combine null
        NowPlaying(book, index, s.positionMs, s.durationMs, s.isPlaying, s.speed, s.ended)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Last playback error reported by the audio device, if any. */
    val playerError: String? get() = player.state.value.error

    // Bookkeeping for persistence, only touched from the state collector.
    private var lastItemId: String? = null
    private var lastDurationMs = 0L
    private var lastPositionMs = 0L
    private var lastSavedPositionMs = -1L
    private var lastWasPlaying = false

    init {
        scope.launch { player.state.collect { onPlayerState(it) } }
    }

    fun isCurrent(bookId: String) = currentBook.value?.id == bookId

    /**
     * Plays [book]. Without [trackIndex] it resumes the file played last in that book at its saved
     * position; with [trackIndex] it starts that file at its saved position.
     */
    suspend fun play(book: Audiobook, trackIndex: Int? = null) {
        if (!downloads.isDownloaded(book)) throw NotDownloadedException(book)
        val playingThisBook = isCurrent(book.id) &&
            book.tracks.any { it.id == player.state.value.currentItemId }
        if (playingThisBook && trackIndex == null) {
            if (player.state.value.ended) restartIfFinished(book) else player.play()
            return
        }
        if (playingThisBook && trackIndex != null) {
            skipToTrack(trackIndex)
            player.play()
            return
        }
        val index = trackIndex
            ?: store.lastTrackOf(book.id)?.let { id -> book.tracks.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
            ?: 0
        val position = startPositionFor(book, index)
        saveCurrentPosition()
        lastItemId = null
        lastSavedPositionMs = -1
        currentBook.value = book
        player.setQueue(
            book.tracks.map { track ->
                PlayableItem(track.id, fileToUri(downloads.localFile(track)), track.title, book.title)
            },
            index,
            position,
        )
        player.play()
    }

    private suspend fun restartIfFinished(book: Audiobook) {
        player.seekTo(0, 0)
        store.setLastPlayed(book.id, book.tracks.first().id)
        player.play()
    }

    private suspend fun startPositionFor(book: Audiobook, index: Int): Long {
        val saved = store.position(book.tracks[index].id) ?: return 0
        return if (saved.isFinished) 0 else saved.positionMs
    }

    fun pause() = player.pause()

    fun resume() = player.play()

    fun togglePlayPause() {
        if (player.state.value.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    fun seekBy(deltaMs: Long) {
        val s = player.state.value
        val target = (s.positionMs + deltaMs).coerceAtLeast(0)
        player.seekTo(if (s.durationMs > 0) target.coerceAtMost(s.durationMs) else target)
    }

    suspend fun skipToTrack(index: Int) {
        val book = currentBook.value ?: return
        if (index !in book.tracks.indices) return
        player.seekTo(index, startPositionFor(book, index))
    }

    suspend fun nextTrack() {
        val np = nowPlaying.value ?: return
        if (np.trackIndex < np.book.tracks.lastIndex) skipToTrack(np.trackIndex + 1)
    }

    suspend fun previousTrack() {
        val np = nowPlaying.value ?: return
        // Like most players: restart the file unless we're at its very beginning.
        if (np.positionMs > 5_000 || np.trackIndex == 0) player.seekTo(0) else skipToTrack(np.trackIndex - 1)
    }

    fun setSpeed(speed: Float) = player.setSpeed(speed)

    private suspend fun saveCurrentPosition() {
        val s = player.state.value
        val book = currentBook.value ?: return
        val id = s.currentItemId ?: return
        if (book.tracks.none { it.id == id }) return
        store.savePosition(id, s.positionMs, s.durationMs)
        lastSavedPositionMs = s.positionMs
    }

    private suspend fun onPlayerState(s: PlayerState) {
        val book = currentBook.value ?: return
        val itemId = s.currentItemId ?: return
        val index = book.tracks.indexOfFirst { it.id == itemId }
        if (index < 0) return // Stale state from a previous queue.

        if (itemId != lastItemId) {
            val previous = lastItemId
            if (previous != null) {
                val prevIndex = book.tracks.indexOfFirst { it.id == previous }
                // The previous file played to its end and the player moved on: mark it finished.
                val reachedEnd = lastDurationMs > 0 && lastPositionMs >= lastDurationMs - END_TOLERANCE_MS
                if (prevIndex in 0 until index && reachedEnd) {
                    store.savePosition(previous, lastDurationMs, lastDurationMs)
                }
            }
            lastItemId = itemId
            store.setLastPlayed(book.id, itemId)
            store.savePosition(itemId, s.positionMs, s.durationMs)
            lastSavedPositionMs = s.positionMs
        } else if (
            s.isPlaying != lastWasPlaying ||
            s.ended ||
            abs(s.positionMs - lastSavedPositionMs) >= SAVE_INTERVAL_MS ||
            (!s.isPlaying && s.positionMs != lastSavedPositionMs)
        ) {
            val position = if (s.ended && s.durationMs > 0) s.durationMs else s.positionMs
            store.savePosition(itemId, position, s.durationMs)
            lastSavedPositionMs = position
        }
        lastWasPlaying = s.isPlaying
        lastPositionMs = s.positionMs
        lastDurationMs = s.durationMs
    }

    companion object {
        /** While playing, the position is persisted at least this often. */
        const val SAVE_INTERVAL_MS = 5_000L

        /** Position sampling is coarse, so "near the end" counts as having reached the end. */
        private const val END_TOLERANCE_MS = 3_000L
    }
}
