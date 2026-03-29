package io.digibyte.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.digiscope.DigiScopeClient
import io.digibyte.core.digiscope.DigiScopeProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ProfileState {
    data object Loading : ProfileState()
    data object NotLoggedIn : ProfileState()
    data class NoHandle(val address: String, val tipBalance: Long) : ProfileState()
    data class HasProfile(val profile: DigiScopeProfile) : ProfileState()
    data class Error(val message: String) : ProfileState()
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val digiScopeClient: DigiScopeClient
) : ViewModel() {

    private val _profileState = MutableStateFlow<ProfileState>(ProfileState.Loading)
    val profileState: StateFlow<ProfileState> = _profileState.asStateFlow()

    // Handle registration UI state
    private val _handleInput = MutableStateFlow("")
    val handleInput: StateFlow<String> = _handleInput.asStateFlow()

    private val _handleAvailable = MutableStateFlow<Boolean?>(null)
    val handleAvailable: StateFlow<Boolean?> = _handleAvailable.asStateFlow()

    private val _isCheckingHandle = MutableStateFlow(false)
    val isCheckingHandle: StateFlow<Boolean> = _isCheckingHandle.asStateFlow()

    private val _isRegistering = MutableStateFlow(false)
    val isRegistering: StateFlow<Boolean> = _isRegistering.asStateFlow()

    private val _registrationError = MutableStateFlow<String?>(null)
    val registrationError: StateFlow<String?> = _registrationError.asStateFlow()

    // Handle validation regex: 3-20 chars, alphanumeric + underscore
    private val handleRegex = Regex("^[a-zA-Z0-9_]{3,20}$")

    init {
        loadProfile()
    }

    // ── Quick login (no QR scan) ────────────────────────────────────────

    fun quickLogin() {
        _profileState.value = ProfileState.Loading
        viewModelScope.launch {
            val success = digiScopeClient.quickLogin()
            if (success) {
                loadProfile()
            } else {
                _profileState.value = ProfileState.Error("Login failed. Check your connection.")
            }
        }
    }

    // ── Profile loading ───────────────────────────────────────────────────

    fun loadProfile() {
        if (!digiScopeClient.isLoggedIn()) {
            _profileState.value = ProfileState.NotLoggedIn
            return
        }

        _profileState.value = ProfileState.Loading
        viewModelScope.launch {
            val profile = digiScopeClient.getProfile()
            _profileState.value = when {
                profile == null -> ProfileState.Error("Failed to load profile. Check your connection.")
                profile.handle == null -> ProfileState.NoHandle(
                    address = profile.address,
                    tipBalance = profile.tipBalance
                )
                else -> ProfileState.HasProfile(profile)
            }
        }
    }

    // ── Handle input ──────────────────────────────────────────────────────

    fun onHandleInputChange(value: String) {
        // Only allow valid chars, max 20
        val filtered = value.filter { it.isLetterOrDigit() || it == '_' }.take(20)
        _handleInput.value = filtered
        _handleAvailable.value = null
        _registrationError.value = null
    }

    fun isHandleFormatValid(): Boolean = handleRegex.matches(_handleInput.value)

    // ── Availability check ────────────────────────────────────────────────

    fun checkAvailability() {
        val handle = _handleInput.value
        if (!handleRegex.matches(handle)) {
            _registrationError.value = "Handle must be 3-20 alphanumeric characters or underscores"
            return
        }

        _isCheckingHandle.value = true
        _handleAvailable.value = null
        _registrationError.value = null

        viewModelScope.launch {
            val available = digiScopeClient.checkHandleAvailable(handle)
            _handleAvailable.value = available
            _isCheckingHandle.value = false
            if (!available) {
                _registrationError.value = "\"$handle\" is already taken"
            }
        }
    }

    // ── Handle registration ────────────────────────────────────────────────

    fun registerHandle() {
        val handle = _handleInput.value
        if (!handleRegex.matches(handle)) {
            _registrationError.value = "Handle must be 3-20 alphanumeric characters or underscores"
            return
        }
        if (_handleAvailable.value != true) {
            _registrationError.value = "Please check availability first"
            return
        }

        _isRegistering.value = true
        _registrationError.value = null

        viewModelScope.launch {
            val success = digiScopeClient.registerHandle(handle)
            _isRegistering.value = false
            if (success) {
                // Reload profile to show the new handle
                loadProfile()
            } else {
                _registrationError.value = "Registration failed. Try a different handle."
            }
        }
    }

    // ── Logout / unlink ───────────────────────────────────────────────────

    fun logout() {
        digiScopeClient.logout()
        _profileState.value = ProfileState.NotLoggedIn
        _handleInput.value = ""
        _handleAvailable.value = null
        _registrationError.value = null
    }

    fun clearRegistrationError() {
        _registrationError.value = null
    }
}
