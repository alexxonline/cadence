package net.alexsaez.cadence.fakes

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.alexsaez.cadence.auth.AuthRepository
import net.alexsaez.cadence.auth.AuthState
import net.alexsaez.cadence.auth.SignInException
import net.alexsaez.cadence.auth.SignInStep
import net.alexsaez.cadence.drive.GoogleUser
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stand-in for Google sign-in. Instead of showing Google's account picker it immediately
 * "picks" the test account, then loads the profile from the (fake) Drive server exactly like
 * the real implementation does.
 */
class FakeAuthRepository(
    /** Simulates a session that Google Play services remembers from a previous run. */
    @Volatile var rememberedUser: GoogleUser? = null,
) : AuthRepository {

    var profileLoader: suspend (String) -> GoogleUser = { GoogleUser("reader@example.com") }

    /** When set, sign-in fails with this message (e.g. user cancelled, no network). */
    @Volatile
    var signInError: String? = null

    @Volatile
    var token = "test-token"

    val signInCalls = AtomicInteger()
    val signOutCalls = AtomicInteger()

    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    val currentUser: GoogleUser? get() = (state.value as? AuthState.SignedIn)?.user

    override suspend fun restoreSession() {
        _state.value = rememberedUser?.let { AuthState.SignedIn(it) } ?: AuthState.SignedOut
    }

    override suspend fun beginSignIn(): SignInStep {
        signInCalls.incrementAndGet()
        signInError?.let { throw SignInException(it) }
        val user = profileLoader(token)
        rememberedUser = user
        _state.value = AuthState.SignedIn(user)
        return SignInStep.Completed
    }

    override suspend fun completeSignIn(resultCode: Int, data: Intent?) =
        error("The fake never asks for user interaction")

    override suspend fun accessToken(): String = token

    override suspend fun invalidate(token: String) = Unit

    override suspend fun signOut() {
        signOutCalls.incrementAndGet()
        rememberedUser = null
        _state.value = AuthState.SignedOut
    }
}
