package io.digibyte.core.digidollar

import io.digibyte.core.HttpFetcher
import io.digibyte.digidollar.Collateral
import io.digibyte.digidollar.DdAddress
import io.digibyte.digidollar.EcOps
import io.digibyte.digidollar.LockTiers
import io.digibyte.digidollar.MintBuilder
import io.digibyte.digidollar.TxOutput
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #11 Mint flow glue: status/gate/divergence policy checks, funding
 * UTXO selection over [io.digibyte.core.bridge.NativeBridge.listWalletUtxos]
 * lines, MintBuilder assembly, and the sign→broadcast→persist tail mirroring
 * TransactionBuilder.sendTransaction. All wallet/native surfaces enter
 * through constructor seams so the flow runs as a JVM test.
 */
class MintServiceTest {

    // ---- fixture ----

    private val price = 13_420L // micro-USD per DGB
    private val tier = LockTiers.byIndex(0)
    private val ddCents = 10_000L // $100 — consensus minimum
    private val collateral = Collateral.requiredSats(ddCents, tier, price)
    private val tipHeight = 18_000_000L

    private val healthyJson = """
        {"deployment":"active","priceMicroUsd":$price,
         "priceUpdatedAt":1730000000,"dcaMultiplierBps":10000}
    """.trimIndent()

    private val fundingTxid = "aa".repeat(32)
    private val changeHash160 = "cc".repeat(20)

    /** Fake EcOps: parity byte + the key back — flow tests exercise glue,
     *  not taproot math (that is proven in :digidollar fixture tests). */
    private val fakeEcOps = EcOps { key, _ -> ByteArray(1) + key }

    private class FakeWallet(
        var utxoLines: String = "",
        var change: String? = "dgb1qchange",
        var changeScript: ByteArray? = null,
        var ownerKey: ByteArray? = ByteArray(32) { 0x11 },
        var tip: Long = 0,
        /** Network-estimated height; defaults to the synced tip (fully synced). */
        var estimatedTip: Long = -1,
    ) : MintService.WalletPort {
        val ownerKeyCalls = mutableListOf<Triple<Int, Int, Int>>()
        override fun listWalletUtxos() = utxoLines
        override fun changeAddress() = change
        override fun addressToScriptPubKey(address: String) = changeScript
        override fun deriveOwnerKey(coinType: Int, chain: Int, index: Int): ByteArray? {
            ownerKeyCalls.add(Triple(coinType, chain, index))
            return ownerKey
        }
        override fun tipHeight() = tip
        override fun estimatedTipHeight() = if (estimatedTip >= 0) estimatedTip else tip
    }

