package net.alexsaez.cadence.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.alexsaez.cadence.drive.DriveApi
import net.alexsaez.cadence.library.AudioTrack
import net.alexsaez.cadence.library.Audiobook
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface DownloadState {
    data object NotDownloaded : DownloadState
    data object Queued : DownloadState
    data class Downloading(val bytes: Long, val total: Long) : DownloadState {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data object Downloaded : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** Aggregated download state of a whole audiobook. */
sealed interface BookDownloadState {
    data object NotDownloaded : BookDownloadState
    data class InProgress(val fraction: Float, val completedFiles: Int, val totalFiles: Int) : BookDownloadState
    data object Downloaded : BookDownloadState
    data class Failed(val message: String) : BookDownloadState
}

/**
 * Downloads audiobook files from Drive into app-private storage so they can be played offline.
 * Files are stored as `<dir>/<driveFileId>.<ext>`.
 */
class DownloadRepository(
    private val drive: DriveApi,
    private val directory: File,
    private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    /** Download state by Drive file id. Files absent from the map are not downloaded. */
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    init {
        directory.mkdirs()
        val existing = directory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.associate { it.name.substringBeforeLast('.') to (DownloadState.Downloaded as DownloadState) }
            .orEmpty()
        _states.value = existing
    }

    fun localFile(track: AudioTrack): File =
        File(directory, track.id + (track.extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""))

    fun isDownloaded(book: Audiobook): Boolean =
        book.tracks.all { _states.value[it.id] == DownloadState.Downloaded && localFile(it).exists() }

    fun bookState(book: Audiobook, states: Map<String, DownloadState> = _states.value): BookDownloadState {
        val perTrack = book.tracks.map { states[it.id] ?: DownloadState.NotDownloaded }
        perTrack.filterIsInstance<DownloadState.Failed>().firstOrNull()?.let {
            if (perTrack.none { s -> s is DownloadState.Downloading || s is DownloadState.Queued }) {
                return BookDownloadState.Failed(it.message)
            }
        }
        val done = perTrack.count { it == DownloadState.Downloaded }
        if (done == perTrack.size) return BookDownloadState.Downloaded
        val active = perTrack.any { it is DownloadState.Downloading || it is DownloadState.Queued }
        if (!active) return BookDownloadState.NotDownloaded
        val fraction = progressFraction(book, perTrack)
        return BookDownloadState.InProgress(fraction, done, perTrack.size)
    }

    private fun progressFraction(book: Audiobook, perTrack: List<DownloadState>): Float {
        val total = book.totalBytes
        return if (total != null && total > 0) {
            val bytes = book.tracks.zip(perTrack).sumOf { (track, state) ->
                when (state) {
                    DownloadState.Downloaded -> track.sizeBytes ?: 0
                    is DownloadState.Downloading -> state.bytes
                    else -> 0
                }
            }
            (bytes.toFloat() / total).coerceIn(0f, 1f)
        } else {
            perTrack.sumOf { s ->
                when (s) {
                    DownloadState.Downloaded -> 1.0
                    is DownloadState.Downloading -> s.fraction.toDouble()
                    else -> 0.0
                }
            }.toFloat() / perTrack.size
        }
    }

    /** Downloads all missing files of [book], one after another. */
    fun download(book: Audiobook) {
        if (jobs[book.id]?.isActive == true) return
        val missing = book.tracks.filter { _states.value[it.id] != DownloadState.Downloaded || !localFile(it).exists() }
        if (missing.isEmpty()) return
        _states.update { map -> map + missing.associate { it.id to DownloadState.Queued } }
        jobs[book.id] = scope.launch {
            for (track in missing) {
                try {
                    downloadTrack(track)
                } catch (e: CancellationException) {
                    _states.update { map -> map + missing.filter { map[it.id] != DownloadState.Downloaded }.associate { it.id to DownloadState.NotDownloaded } }
                    throw e
                } catch (e: Exception) {
                    _states.update { map ->
                        map + (track.id to DownloadState.Failed(e.message ?: "Download failed")) +
                            missing.filter { map[it.id] == DownloadState.Queued }.associate { it.id to DownloadState.NotDownloaded }
                    }
                    break
                }
            }
            jobs.remove(book.id)
        }
    }

    private suspend fun downloadTrack(track: AudioTrack) {
        var lastReported = -1L
        _states.update { it + (track.id to DownloadState.Downloading(0, track.sizeBytes ?: -1)) }
        drive.download(track.id, localFile(track)) { read, total ->
            val effectiveTotal = if (total > 0) total else track.sizeBytes ?: -1
            // Throttle UI updates to ~1% steps.
            val step = if (effectiveTotal > 0) effectiveTotal / 100 else 256 * 1024L
            if (read - lastReported >= step || read == effectiveTotal) {
                lastReported = read
                _states.update { it + (track.id to DownloadState.Downloading(read, effectiveTotal)) }
            }
        }
        _states.update { it + (track.id to DownloadState.Downloaded) }
    }

    fun cancel(book: Audiobook) {
        jobs.remove(book.id)?.cancel()
    }

    /** Removes the local copies of [book]'s files. */
    suspend fun delete(book: Audiobook) {
        cancel(book)
        withContext(Dispatchers.IO) {
            book.tracks.forEach { localFile(it).delete() }
        }
        _states.update { map -> map - book.tracks.map { it.id }.toSet() }
    }

    suspend fun deleteAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        withContext(Dispatchers.IO) { directory.listFiles()?.forEach { it.delete() } }
        _states.value = emptyMap()
    }
}
