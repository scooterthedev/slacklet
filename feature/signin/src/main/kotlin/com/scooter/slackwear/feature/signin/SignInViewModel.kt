package com.scooter.slackwear.feature.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scooter.slackwear.core.auth.InternalSlackAuthenticator
import com.scooter.slackwear.core.model.repository.RelaySignInGateway
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SignInViewModel(
    private val authenticator: InternalSlackAuthenticator,
    private val gateway: RelaySignInGateway,
    relayBaseUrl: String,
    defaultDomain: String = "hackclub",
) : ViewModel() {

    private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private val _domain = MutableStateFlow(defaultDomain)
    val domain: StateFlow<String> = _domain.asStateFlow()

    private val host: String =
        relayBaseUrl.trimEnd('/').removePrefix("https://").removePrefix("http://")

    private var pollJob: Job? = null

    fun setDomain(value: String) {
        _domain.value = value.trim()
    }

    fun signIn() {
        val current = _state.value
        if (current is SignInState.Starting || current is SignInState.Waiting) return

        _state.value = SignInState.Starting
        viewModelScope.launch {
            val started = gateway.start(_domain.value).getOrElse {
                _state.value = SignInState.Failed(it.message ?: "Could not reach the sign-in relay")
                return@launch
            }
            _state.value = SignInState.Waiting(
                code = started.code,
                signInUrl = "$host/auth/${started.code}",
            )
            pollForHandback(started.code)
        }
    }

    private fun pollForHandback(code: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)

                val status = gateway.poll(code).getOrNull() ?: continue
                status.error?.let { error ->
                    _state.value = SignInState.Failed(error)
                    return@launch
                }
                val token = status.magicToken
                val teamId = status.teamId
                if (token != null && teamId != null) {
                    _state.value = authenticator.redeemSsoToken(teamId, token, _domain.value).fold(
                        onSuccess = { SignInState.Succeeded },
                        onFailure = { SignInState.Failed(it.message ?: "Sign-in failed") },
                    )
                    return@launch
                }
            }
        }
    }

    fun cancel() {
        pollJob?.cancel()
        _state.value = SignInState.Idle
    }

    fun dismissError() {
        _state.value = SignInState.Idle
    }

    override fun onCleared() {
        pollJob?.cancel()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}

sealed interface SignInState {
    data object Idle : SignInState

    data object Starting : SignInState

    data class Waiting(val code: String, val signInUrl: String) : SignInState

    data object Succeeded : SignInState

    data class Failed(val message: String) : SignInState
}
