package io.digibyte.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.asset.AssetManager
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.entity.TransactionEntity
import io.digibyte.core.model.OwnedAsset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AssetViewModel @Inject constructor(
    private val assetManager: AssetManager,
    private val assetMetadataDao: AssetMetadataDao
) : ViewModel() {

    val ownedAssets: StateFlow<List<OwnedAsset>> = assetManager.getOwnedAssets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedAssetId = MutableStateFlow<String?>(null)

    val selectedAsset: StateFlow<OwnedAsset?> = _selectedAssetId
        .combine(ownedAssets) { id, assets -> assets.find { it.assetId == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val assetHistory: StateFlow<List<TransactionEntity>> =
        assetManager.getAssetHistory(_selectedAssetId.value ?: "")
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectAsset(assetId: String) {
        _selectedAssetId.value = assetId
    }
}
