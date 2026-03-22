package io.digibyte.core.model

data class Wallet(
    val isCreated: Boolean = false,
    val isLocked: Boolean = true,
    val creationTimestamp: Long = 0L, // Unix seconds — used for recovery checkpoint
    val defaultAddressFormat: Int = 2 // 0=legacy, 1=p2sh, 2=bech32
)
