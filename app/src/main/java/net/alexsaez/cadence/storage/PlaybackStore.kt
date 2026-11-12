package net.alexsaez.cadence.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.alexsaez.cadence.drive.DriveFolder

/** Saved listening position of a single audio file. */
data class TrackProgress(val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
    val isFinished: Boolean get() = durationMs > 0 && positionMs >= durationMs - FINISHED_THRESHOLD_MS

    companion object {
        const val FINISHED_THRESHOLD_MS = 2_000L
    }
}

data class LastPlayed(val bookId: String, val trackId: String)

/** Local, on-device memory of what the user listened to and where they stopped. */
interface PlaybackStore {
    val selectedFolder: Flow<DriveFolder?>
    suspend fun setSelectedFolder(folder: DriveFolder?)

    /** The latest audiobook/file that was played. */
    val lastPlayed: Flow<LastPlayed?>
    suspend fun setLastPlayed(bookId: String, trackId: String)

    /** Last position per audio file id. */
    val positions: Flow<Map<String, TrackProgress>>
    suspend fun savePosition(trackId: String, positionMs: Long, durationMs: Long)
    suspend fun position(trackId: String): TrackProgress? = positions.first()[trackId]

    /** The file of [bookId] that was played last. */
    suspend fun lastTrackOf(bookId: String): String?
}

class DataStorePlaybackStore(
    private val dataStore: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
) : PlaybackStore {

    override val selectedFolder: Flow<DriveFolder?> = dataStore.data.map { prefs ->
        val id = prefs[FOLDER_ID] ?: return@map null
        DriveFolder(id, prefs[FOLDER_NAME] ?: id)
    }.distinctUntilChanged()

    override suspend fun setSelectedFolder(folder: DriveFolder?) {
        dataStore.edit { prefs ->
            if (folder == null) {
                prefs.remove(FOLDER_ID)
                prefs.remove(FOLDER_NAME)
            } else {
                prefs[FOLDER_ID] = folder.id
                prefs[FOLDER_NAME] = folder.name
            }
        }
    }

    override val lastPlayed: Flow<LastPlayed?> = dataStore.data.map { prefs ->
        val book = prefs[LAST_BOOK] ?: return@map null
        val track = prefs[LAST_TRACK] ?: return@map null
        LastPlayed(book, track)
    }.distinctUntilChanged()

    override suspend fun setLastPlayed(bookId: String, trackId: String) {
        dataStore.edit { prefs ->
            prefs[LAST_BOOK] = bookId
            prefs[LAST_TRACK] = trackId
            prefs[stringPreferencesKey(BOOK_PREFIX + bookId)] = trackId
        }
    }

    override val positions: Flow<Map<String, TrackProgress>> = dataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(POS_PREFIX)) return@mapNotNull null
            val parts = (value as? String)?.split(',') ?: return@mapNotNull null
            if (parts.size != 3) return@mapNotNull null
            key.name.removePrefix(POS_PREFIX) to TrackProgress(
                parts[0].toLongOrNull() ?: 0, parts[1].toLongOrNull() ?: 0, parts[2].toLongOrNull() ?: 0,
            )
        }.toMap()
    }.distinctUntilChanged()

    override suspend fun savePosition(trackId: String, positionMs: Long, durationMs: Long) {
        val value = "${positionMs.coerceAtLeast(0)},${durationMs.coerceAtLeast(0)},${clock()}"
        dataStore.edit { it[stringPreferencesKey(POS_PREFIX + trackId)] = value }
    }

    override suspend fun lastTrackOf(bookId: String): String? =
        dataStore.data.first()[stringPreferencesKey(BOOK_PREFIX + bookId)]

    private companion object {
        val FOLDER_ID = stringPreferencesKey("folder_id")
        val FOLDER_NAME = stringPreferencesKey("folder_name")
        val LAST_BOOK = stringPreferencesKey("last_book")
        val LAST_TRACK = stringPreferencesKey("last_track")
        const val POS_PREFIX = "pos:"
        const val BOOK_PREFIX = "book:"
    }
}
