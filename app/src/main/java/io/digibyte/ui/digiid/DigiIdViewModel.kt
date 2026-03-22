package io.digibyte.ui.digiid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.db.dao.DigiIdHistoryDao
import io.digibyte.core.db.entity.DigiIdHistoryEntity
import io.digibyte.core.digiid.DigiIdManager
import io.digibyte.core.model.DigiIdRequest
import io.digibyte.core.model.DigiIdResult
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

    fun authenticate(request: DigiIdRequest) {
        viewModelScope.launch {
            _isLoading.value = true
            _authResult.value = null
            _authResult.value = digiIdManager.authenticate(request)
            _isLoading.value = false
        }
    }

    fun clearResult() {
        _authResult.value = null
    }
}
