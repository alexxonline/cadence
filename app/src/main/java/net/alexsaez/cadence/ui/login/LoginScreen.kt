package net.alexsaez.cadence.ui.login

import android.app.PendingIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.alexsaez.cadence.auth.AuthRepository
import net.alexsaez.cadence.auth.SignInStep
import net.alexsaez.cadence.di.AppContainer

class LoginViewModel(private val auth: AuthRepository) : ViewModel() {
    data class UiState(val busy: Boolean = false, val error: String? = null)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _interactions = Channel<PendingIntent>(Channel.BUFFERED)
    /** Google UI (account picker / consent) that must be launched by the activity. */
    val interactions = _interactions.receiveAsFlow()

    fun signIn() {
        if (_ui.value.busy) return
        _ui.value = UiState(busy = true)
        viewModelScope.launch {
            try {
                when (val step = auth.beginSignIn()) {
                    SignInStep.Completed -> _ui.update { it.copy(busy = false) }
                    is SignInStep.NeedsUserInteraction -> _interactions.send(step.pendingIntent)
                }
            } catch (e: Exception) {
                _ui.value = UiState(error = e.message ?: "Sign-in failed")
            }
        }
    }

    fun onInteractionResult(resultCode: Int, data: android.content.Intent?) {
        viewModelScope.launch {
            try {
                auth.completeSignIn(resultCode, data)
                _ui.value = UiState()
            } catch (e: Exception) {
                _ui.value = UiState(error = e.message ?: "Sign-in failed")
            }
        }
    }
}

@Composable
fun LoginScreen(container: AppContainer) {
    val vm = viewModel { LoginViewModel(container.authRepository) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onInteractionResult(result.resultCode, result.data) }

    LaunchedEffect(vm) {
        vm.interactions.collect { pendingIntent ->
            launcher.launch(IntentSenderRequest.Builder(pendingIntent).build())
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag("login_screen"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Headphones,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text("Cadence", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Listen to the audiobooks stored in your Google Drive.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        if (ui.busy) {
            CircularProgressIndicator(Modifier.testTag("login_progress"))
        } else {
            Button(onClick = vm::signIn, modifier = Modifier.testTag("sign_in_button")) {
                Text("Sign in with Google")
            }
        }
        ui.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("login_error"),
            )
        }
    }
}
