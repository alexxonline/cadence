package net.alexsaez.cadence.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.map
import net.alexsaez.cadence.auth.AuthState
import net.alexsaez.cadence.di.AppContainer
import net.alexsaez.cadence.drive.DriveFolder
import net.alexsaez.cadence.ui.library.LibraryScreen
import net.alexsaez.cadence.ui.login.LoginScreen
import net.alexsaez.cadence.ui.picker.FolderPickerScreen
import net.alexsaez.cadence.ui.player.PlayerScreen

object Routes {
    const val PICKER = "picker"
    const val LIBRARY = "library"
    const val BOOK = "book/{bookId}"
    fun book(bookId: String) = "book/${Uri.encode(bookId)}"
}

@Composable
fun CadenceRoot(container: AppContainer) {
    val auth by container.authRepository.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (container.authRepository.state.value == AuthState.Unknown) {
            container.authRepository.restoreSession()
        }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        when (val state = auth) {
            AuthState.Unknown -> Loading()
            AuthState.SignedOut -> LoginScreen(container)
            is AuthState.SignedIn -> SignedInContent(container)
        }
    }
}

private sealed interface FolderLoad {
    data object Loading : FolderLoad
    data class Loaded(val folder: DriveFolder?) : FolderLoad
}

@Composable
private fun SignedInContent(container: AppContainer) {
    val folderFlow = remember(container) {
        container.playbackStore.selectedFolder.map<DriveFolder?, FolderLoad> { FolderLoad.Loaded(it) }
    }
    val folderLoad by folderFlow.collectAsStateWithLifecycle(FolderLoad.Loading)
    val loaded = folderLoad as? FolderLoad.Loaded ?: return Loading()

    val navController = rememberNavController()
    val startDestination = remember { if (loaded.folder == null) Routes.PICKER else Routes.LIBRARY }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.PICKER) {
            FolderPickerScreen(
                container = container,
                canCancel = navController.previousBackStackEntry != null,
                onCancel = { navController.popBackStack() },
                onFolderSelected = {
                    navController.navigate(Routes.LIBRARY) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                container = container,
                onOpenBook = { navController.navigate(Routes.book(it)) },
                onChangeFolder = { navController.navigate(Routes.PICKER) },
            )
        }
        composable(
            Routes.BOOK,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            PlayerScreen(
                container = container,
                bookId = entry.arguments?.getString("bookId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize().testTag("loading"), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
