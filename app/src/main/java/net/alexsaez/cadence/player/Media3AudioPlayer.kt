package net.alexsaez.cadence.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** [AudioPlayer] backed by a Media3 [MediaController] connected to [PlaybackService]. */
class Media3AudioPlayer(
    private val context: Context,
    scope: CoroutineScope,
) : AudioPlayer {

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var lastError: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
        override fun onPlayerError(error: PlaybackException) {
            lastError = error.message ?: "Playback error"
            publish()
        }
    }

    init {
        // Position is not pushed by the player, so sample it while playing.
        scope.launch(Dispatchers.Main) {
            while (isActive) {
                if (controller?.isPlaying == true) publish()
                delay(500)
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let { return block(it) }
        pending += block
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                controllerFuture = null
                lastError = "Could not connect to the playback service"
                publish()
                return@addListener
            }
            controller = c
            c.addListener(listener)
            pending.toList().forEach { it(c) }
            pending.clear()
            publish()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun publish() {
        val c = controller ?: return
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        _state.value = PlayerState(
            currentItemId = c.currentMediaItem?.mediaId,
            currentIndex = c.currentMediaItemIndex,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            isPlaying = c.isPlaying,
            speed = c.playbackParameters.speed,
            ended = c.playbackState == Player.STATE_ENDED,
            error = lastError,
        )
    }

    override fun setQueue(items: List<PlayableItem>, startIndex: Int, startPositionMs: Long) {
        lastError = null
        val mediaItems = items.map { item ->
            MediaItem.Builder()
                .setMediaId(item.id)
                .setUri(Uri.parse(item.uri))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setAlbumTitle(item.album)
                        .setArtist(item.album)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                        .build()
                )
                .build()
        }
        withController {
            it.setMediaItems(mediaItems, startIndex, startPositionMs)
            it.prepare()
        }
    }

    override fun play() = withController { it.play() }
    override fun pause() = withController { it.pause() }
    override fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0)) }
    override fun seekTo(index: Int, positionMs: Long) =
        withController { it.seekTo(index, positionMs.coerceAtLeast(0)) }
    override fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed) }
    override fun stop() = withController {
        it.stop()
        it.clearMediaItems()
    }
}
