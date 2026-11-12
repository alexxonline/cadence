package net.alexsaez.cadence.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.alexsaez.cadence.di.AppContainer
import net.alexsaez.cadence.download.BookDownloadState
import net.alexsaez.cadence.drive.DriveFolder
import net.alexsaez.cadence.library.Audiobook
import net.alexsaez.cadence.player.NowPlaying
import net.alexsaez.cadence.ui.common.describeProgress
import net.alexsaez.cadence.ui.common.formatBytes
import net.alexsaez.cadence.ui.common.resumePoint

data class BookRow(
    val book: Audiobook,
    val download: BookDownloadState,
    val progressText: String?,
)

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    data class UiState(
        val folder: DriveFolder? = null,
        val books: List<BookRow> = emptyList(),
        val continueListening: BookRow? = null,
        val nowPlaying: NowPlaying? = null,
        val loaded: Boolean = false,
        val refreshing: Boolean = false,
        val error: String? = null,
    )

    private data class Status(
        val refreshing: Boolean = false,
        val error: String? = null,
        val attempted: Boolean = false,
    )

    private val status = MutableStateFlow(Status())

    private val rows = combine(
        c.libraryRepository.library,
        c.downloadRepository.states,
        c.playbackStore.positions,
    ) { library, downloads, positions ->
        library?.folder to library?.books.orEmpty().map { book ->
            BookRow(
                book = book,
                download = c.downloadRepository.bookState(book, downloads),
                progressText = book.describeProgress(book.resumePoint(positions)),
            )
        }
    }

    val ui: StateFlow<UiState> = combine(
        rows,
        c.playbackStore.lastPlayed,
        c.playbackController.nowPlaying,
        status,
        c.playbackStore.selectedFolder,
    ) { (folder, books), last, nowPlaying, st, selected ->
        UiState(
            folder = folder ?: selected,
            books = books,
            continueListening = last?.let { l -> books.firstOrNull { it.book.id == l.bookId } },
            nowPlaying = nowPlaying,
            loaded = folder != null || st.attempted,
            refreshing = st.refreshing,
            error = st.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        viewModelScope.launch {
            c.playbackStore.selectedFolder.filterNotNull().collectLatest { folder ->
                val cached = c.libraryRepository.loadCached(folder)
                // Rescan when opening a folder for the first time; otherwise show the cache
                // instantly and refresh in the background.
                refresh(folder, showIndicator = cached == null)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val folder = c.playbackStore.selectedFolder.first() ?: return@launch
            refresh(folder, showIndicator = true)
        }
    }

    private suspend fun refresh(folder: DriveFolder, showIndicator: Boolean) {
        status.update { it.copy(refreshing = showIndicator || it.refreshing, error = null) }
        val failure = try {
            c.libraryRepository.refresh(folder)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            status.update { it.copy(refreshing = false) }
            throw e
        } catch (e: Exception) {
            "Could not refresh the library: ${e.message}"
        }
        status.update { it.copy(refreshing = false, error = failure, attempted = true) }
    }

    fun download(book: Audiobook) = c.downloadRepository.download(book)

    fun cancelDownload(book: Audiobook) = c.downloadRepository.cancel(book)

    fun togglePlay(book: Audiobook) {
        viewModelScope.launch {
            val np = c.playbackController.nowPlaying.value
            if (np?.book?.id == book.id && np.isPlaying) {
                c.playbackController.pause()
            } else {
                try {
                    c.playbackController.play(book)
                } catch (e: Exception) {
                    status.update { it.copy(error = e.message) }
                }
            }
        }
    }

    fun dismissError() {
        status.update { it.copy(error = null) }
    }

    fun signOut() {
        viewModelScope.launch {
            c.playbackController.pause()
            c.audioPlayer.stop()
            c.downloadRepository.deleteAll()
            c.libraryRepository.clear()
            c.playbackStore.setSelectedFolder(null)
            c.authRepository.signOut()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenBook: (String) -> Unit,
    onChangeFolder: () -> Unit,
) {
    val vm = viewModel { LibraryViewModel(container) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Library")
                        ui.folder?.let {
                            Text(
                                it.name,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("library_folder_name"),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onChangeFolder) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Change folder")
                    }
                    IconButton(onClick = vm::signOut) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.testTag("library_screen"),
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = ui.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                !ui.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (!ui.refreshing) CircularProgressIndicator()
                }
                ui.books.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            "No audiobooks found in this folder.\nAdd audio files (mp3, m4a, m4b…) or folders of them to your Drive folder and pull to refresh.",
                            modifier = Modifier.padding(32.dp).testTag("library_empty"),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize().testTag("book_list")) {
                    ui.continueListening?.let { row ->
                        item(key = "continue") {
                            ContinueListeningCard(
                                row = row,
                                isPlaying = ui.nowPlaying?.book?.id == row.book.id && ui.nowPlaying?.isPlaying == true,
                                onOpen = { onOpenBook(row.book.id) },
                                onTogglePlay = { vm.togglePlay(row.book) },
                            )
                        }
                    }
                    items(ui.books, key = { it.book.id }) { row ->
                        BookListItem(
                            row = row,
                            onClick = { onOpenBook(row.book.id) },
                            onDownload = { vm.download(row.book) },
                            onCancel = { vm.cancelDownload(row.book) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueListeningCard(
    row: BookRow,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("continue_listening"),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Continue listening", style = MaterialTheme.typography.labelMedium)
                Text(
                    row.book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                row.progressText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.width(12.dp))
            if (row.download == BookDownloadState.Downloaded) {
                FilledIconButton(onClick = onTogglePlay, modifier = Modifier.testTag("continue_play")) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
            }
        }
    }
}

@Composable
private fun BookListItem(
    row: BookRow,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val book = row.book
    val details = buildList {
        add(if (book.isMultiFile) "${book.tracks.size} files" else "Single file")
        book.totalBytes?.let { add(formatBytes(it)) }
    }.joinToString(" · ")
    ListItem(
        headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(details)
                row.progressText?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        },
        leadingContent = {
            Icon(
                if (book.isMultiFile) Icons.AutoMirrored.Filled.LibraryBooks else Icons.Filled.Headphones,
                contentDescription = null,
            )
        },
        trailingContent = { DownloadIndicator(row.download, book.title, onDownload, onCancel) },
        modifier = Modifier.clickable(onClick = onClick).testTag("book:${book.title}"),
    )
}

@Composable
private fun DownloadIndicator(
    state: BookDownloadState,
    title: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        BookDownloadState.NotDownloaded -> IconButton(onClick = onDownload) {
            Icon(Icons.Filled.Download, contentDescription = "Download $title")
        }
        is BookDownloadState.InProgress -> IconButton(onClick = onCancel) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { state.fraction }, modifier = Modifier.size(32.dp))
                Icon(Icons.Filled.Close, contentDescription = "Cancel download of $title", modifier = Modifier.size(16.dp))
            }
        }
        BookDownloadState.Downloaded -> Icon(
            Icons.Filled.DownloadDone,
            contentDescription = "$title downloaded",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(12.dp),
        )
        is BookDownloadState.Failed -> IconButton(onClick = onDownload) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = "Retry download of $title",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
