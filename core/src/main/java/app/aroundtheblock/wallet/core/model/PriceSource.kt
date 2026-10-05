package app.aroundtheblock.wallet.core.model

import app.aroundtheblock.wallet.core.PriceData

enum class PriceSource { API, ORACLE, CACHED }

interface OraclePriceProvider {
    suspend fun fetchOraclePrice(): PriceData?
    fun isAvailable(): Boolean
}
