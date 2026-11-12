package net.alexsaez.cadence.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.alexsaez.cadence.player.AudioPlayer
import net.alexsaez.cadence.player.PlayableItem
import net.alexsaez.cadence.player.PlayerState

/**
 * Simulated audio hardware. Nothing is decoded or played: time only moves when the test calls
 * [advanceBy], and hardware events such as unplugging headphones can be triggered on demand.
 */
class FakeAudioPlayer(
    /** Duration reported for each item; defaults to 10 minutes. */
    private val durationOf: (PlayableItem) -> Long = { 10 * 60_000L },
) : AudioPlayer {

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    @Volatile
    var queue: List<PlayableItem> = emptyList()
        private set

    /** The (index, position) passed to the last [setQueue] call. */
    @Volatile
    var lastQueueStart: Pair<Int, Long>? = null
        private set

    @Synchronized
    override fun setQueue(items: List<PlayableItem>, startIndex: Int, startPositionMs: Long) {
        queue = items
        lastQueueStart = startIndex to startPositionMs
        emit(index = startIndex, position = startPositionMs, playing = false, ended = false)
    }

    @Synchronized
    override fun play() {
        if (queue.isEmpty()) return
        val s = _state.value
        if (s.ended) return
        _state.value = s.copy(isPlaying = true)
    }

    @Synchronized
    override fun pause() {
        _state.value = _state.value.copy(isPlaying = false)
    }

    @Synchronized
    override fun seekTo(positionMs: Long) {
        val s = _state.value
        emit(s.currentIndex, positionMs.coerceIn(0, s.durationMs), s.isPlaying, ended = false)
    }

    @Synchronized
    override fun seekTo(index: Int, positionMs: Long) {
        if (index !in queue.indices) return
        emit(index, positionMs, _state.value.isPlaying, ended = false)
    }

    @Synchronized
    override fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
    }

    @Synchronized
    override fun stop() {
        queue = emptyList()
        _state.value = PlayerState()
    }

    /** Simulates [wallClockMs] of playback, moving through the queue like a real player. */
    @Synchronized
    fun advanceBy(wallClockMs: Long) {
        val s = _state.value
        if (!s.isPlaying || queue.isEmpty()) return
        var remaining = (wallClockMs * s.speed).toLong()
        var index = s.currentIndex
        var position = s.positionMs
        while (remaining > 0) {
            val duration = durationOf(queue[index])
            val left = duration - position
            if (remaining < left) {
                position += remaining
                remaining = 0
            } else {
                remaining -= left
                if (index == queue.lastIndex) {
                    emit(index, duration, playing = false, ended = true)
                    return
                }
                // Let observers see the end of the file before moving on.
                emit(index, duration, playing = true, ended = false)
                index++
                position = 0
            }
        }
        emit(index, position, playing = true, ended = false)
    }

    /** The OS broadcast ACTION_AUDIO_BECOMING_NOISY (headphones unplugged): players pause. */
    fun simulateHeadphonesUnplugged() = pause()

    /** Another app took audio focus (e.g. an incoming call). */
    fun simulateAudioFocusLoss() = pause()

    @Synchronized
    fun simulateError(message: String) {
        _state.value = _state.value.copy(isPlaying = false, error = message)
    }

    private fun emit(index: Int, position: Long, playing: Boolean, ended: Boolean) {
        val item = queue.getOrNull(index)
        _state.value = PlayerState(
            currentItemId = item?.id,
            currentIndex = index,
            positionMs = position,
            durationMs = item?.let(durationOf) ?: 0,
            isPlaying = playing,
            speed = _state.value.speed,
            ended = ended,
        )
    }
}
