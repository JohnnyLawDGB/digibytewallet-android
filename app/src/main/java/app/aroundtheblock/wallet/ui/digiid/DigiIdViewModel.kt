package app.aroundtheblock.wallet.ui.digiid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import app.aroundtheblock.wallet.core.db.dao.DigiIdHistoryDao
import app.aroundtheblock.wallet.core.db.entity.DigiIdHistoryEntity
import app.aroundtheblock.wallet.core.digiid.DigiIdManager
import app.aroundtheblock.wallet.core.digiid.IdentityKeyKind
import app.aroundtheblock.wallet.core.model.DigiIdRequest
import app.aroundtheblock.wallet.core.model.DigiIdResult
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DigiIdViewModel @Inject constructor(
    private val digiIdManager: DigiIdManager,
    private val historyDao: DigiIdHistoryDao
) : ViewModel() {

    /** Live-updating list of past Digi-ID logins, newest first. */
    val history: StateFlow<List<DigiIdHistoryEntity>> = historyDao.getHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _authResult = MutableStateFlow<DigiIdResult?>(null)
    val authResult: StateFlow<DigiIdResult?> = _authResult.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** The identities offered for this login, or null when the site's history decides (row 16). */
    private val _identityChoice = MutableStateFlow<DigiIdManager.IdentityChoice?>(null)
    val identityChoice: StateFlow<DigiIdManager.IdentityChoice?> = _identityChoice.asStateFlow()

    /** The user's pick; per-site unless they choose the shared key. */
    private val _selectedIdentity = MutableStateFlow(IdentityKeyKind.PER_SITE)
    val selectedIdentity: StateFlow<IdentityKeyKind> = _selectedIdentity.asStateFlow()

    private var choiceLoadedFor: String? = null

    fun loadIdentityChoice(request: DigiIdRequest) {
        if (choiceLoadedFor == request.rawUri) return
        choiceLoadedFor = request.rawUri
        viewModelScope.launch { _identityChoice.value = digiIdManager.identityChoice(request) }
    }

    fun selectIdentity(kind: IdentityKeyKind) {
        _selectedIdentity.value = kind
    }

    fun authenticate(request: DigiIdRequest) {
        viewModelScope.launch {
            _isLoading.value = true
            _authResult.value = null
            val requested = if (_identityChoice.value != null) _selectedIdentity.value else null
            _authResult.value = digiIdManager.authenticate(request, requested)
            _isLoading.value = false
        }
    }

    fun clearResult() {
        _authResult.value = null
    }
}
