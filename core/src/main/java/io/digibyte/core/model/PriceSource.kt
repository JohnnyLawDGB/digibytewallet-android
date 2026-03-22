package io.digibyte.core.model

import io.digibyte.core.PriceData

enum class PriceSource { API, ORACLE, CACHED }

interface OraclePriceProvider {
    suspend fun fetchOraclePrice(): PriceData?
    fun isAvailable(): Boolean
}
