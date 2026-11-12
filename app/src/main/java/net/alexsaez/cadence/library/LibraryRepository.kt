package net.alexsaez.cadence.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.alexsaez.cadence.drive.DriveApi
import net.alexsaez.cadence.drive.DriveFolder
import java.io.File

/**
 * Scans the selected Drive folder into [Audiobook]s and caches the result on disk so the
 * library (and downloaded books) are available offline.
 */
class LibraryRepository(
    private val drive: DriveApi,
    private val cacheDir: File,
    private val maxDepth: Int = 4,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _library = MutableStateFlow<LibrarySnapshot?>(null)
    val library: StateFlow<LibrarySnapshot?> = _library.asStateFlow()

    /** Loads the cached library for [folder], if any. */
    suspend fun loadCached(folder: DriveFolder): LibrarySnapshot? = withContext(Dispatchers.IO) {
        val current = _library.value
        if (current?.folder == folder) return@withContext current
        val file = cacheFile(folder)
        val cached = if (file.exists()) {
            runCatching { json.decodeFromString<LibrarySnapshot>(file.readText()) }.getOrNull()
        } else null
        _library.value = cached?.takeIf { it.folder == folder }
        _library.value
    }

    /** Rescans [folder] on Drive. */
    suspend fun refresh(folder: DriveFolder): LibrarySnapshot {
        val tree = scan(folder.id, folder.name, depth = 0, limiter = Semaphore(4))
        val snapshot = LibrarySnapshot(folder, AudiobookGrouper.group(tree))
        _library.value = snapshot
        withContext(Dispatchers.IO) {
            cacheDir.mkdirs()
            cacheFile(folder).writeText(json.encodeToString(LibrarySnapshot.serializer(), snapshot))
        }
        return snapshot
    }

    fun findBook(bookId: String): Audiobook? = _library.value?.books?.firstOrNull { it.id == bookId }

    fun clear() {
        _library.value = null
        cacheDir.listFiles { f -> f.name.startsWith(CACHE_PREFIX) }?.forEach { it.delete() }
    }

    private suspend fun scan(id: String, name: String, depth: Int, limiter: Semaphore): FolderNode {
        val children = limiter.withPermit { drive.listChildren(id) }
        val audio = children.filter { it.isAudio }
        val folders = if (depth < maxDepth) children.filter { it.isFolder } else emptyList()
        val subNodes = coroutineScope {
            folders.map { f -> async { scan(f.id, f.name, depth + 1, limiter) } }.awaitAll()
        }
        return FolderNode(id, name, audio, subNodes)
    }

    private fun cacheFile(folder: DriveFolder) =
        File(cacheDir, CACHE_PREFIX + folder.id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private companion object {
        const val CACHE_PREFIX = "library_"
    }
}

@Serializable
data class LibrarySnapshot(val folder: DriveFolder, val books: List<Audiobook>)
