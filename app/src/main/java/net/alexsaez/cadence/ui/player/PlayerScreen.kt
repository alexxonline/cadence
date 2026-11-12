package net.alexsaez.cadence.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.alexsaez.cadence.di.AppContainer
import net.alexsaez.cadence.download.BookDownloadState
import net.alexsaez.cadence.library.Audiobook
import net.alexsaez.cadence.player.NowPlaying
import net.alexsaez.cadence.storage.TrackProgress
import net.alexsaez.cadence.ui.common.BookProgress
import net.alexsaez.cadence.ui.common.formatBytes
import net.alexsaez.cadence.ui.common.formatDuration
import net.alexsaez.cadence.ui.common.resumePoint

class PlayerViewModel(private val c: AppContainer, private val bookId: String) : ViewModel() {
    data class UiState(
        val book: Audiobook? = null,
        val notFound: Boolean = false,
        val download: BookDownloadState = BookDownloadState.NotDownloaded,
        /** Non-null when this book is the one loaded in the player. */
        val nowPlaying: NowPlaying? = null,
        val positions: Map<String, TrackProgress> = emptyMap(),
        val resume: BookProgress? = null,
    )

    private val book = MutableStateFlow<Audiobook?>(null)
    private val notFound = MutableStateFlow(false)
    val errors = MutableStateFlow<String?>(null)