    private class InMemoryStore : DigiDollarGate.Store {
        val map = mutableMapOf<String, Long>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: Long) { map[key] = value }
    }

    private fun service(
        wallet: FakeWallet,
        statusJson: String = healthyJson,
        independentUsd: Double? = price / 1_000_000.0,
        signMint: (MintBuilder.UnsignedMint, TxOutput) -> ByteArray = { _, _ -> SIGNED },
        broadcast: (ByteArray) -> String? = { BROADCAST_TXID },
        persistCalls: MutableList<Unit> = mutableListOf(),
        recordOutgoing: (String, Long, Long, String) -> Unit = { _, _, _, _ -> },
        testnet: Boolean = true,
        gate: DigiDollarGate = DigiDollarGate(InMemoryStore()),
    ) = MintService(
        wallet = wallet,
        statusClient = DigiDollarStatusClient(HttpFetcher { statusJson }),
        gate = gate,
        independentUsd = { independentUsd },
        signMint = signMint,
        broadcast = broadcast,
        persist = { persistCalls.add(Unit) },
        recordOutgoing = recordOutgoing,
        ecOps = fakeEcOps,
        testnet = testnet,
        nowMs = { NOW_MS },
    )

    private fun fundedWallet(valueSats: Long = collateral + MintBuilder.DEFAULT_FEE_SATS + 50_000_000) =
        FakeWallet(
            utxoLines = "$fundingTxid:1:$valueSats:0014${"bb".repeat(20)}",
            changeScript = ("0014$changeHash160").hexBytes(),
            tip = tipHeight,
        )

    // ---- happy path ----

    @Test
    fun `funded wallet with healthy status mints - signs, broadcasts, persists`() = runTest {
        val broadcasts = mutableListOf<ByteArray>()
        val persists = mutableListOf<Unit>()
        val wallet = fundedWallet()

        val result = service(
            wallet = wallet,
            broadcast = { broadcasts.add(it); BROADCAST_TXID },
            persistCalls = persists,
        ).mint(ddCents, tier)

        val success = result as MintService.MintResult.Success
        assertEquals(BROADCAST_TXID, success.txid)
        assertEquals(1, broadcasts.size)
        assertTrue(SIGNED.contentEquals(broadcasts.single()))
        assertEquals(1, persists.size)
        assertEquals(
            (tipHeight + 1 + MintBuilder.LOCK_CONFIRMATION_BUFFER_BLOCKS + tier.lockBlocks).toInt(),
            success.unlockHeight,
        )
        assertEquals(collateral, success.collateralSats)
    }

    // ---- policy blocks ----

    @Test
    fun `mainnet is hard-blocked until the 4_0_0 unlock - nothing broadcast`() = runTest {
        val broadcasts = mutableListOf<ByteArray>()

        val result = service(
            wallet = fundedWallet(),
            broadcast = { broadcasts.add(it); BROADCAST_TXID },
            testnet = false,
        ).mint(ddCents, tier)

        val blocked = result as MintService.MintResult.Blocked
        assertTrue(blocked.reason.contains("testnet"))
        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun `unreachable status endpoint blocks the mint - fail closed`() = runTest {
        val broadcasts = mutableListOf<ByteArray>()

        val result = service(
            wallet = fundedWallet(),
            statusJson = "not json at all",
            broadcast = { broadcasts.add(it); BROADCAST_TXID },
        ).mint(ddCents, tier)

        val blocked = result as MintService.MintResult.Blocked
        assertTrue(blocked.reason.contains("status unavailable"))
        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun `inactive softfork blocks the mint and is recorded to the gate`() = runTest {
        val broadcasts = mutableListOf<ByteArray>()
        val store = InMemoryStore()
        val gate = DigiDollarGate(store)
        gate.record(DigiDollarDeployment.ACTIVE, NOW_MS - 1000) // stale positive

        val result = service(
            wallet = fundedWallet(),
            statusJson = healthyJson.replace("active", "defined"),
            broadcast = { broadcasts.add(it); BROADCAST_TXID },
            gate = gate,
        ).mint(ddCents, tier)

        val blocked = result as MintService.MintResult.Blocked
        assertTrue(blocked.reason.contains("not active"))
        assertTrue(broadcasts.isEmpty())
        // The fresh INACTIVE overwrote the stale positive — the UI gate closes too.
        assertTrue(!gate.isOpen(NOW_MS))
    }

    @Test
    fun `oracle price diverging from the market price blocks the mint`() = runTest {
        val broadcasts = mutableListOf<ByteArray>()

        val result = service(
            wallet = fundedWallet(),
            independentUsd = price / 1_000_000.0 * 1.2, // 20% apart > 10% tolerance
            broadcast = { broadcasts.add(it); BROADCAST_TXID },
        ).mint(ddCents, tier)

        val blocked = result as MintService.MintResult.Blocked
        assertTrue(blocked.reason.contains("diverges"))
        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun `missing independent price blocks the mint - single-source mints forbidden`() = runTest {
        val broadcasts = mutableListOf<ByteArray>()

        val result = service(
            wallet = fundedWallet(),
            independentUsd = null,
            broadcast = { broadcasts.add(it); BROADCAST_TXID },
        ).mint(ddCents, tier)

        val blocked = result as MintService.MintResult.Blocked
        assertTrue(blocked.reason.contains("Independent DGB price unavailable"))
        assertTrue(broadcasts.isEmpty())
    }

    // ---- funding selection ----

    @Test
    fun `funding ignores non-P2WPKH coins even when they are larger`() = runTest {
        val sufficient = collateral + MintBuilder.DEFAULT_FEE_SATS + 50_000_000
        val legacyLine = "${"dd".repeat(32)}:0:${sufficient * 2}:76a914${"ee".repeat(20)}88ac"
        val p2wpkhLine = "$fundingTxid:1:$sufficient:0014${"bb".repeat(20)}"
        val wallet = fundedWallet().apply { utxoLines = "$legacyLine\n$p2wpkhLine" }
        val prevouts = mutableListOf<TxOutput>()

        val result = service(
            wallet = wallet,
            signMint = { _, prevout -> prevouts.add(prevout); SIGNED },
        ).mint(ddCents, tier)

        assertTrue(result is MintService.MintResult.Success)
        assertEquals("0014${"bb".repeat(20)}", prevouts.single().scriptPubKeyHex)
    }

    @Test
    fun `no single coin covering collateral plus fee - actionable error, nothing signed`() = runTest {
        val half = (collateral + MintBuilder.DEFAULT_FEE_SATS) / 2
        val wallet = fundedWallet().apply {
            // Two coins that only cover the Mint TOGETHER — single-input funding
            // (the reference builder's shape) must refuse and say why.
            utxoLines = "$fundingTxid:1:$half:0014${"bb".repeat(20)}\n" +
                "${"dd".repeat(32)}:0:$half:0014${"bb".repeat(20)}"
        }
        val signs = mutableListOf<Unit>()

        val result = service(
            wallet = wallet,
            signMint = { _, _ -> signs.add(Unit); SIGNED },
        ).mint(ddCents, tier)

        val error = result as MintService.MintResult.Error
        assertTrue(error.message.contains("single"))
        assertTrue(signs.isEmpty())
    }

    @Test
    fun `empty wallet - error, not a crash`() = runTest {
        val wallet = fundedWallet().apply { utxoLines = "" }

        val result = service(wallet = wallet).mint(ddCents, tier)

        assertTrue(result is MintService.MintResult.Error)
    }

    @Test
    fun `built tx spends the selected coin and pays change to the wallet change script`() = runTest {
        val changeSats = 50_000_000L
        val wallet = fundedWallet(valueSats = collateral + MintBuilder.DEFAULT_FEE_SATS + changeSats)
        val unsignedMints = mutableListOf<MintBuilder.UnsignedMint>()

        val result = service(
            wallet = wallet,
            signMint = { unsigned, _ -> unsignedMints.add(unsigned); SIGNED },
        ).mint(ddCents, tier)

        assertTrue(result is MintService.MintResult.Success)
        val tx = unsignedMints.single().tx
        assertEquals(fundingTxid, tx.inputs.single().txidHex)
        assertEquals(1, tx.inputs.single().vout)
        val change = tx.outputs.last()
        assertEquals("0014$changeHash160", change.scriptPubKeyHex)
        assertEquals(changeSats, change.valueSats)
    }

    // ---- mechanical failures ----

    @Test
    fun `locked session - owner key unavailable becomes a typed error`() = runTest {
        val wallet = fundedWallet().apply { ownerKey = null }

        val result = service(wallet = wallet).mint(ddCents, tier)

        val error = result as MintService.MintResult.Error
        assertTrue(error.message.contains("locked"))
    }

    @Test
    fun `change address unavailable becomes a typed error`() = runTest {
        val wallet = fundedWallet().apply { change = null }

        val result = service(wallet = wallet).mint(ddCents, tier)

        assertTrue(result is MintService.MintResult.Error)
    }

    @Test
    fun `broadcast failure - error and the wallet snapshot is NOT persisted`() = runTest {
        val persists = mutableListOf<Unit>()

        val result = service(
            wallet = fundedWallet(),
            broadcast = { null },
            persistCalls = persists,
        ).mint(ddCents, tier)

        val error = result as MintService.MintResult.Error
        assertTrue(error.message.contains("broadcast"))
        assertTrue(persists.isEmpty())
    }

    @Test
    fun `signer refusal surfaces its reason as a typed error`() = runTest {
        val result = service(
            wallet = fundedWallet(),
            signMint = { _, _ -> error("wallet signer rejected the Mint (locked session or foreign funding input)") },
        ).mint(ddCents, tier)

        val error = result as MintService.MintResult.Error
        assertTrue(error.message.contains("wallet signer rejected"))
    }

    @Test
    fun `unsynced wallet - no tip height means no mint`() = runTest {
        val wallet = fundedWallet().apply { tip = 0 }

        val result = service(wallet = wallet).mint(ddCents, tier)

        val error = result as MintService.MintResult.Error
        assertTrue(error.message.contains("sync"))
    }

    @Test
    fun `amount below the consensus minimum - typed error, not a crash`() = runTest {
        val below = service(wallet = fundedWallet()).mint(ddCents = 500, tier = tier)
        val zero = service(wallet = fundedWallet()).mint(ddCents = 0, tier = tier)

        assertTrue((below as MintService.MintResult.Error).message.contains("minimum"))
        assertTrue(zero is MintService.MintResult.Error)
    }

    // ---- review-driven behaviors ----

    @Test
    fun `owner key comes from the wallet's watched chain - coinType 20 even on testnet`() = runTest {
        // The C wallet watches m/86'/20' on BOTH networks (DGB_COIN_TYPE has no
        // testnet variant); a coinType-1 owner key would mint DD tokens the
        // wallet can never detect or redeem.
        val wallet = fundedWallet()

        service(wallet = wallet).mint(ddCents, tier)

        assertEquals(listOf(Triple(20, 0, 0)), wallet.ownerKeyCalls)
    }

    @Test
    fun `wallet lagging the network tip - mint blocked until caught up`() = runTest {
        // unlockHeight commits to the tip; the 100-block buffer only absorbs
        // ~25 minutes of lag on 15s blocks.
        val wallet = fundedWallet().apply { estimatedTip = tip + 500 }

        val result = service(wallet = wallet).mint(ddCents, tier)

        val error = result as MintService.MintResult.Error
        assertTrue(error.message.contains("sync"))
    }

    @Test
    fun `malformed native utxo line - typed error, not a crash`() = runTest {
        val wallet = fundedWallet().apply { utxoLines = "deadbeef:notanumber" }

        assertTrue(service(wallet = wallet).mint(ddCents, tier) is MintService.MintResult.Error)
    }

    @Test
    fun `hostile status values that overflow collateral math - typed error, not a crash`() = runTest {
        val hostile = healthyJson.replace(
            "\"dcaMultiplierBps\":10000",
            "\"dcaMultiplierBps\":5000000000000",
        )

        val result = service(wallet = fundedWallet(), statusJson = hostile).mint(ddCents, tier)

        assertTrue(result is MintService.MintResult.Error)
    }

    @Test
    fun `second mint while one is in flight is rejected - no double-spend of the funding coin`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val svc = service(
            wallet = fundedWallet(),
            signMint = { _, _ ->
                entered.countDown()
                release.await()
                SIGNED
            },
        )

        var first: MintService.MintResult? = null
        val worker = Thread { first = kotlinx.coroutines.runBlocking { svc.mint(ddCents, tier) } }
        worker.start()
        entered.await()

        val second = kotlinx.coroutines.runBlocking { svc.mint(ddCents, tier) }

        release.countDown()
        worker.join()
        assertTrue((second as MintService.MintResult.Error).message.contains("in progress"))
        assertTrue(first is MintService.MintResult.Success)
    }

    @Test
    fun `successful mint is recorded for the activity list with the DD address`() = runTest {
        val records = mutableListOf<List<Any>>()

        val result = service(
            wallet = fundedWallet(),
            recordOutgoing = { txid, sent, fee, to -> records.add(listOf(txid, sent, fee, to)) },
        ).mint(ddCents, tier)

        assertTrue(result is MintService.MintResult.Success)
        val (txid, sent, fee, to) = records.single()
        assertEquals(BROADCAST_TXID, txid)
        assertEquals(collateral, sent)
        assertEquals(MintBuilder.DEFAULT_FEE_SATS, fee)
        // Fake EcOps is identity on the key, so the DD-token output key is the
        // owner key itself; the recorded address must be its TD… encoding.
        assertEquals(
            DdAddress.encode(ByteArray(32) { 0x11 }, DdAddress.Network.TESTNET),
            to,
        )
    }

    // ---- slippage cap (preview-vs-actual collateral drift) ----

    @Test
    fun `blocks when fresh collateral exceeds the approved cap`() = runTest {
        // maxCollateral one sat below the freshly-priced collateral: the price
        // "moved" against the user since the preview, so lock is refused.
        val result = service(wallet = fundedWallet())
            .mint(ddCents, tier, maxCollateralSats = collateral - 1)
        assertTrue(result is MintService.MintResult.Blocked)
    }

    @Test
    fun `mints when fresh collateral is within the approved cap`() = runTest {
        val result = service(wallet = fundedWallet())
            .mint(ddCents, tier, maxCollateralSats = collateral)
        assertTrue(result is MintService.MintResult.Success)
    }

    // ---- currentStatus (UI collateral-preview seam) ----

    @Test
    fun `currentStatus parses the live price and dca for the preview`() = runTest {
        val status = service(wallet = fundedWallet()).currentStatus()
        assertEquals(DigiDollarDeployment.ACTIVE, status?.deployment)
        assertEquals(price, status?.priceMicroUsd)
        assertEquals(10_000L, status?.dcaMultiplierBps)
    }

    @Test
    fun `currentStatus is null when the endpoint is unusable`() = runTest {
        val status = service(wallet = fundedWallet(), statusJson = "not json").currentStatus()
        assertEquals(null, status)
    }

    @Test
    fun `failed broadcast records nothing for the activity list`() = runTest {
        val records = mutableListOf<List<Any>>()

        service(
            wallet = fundedWallet(),
            broadcast = { null },
            recordOutgoing = { txid, sent, fee, to -> records.add(listOf(txid, sent, fee, to)) },
        ).mint(ddCents, tier)

        assertTrue(records.isEmpty())
    }

    private companion object {
        val SIGNED = byteArrayOf(0x5e, 0x11, 0x22)
        val BROADCAST_TXID = "f0".repeat(32) // 64-char txid
        const val NOW_MS = 1_730_000_100_000L
    }
}

private fun String.hexBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
