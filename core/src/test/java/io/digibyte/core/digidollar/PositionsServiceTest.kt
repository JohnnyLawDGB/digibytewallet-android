package io.digibyte.core.digidollar

import io.digibyte.digidollar.MintMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #10 Positions: collateral positions are recovered from the wallet's
 * own Mint transactions via
 * [io.digibyte.core.bridge.NativeBridge.listDigiDollarMints] lines
 * (`txid:vout:valueSats:blockHeight:spent:opReturnHex`) — no local
 * persistence, so positions survive a seed restore. The service decodes each
 * Mint's OP_RETURN with the production [MintMetadata.parse], keeps only
 * unspent collateral owned by THIS wallet's Owner key, and yields the
 * [RedemptionService.CollateralPosition]s the Redemption flow consumes.
 */
class PositionsServiceTest {

    private val ownerKey = ByteArray(32) { 0x11 }
    private val ownerKeyHex = "11".repeat(32)
    private val mintTxid = "aa".repeat(32)
    private val collateralSats = 26_341_281_669L
    private val lockHeight = 17_000_000
    private val ddCents = 10_000L

    private fun mintOpReturn(
        ownerKeyHex: String = this.ownerKeyHex,
        lockHeight: Int = this.lockHeight,
        ddCents: Long = this.ddCents,
    ) = MintMetadata(
        ddCents = ddCents,
        unlockHeight = lockHeight,
        lockTier = 3,
        ownerKeyHex = ownerKeyHex,
    ).build()

    private fun line(
        txid: String = mintTxid,
        vout: Int = 0,
        valueSats: Long = collateralSats,
        blockHeight: Long = 16_500_000,
        spent: Int = 0,
        opReturnHex: String = mintOpReturn(),
    ) = "$txid:$vout:$valueSats:$blockHeight:$spent:$opReturnHex"

    private class FakeWallet(
        var mintLines: String,
        var ownerKey: ByteArray?,
    ) : PositionsService.WalletPort {
        val ownerKeyCalls = mutableListOf<Triple<Int, Int, Int>>()
        override fun listDigiDollarMints() = mintLines
        override fun deriveOwnerKey(coinType: Int, chain: Int, index: Int): ByteArray? {
            ownerKeyCalls.add(Triple(coinType, chain, index))
            return ownerKey
        }
    }

    @Test
    fun `an unspent mint owned by this wallet is an open position`() {
        val wallet = FakeWallet(line(), ownerKey)

        val positions = PositionsService(wallet).listOpenPositions()

        assertEquals(
            listOf(
                RedemptionService.CollateralPosition(
                    txidHex = mintTxid,
                    vout = 0,
                    valueSats = collateralSats,
                    lockHeight = lockHeight,
                    ddCents = ddCents,
                    ownerChain = 0,
                    ownerIndex = 0,
                ),
            ),
            positions,
        )
        // Ownership is checked against the one key the wallet mints with:
        // m/86'/20'/0'/0/0 (coin type ALWAYS 20 — MintService.OWNER_KEY_COIN_TYPE).
        assertEquals(
            listOf(Triple(MintService.OWNER_KEY_COIN_TYPE, 0, 0)),
            wallet.ownerKeyCalls,
        )
    }

    @Test
    fun `a redeemed mint - collateral spent - is not an open position`() {
        val redeemed = line(txid = "bb".repeat(32), spent = 1)
        val wallet = FakeWallet("$redeemed\n${line()}", ownerKey)

        val positions = PositionsService(wallet).listOpenPositions()

        assertEquals(listOf(mintTxid), positions.map { it.txidHex })
    }

    @Test
    fun `a mint whose Owner key is not this wallet's is not listed`() {
        val foreign = line(
            txid = "bb".repeat(32),
            opReturnHex = mintOpReturn(ownerKeyHex = "22".repeat(32)),
        )
        val wallet = FakeWallet("$foreign\n${line()}", ownerKey)

        val positions = PositionsService(wallet).listOpenPositions()

        assertEquals(listOf(mintTxid), positions.map { it.txidHex })
    }

    @Test
    fun `malformed lines and metadata are skipped - never crash the scan`() {
        val truncatedLine = "$mintTxid:0:$collateralSats" // too few fields
        val badOpReturn = line(txid = "bb".repeat(32), opReturnHex = "6a0244") // truncated push
        val notAMint = line(txid = "cc".repeat(32), opReturnHex = "6a024444010202b80b02581b")
        val badNumber = line(txid = "dd".repeat(32)).replaceFirst(":$collateralSats:", ":notanumber:")
        val wallet = FakeWallet(
            listOf(truncatedLine, badOpReturn, notAMint, badNumber, line(), "").joinToString("\n"),
            ownerKey,
        )

        val positions = PositionsService(wallet).listOpenPositions()

        assertEquals(listOf(mintTxid), positions.map { it.txidHex })
    }

    @Test
    fun `positions list soonest-unlocking first`() {
        val late = line(txid = "bb".repeat(32), opReturnHex = mintOpReturn(lockHeight = 18_000_000))
        val early = line(txid = "cc".repeat(32), opReturnHex = mintOpReturn(lockHeight = 16_000_000))
        val wallet = FakeWallet(listOf(late, line(), early).joinToString("\n"), ownerKey)

        val positions = PositionsService(wallet).listOpenPositions()

        assertEquals(
            listOf(16_000_000, lockHeight, 18_000_000),
            positions.map { it.lockHeight },
        )
    }

    @Test
    fun `a locked wallet - no Owner key - lists nothing`() {
        val wallet = FakeWallet(line(), ownerKey = null)

        assertEquals(emptyList<Any>(), PositionsService(wallet).listOpenPositions())
    }
}