    val ui: StateFlow<UiState> = combine(
        book,
        notFound,
        c.downloadRepository.states,
        c.playbackController.nowPlaying,
        c.playbackStore.positions,
    ) { b, missing, downloads, np, positions ->
        UiState(
            book = b,
            notFound = missing,
            download = b?.let { c.downloadRepository.bookState(it, downloads) } ?: BookDownloadState.NotDownloaded,
            nowPlaying = np?.takeIf { it.book.id == bookId },
            positions = positions,
            resume = b?.resumePoint(positions),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        viewModelScope.launch {
            val found = c.libraryRepository.findBook(bookId) ?: run {
                val folder = c.playbackStore.selectedFolder.first()
                folder?.let { c.libraryRepository.loadCached(it) }
                c.libraryRepository.findBook(bookId)
            }
            book.value = found
            notFound.value = found == null
        }
    }

    private fun withBook(block: suspend (Audiobook) -> Unit) {
        val b = book.value ?: return
        viewModelScope.launch {
            try {
                block(b)
            } catch (e: Exception) {
                errors.value = e.message ?: "Something went wrong"
            }
        }
    }

    fun download() = withBook { c.downloadRepository.download(it) }
    fun cancelDownload() = withBook { c.downloadRepository.cancel(it) }
    fun deleteDownload() = withBook {
        if (c.playbackController.isCurrent(it.id)) {
            c.playbackController.pause()
            c.audioPlayer.stop()
        }
        c.downloadRepository.delete(it)
    }

    fun play(trackIndex: Int? = null) = withBook { c.playbackController.play(it, trackIndex) }

    fun togglePlayPause() {
        val np = ui.value.nowPlaying
        if (np == null) play() else if (np.ended) play() else c.playbackController.togglePlayPause()
    }

    fun seekBy(deltaMs: Long) = c.playbackController.seekBy(deltaMs)
    fun seekTo(positionMs: Long) = c.playbackController.seekTo(positionMs)
    fun next() = withBook { c.playbackController.nextTrack() }
    fun previous() = withBook { c.playbackController.previousTrack() }

    fun cycleSpeed() {
        val current = ui.value.nowPlaying?.speed ?: 1f
        val next = SPEEDS.firstOrNull { it > current + 0.01f } ?: SPEEDS.first()
        c.playbackController.setSpeed(next)
    }

    companion object {
        val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(container: AppContainer, bookId: String, onBack: () -> Unit) {
    val vm = viewModel(key = "player:$bookId") { PlayerViewModel(container, bookId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val error by vm.errors.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            vm.errors.value = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.book?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (ui.download == BookDownloadState.Downloaded) {
                        IconButton(onClick = vm::deleteDownload) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete download")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.testTag("player_screen"),
    ) { padding ->
        val book = ui.book
        if (book == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (ui.notFound) Text("This audiobook is no longer in your library.") else CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Header(book) }
            item { DownloadSection(book, ui.download, vm) }
            if (ui.download == BookDownloadState.Downloaded) {
                item { Controls(book, ui, vm) }
            }
            if (book.isMultiFile) {
                item {
                    Text(
                        "Files",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
                    )
                }
                itemsIndexed(book.tracks, key = { _, t -> t.id }) { index, track ->
                    val saved = ui.positions[track.id]
                    val isCurrent = ui.nowPlaying?.trackIndex == index
                    ListItem(
                        headlineContent = {
                            Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            val text = when {
                                saved == null -> track.sizeBytes?.let { formatBytes(it) } ?: ""
                                saved.isFinished -> "Finished"
                                else -> "Stopped at ${formatDuration(saved.positionMs)}"
                            }
                            Text(text)
                        },
                        leadingContent = {
                            Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                        },
                        trailingContent = {
                            when {
                                isCurrent && ui.nowPlaying?.isPlaying == true ->
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Now playing")
                                saved?.isFinished == true ->
                                    Icon(Icons.Filled.CheckCircle, contentDescription = "Finished")
                            }
                        },
                        colors = if (isCurrent) {
                            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        } else ListItemDefaults.colors(),
                        modifier = Modifier
                            .testTag("track_$index")
                            .clickable(enabled = ui.download == BookDownloadState.Downloaded) { vm.play(index) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Header(book: Audiobook) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(140.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                book.title.take(1).uppercase(),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(book.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        val details = buildList {
            add(if (book.isMultiFile) "${book.tracks.size} files" else "Single file")
            book.totalBytes?.let { add(formatBytes(it)) }
        }.joinToString(" · ")
        Text(details, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DownloadSection(book: Audiobook, state: BookDownloadState, vm: PlayerViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (state) {
            BookDownloadState.NotDownloaded -> Button(onClick = vm::download, modifier = Modifier.testTag("download_button")) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Text(" Download to listen" + (book.totalBytes?.let { " (${formatBytes(it)})" } ?: ""))
            }
            is BookDownloadState.InProgress -> {
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth().testTag("download_progress"),
                )
                Text(
                    "Downloading… ${(state.fraction * 100).toInt()}%" +
                        if (state.totalFiles > 1) " (${state.completedFiles}/${state.totalFiles} files)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedButton(onClick = vm::cancelDownload) { Text("Cancel") }
            }
            is BookDownloadState.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("download_error"))
                Button(onClick = vm::download) { Text("Retry download") }
            }
            BookDownloadState.Downloaded -> Text(
                "Downloaded · available offline",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("downloaded_label"),
            )
        }
    }
}

@Composable
private fun Controls(book: Audiobook, ui: PlayerViewModel.UiState, vm: PlayerViewModel) {
    val np = ui.nowPlaying
    val trackIndex = np?.trackIndex ?: ui.resume?.trackIndex ?: 0
    val position = np?.positionMs ?: ui.resume?.progress?.positionMs?.takeIf { ui.resume.progress.isFinished.not() } ?: 0
    val duration = np?.durationMs?.takeIf { it > 0 } ?: ui.resume?.progress?.durationMs ?: 0
    var dragging by remember { mutableStateOf<Float?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        if (book.isMultiFile) {
            Text(
                "File ${trackIndex + 1} of ${book.tracks.size}: ${book.tracks[trackIndex].title}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("current_track"),
            )
        }
        Slider(
            value = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { if (np != null && duration > 0) vm.seekTo((it * duration).toLong()) }
                dragging = null
            },
            enabled = np != null && duration > 0,
            modifier = Modifier.testTag("position_slider"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(position), modifier = Modifier.testTag("position_text"))
            Text(if (duration > 0) formatDuration(duration) else "--:--", modifier = Modifier.testTag("duration_text"))
        }
        if (np == null && ui.resume != null && !ui.resume.progress.isFinished) {
            Text(
                "Resumes where you left off",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("resume_hint"),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (book.isMultiFile) {
                IconButton(onClick = vm::previous, enabled = np != null) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous file")
                }
            }
            IconButton(onClick = { vm.seekBy(-10_000) }, enabled = np != null) {
                Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds")
            }
            val playing = np?.isPlaying == true
            FilledIconButton(
                onClick = vm::togglePlayPause,
                modifier = Modifier.size(72.dp).testTag("play_pause"),
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = { vm.seekBy(30_000) }, enabled = np != null) {
                Icon(Icons.Filled.Forward30, contentDescription = "Forward 30 seconds")
            }
            if (book.isMultiFile) {
                IconButton(
                    onClick = vm::next,
                    enabled = np != null && trackIndex < book.tracks.lastIndex,
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next file")
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            AssistChip(
                onClick = vm::cycleSpeed,
                enabled = np != null,
                label = { Text("Speed ${np?.speed ?: 1f}x") },
                modifier = Modifier.testTag("speed_chip"),
            )
        }
    }
}
