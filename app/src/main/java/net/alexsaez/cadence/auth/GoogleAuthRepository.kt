package net.alexsaez.cadence.auth

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import net.alexsaez.cadence.drive.DriveRestApi
import net.alexsaez.cadence.drive.GoogleUser

/**
 * Signs the user in with their Google account using Google Identity Services'
 * [com.google.android.gms.auth.api.identity.AuthorizationClient], requesting the
 * `drive.readonly` scope. The account picker and consent screen are shown by Google Play
 * services. No API key or client secret is embedded in the app: Google matches the app's
 * package name + signing certificate SHA-1 against an Android OAuth client in Google Cloud.
 */
class GoogleAuthRepository(
    context: Context,
    private val profileLoader: suspend (accessToken: String) -> GoogleUser,
) : AuthRepository {

    private val client = Identity.getAuthorizationClient(context)
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenLock = Mutex()

    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    @Volatile
    private var accessToken: String? = null

    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(SCOPES.map(::Scope))
        .build()

    override suspend fun restoreSession() {
        val cachedUser = cachedUser()
        if (cachedUser == null) {
            _state.value = AuthState.SignedOut
            return
        }
        try {
            val result = client.authorize(request).await()
            val token = result.accessToken
            if (result.hasResolution() || token == null) {
                clearLocal()
            } else {
                accessToken = token
                _state.value = AuthState.SignedIn(cachedUser)
            }
        } catch (e: Exception) {
            // Probably offline: keep the session so downloaded books stay playable.
            _state.value = AuthState.SignedIn(cachedUser)
        }
    }

    override suspend fun beginSignIn(): SignInStep {
        val result = try {
            client.authorize(request).await()
        } catch (e: Exception) {
            throw SignInException("Google sign-in failed: ${e.message}", e)
        }
        if (result.hasResolution()) {
            val intent = result.pendingIntent
                ?: throw SignInException("Google sign-in could not be started")
            return SignInStep.NeedsUserInteraction(intent)
        }
        finishSignIn(result)
        return SignInStep.Completed
    }

    override suspend fun completeSignIn(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            throw SignInException("Sign-in was cancelled")
        }
        val result = try {
            client.getAuthorizationResultFromIntent(data)
        } catch (e: Exception) {
            throw SignInException("Google sign-in failed: ${e.message}", e)
        }
        finishSignIn(result)
    }

    private suspend fun finishSignIn(result: AuthorizationResult) {
        val token = result.accessToken ?: throw SignInException("Google did not return an access token")
        if (!result.grantedScopes.contains(DriveRestApi.SCOPE_DRIVE_READONLY)) {
            throw SignInException("Cadence needs permission to read your Google Drive")
        }
        accessToken = token
        val user = try {
            profileLoader(token)
        } catch (e: Exception) {
            throw SignInException("Could not load your Google profile: ${e.message}", e)
        }
        prefs.edit { putString(KEY_USER, json.encodeToString(GoogleUser.serializer(), user)) }
        _state.value = AuthState.SignedIn(user)
    }

    override suspend fun accessToken(): String = tokenLock.withLock {
        accessToken?.let { return it }
        val result = client.authorize(request).await()
        val token = result.accessToken
        if (result.hasResolution() || token == null) {
            clearLocal()
            throw SignInException("Your Google session expired. Please sign in again.")
        }
        accessToken = token
        token
    }

    override suspend fun invalidate(token: String) {
        tokenLock.withLock {
            if (accessToken == token) accessToken = null
        }
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    override suspend fun signOut() {
        val user = cachedUser()
        if (user != null && user.email.isNotBlank()) {
            runCatching {
                client.revokeAccess(
                    RevokeAccessRequest.builder()
                        .setAccount(Account(user.email, "com.google"))
                        .setScopes(SCOPES.map(::Scope))
                        .build()
                ).await()
            }
        }
        accessToken?.let { token ->
            runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
        }
        clearLocal()
    }

    private fun clearLocal() {
        accessToken = null
        prefs.edit { clear() }
        _state.value = AuthState.SignedOut
    }

    private fun cachedUser(): GoogleUser? = prefs.getString(KEY_USER, null)?.let {
        runCatching { json.decodeFromString(GoogleUser.serializer(), it) }.getOrNull()
    }

    private companion object {
        const val KEY_USER = "user"
        val SCOPES = listOf(DriveRestApi.SCOPE_DRIVE_READONLY, "email", "profile")
    }
}
