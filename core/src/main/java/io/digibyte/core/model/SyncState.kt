package io.digibyte.core.model

sealed class SyncState {
    data object Idle : SyncState()
    data class Syncing(val progress: Float, val blockHeight: Long) : SyncState()
    data object Complete : SyncState()
    data class Failed(val errorCode: Int, val message: String) : SyncState()
    data class ChainSplit(val message: String) : SyncState()
}
