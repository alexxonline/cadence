package net.alexsaez.cadence.auth

import android.app.PendingIntent
import android.content.Intent
import kotlinx.coroutines.flow.StateFlow
import net.alexsaez.cadence.drive.AccessTokenProvider
import net.alexsaez.cadence.drive.GoogleUser

sealed interface AuthState {
    data object Unknown : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val user: GoogleUser) : AuthState
}

sealed interface SignInStep {
    data object Completed : SignInStep

    /** Google needs to show its account picker / consent screen. Launch [pendingIntent]. */
    data class NeedsUserInteraction(val pendingIntent: PendingIntent) : SignInStep
}

class SignInException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Google sign-in plus authorization of the Drive (read-only) scope. */
interface AuthRepository : AccessTokenProvider {
    val state: StateFlow<AuthState>

    /** Silently restores a previous session on app start. */
    suspend fun restoreSession()

    suspend fun beginSignIn(): SignInStep

    /** Completes sign-in with the result of the [SignInStep.NeedsUserInteraction] intent. */
    suspend fun completeSignIn(resultCode: Int, data: Intent?)

    suspend fun signOut()
}
