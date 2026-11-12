package net.alexsaez.cadence.player

import kotlinx.coroutines.flow.StateFlow

/** A local audio file queued for playback. */
data class PlayableItem(
    val id: String,
    val uri: String,
    val title: String,
    val album: String,
)

data class PlayerState(
    /** Id of the [PlayableItem] currently loaded, or null when the queue is empty. */
    val currentItemId: String? = null,
    val currentIndex: Int = 0,
    val positionMs: Long = 0,
    /** Duration of the current item, or 0 while unknown. */
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    /** True once the whole queue played to the end. */
    val ended: Boolean = false,
    val error: String? = null,
)

/**
 * Abstraction over the device audio output. Production uses Media3/ExoPlayer running inside
 * [PlaybackService]; tests substitute a fake that simulates the hardware.
 */
interface AudioPlayer {
    val state: StateFlow<PlayerState>

    fun setQueue(items: List<PlayableItem>, startIndex: Int, startPositionMs: Long)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekTo(index: Int, positionMs: Long)
    fun setSpeed(speed: Float)
    fun stop()
}
