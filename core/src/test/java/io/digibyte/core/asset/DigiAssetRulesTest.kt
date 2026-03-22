package io.digibyte.core.asset

import io.digibyte.core.model.AssetData
import io.digibyte.core.model.AssetOperation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DigiAssetRules] (Phase 2 stub).
 */
class DigiAssetRulesTest {

    private lateinit var rules: DigiAssetRules

    private val fungibleAsset = AssetData(
        assetId = "Ua94nEKabzhJeDJtxGFXdviT185tYeHqyHKeWC",
        quantity = 1000L,
        operation = AssetOperation.TRANSFER,
        metadataCid = null,
        locked = false,
        divisibility = 2
    )

    private val lockedAsset = AssetData(
        assetId = "La3fq5SvLvxHssNL9FJESiemGwMVsou89tuD3m",
        quantity = 100L,
        operation = AssetOperation.ISSUANCE,
        metadataCid = "bafkreifitsknjetrdz6lcp4fumlrmmiuakkhpa74hru3642jqwr3qlcj3e",
        locked = true,
        divisibility = 0
    )

    @Before
    fun setUp() {
        rules = DigiAssetRules()
    }

    @Test
    fun `validateTransfer allows positive quantity for fungible asset`() {
        val result = rules.validateTransfer(fungibleAsset, 100L, "DFVmLCbkCC9SC7XFA3SUuG3L5fdCbRNAyY")
        assertTrue(result.allowed)
    }

    @Test
    fun `validateTransfer rejects zero quantity`() {
        val result = rules.validateTransfer(fungibleAsset, 0L, "DFVmLCbkCC9SC7XFA3SUuG3L5fdCbRNAyY")
        assertFalse(result.allowed)
        assertTrue(result.reason!!.contains("positive"))
    }

    @Test
    fun `validateTransfer rejects negative quantity`() {
        val result = rules.validateTransfer(fungibleAsset, -1L, "DFVmLCbkCC9SC7XFA3SUuG3L5fdCbRNAyY")
        assertFalse(result.allowed)
    }

    @Test
    fun `validateTransfer rejects splitting dispersed asset`() {
        val nftAsset = fungibleAsset.copy(quantity = 1L)
        val result = rules.validateTransfer(
            nftAsset, 2L, "DFVmLCbkCC9SC7XFA3SUuG3L5fdCbRNAyY",
            aggregation = Aggregation.DISPERSED
        )
        assertFalse(result.allowed)
        assertTrue(result.reason!!.contains("Dispersed"))
    }

    @Test
    fun `validateTransfer allows quantity 1 for dispersed asset`() {
        val nftAsset = fungibleAsset.copy(quantity = 1L)
        val result = rules.validateTransfer(
            nftAsset, 1L, "DFVmLCbkCC9SC7XFA3SUuG3L5fdCbRNAyY",
            aggregation = Aggregation.DISPERSED
        )
        assertTrue(result.allowed)
    }

    @Test
    fun `canReissue returns false for locked asset`() {
        assertFalse(rules.canReissue(lockedAsset))
    }

    @Test
    fun `canReissue returns true for unlocked asset`() {
        assertTrue(rules.canReissue(fungibleAsset))
    }
}
