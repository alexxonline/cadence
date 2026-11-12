package net.alexsaez.cadence.ui.picker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.alexsaez.cadence.di.AppContainer
import net.alexsaez.cadence.drive.DriveApi
import net.alexsaez.cadence.drive.DriveFolder
import net.alexsaez.cadence.storage.PlaybackStore

class FolderPickerViewModel(
    private val drive: DriveApi,
    private val store: PlaybackStore,
) : ViewModel() {
    data class UiState(
        val path: List<DriveFolder> = emptyList(),
        val folders: List<DriveFolder> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        /** Set once the current folder was saved as the library folder. */
        val selected: Boolean = false,
    ) {
        val current: DriveFolder? get() = path.lastOrNull()
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
    }

    fun open(folder: DriveFolder) {
        _ui.update { it.copy(path = it.path + folder) }
        load()
    }

    /** Goes one level up. Returns false when already at the top. */
    fun up(): Boolean {
        if (_ui.value.path.isEmpty()) return false
        _ui.update { it.copy(path = it.path.dropLast(1)) }
        load()
        return true
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        val current = _ui.value.current
        if (current == null) {
            _ui.update {
                it.copy(folders = listOf(DriveFolder.MY_DRIVE, DriveFolder.SHARED_WITH_ME), loading = false, error = null)
            }
            return
        }
        _ui.update { it.copy(folders = emptyList(), loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val folders = drive.listChildren(current.id, foldersOnly = true)
                    .filter { it.isFolder }
                    .map { DriveFolder(it.id, it.name) }
                _ui.update { it.copy(folders = folders, loading = false) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "Could not load folders") }
            }
        }
    }

    fun selectCurrent() {
        val folder = _ui.value.current ?: return
        viewModelScope.launch {
            store.setSelectedFolder(folder)
            _ui.update { it.copy(selected = true) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(
    container: AppContainer,
    canCancel: Boolean,
    onCancel: () -> Unit,
    onFolderSelected: () -> Unit,
) {
    val vm = viewModel { FolderPickerViewModel(container.driveApi, container.playbackStore) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.selected) {
        if (ui.selected) onFolderSelected()
    }

    BackHandler(enabled = ui.path.isNotEmpty()) { vm.up() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Choose audiobooks folder")
                        Text(
                            ui.path.joinToString(" / ") { it.name }.ifEmpty { "Google Drive" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                            modifier = Modifier.testTag("picker_path"),
                        )
                    }
                },
                navigationIcon = {
                    when {
                        ui.path.isNotEmpty() -> IconButton(onClick = { vm.up() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                        }
                        canCancel -> IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = vm::selectCurrent,
                    enabled = ui.current != null,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("use_folder_button"),
                ) {
                    Text(ui.current?.let { "Use \"${it.name}\"" } ?: "Open a folder to select it")
                }
            }
        },
        modifier = Modifier.testTag("folder_picker"),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ui.error != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(ui.error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::retry) { Text("Retry") }
                }
                ui.folders.isEmpty() -> Text(
                    "No sub-folders here",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.folders, key = { it.id }) { folder ->
                        ListItem(
                            headlineContent = { Text(folder.name) },
                            leadingContent = {
                                Icon(
                                    if (folder == DriveFolder.SHARED_WITH_ME) Icons.Filled.FolderShared else Icons.Filled.Folder,
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.clickable { vm.open(folder) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
