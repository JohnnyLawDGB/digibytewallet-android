package io.digibyte.core.digidollar

import io.digibyte.core.HttpFetcher
import io.digibyte.digidollar.EcOps
import io.digibyte.digidollar.MintBuilder
import io.digibyte.digidollar.RedemptionBuilder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #13 Redemption flow glue: status/gate/position-unlocked policy checks,
 * DD-token burn selection over
 * [io.digibyte.core.bridge.NativeBridge.listDigiDollarUtxos] lines, fee
 * selection over listWalletUtxos, RedemptionBuilder assembly, and the
 * sign→broadcast→persist tail. All wallet/native surfaces enter through
 * constructor seams so the flow runs as a JVM test.
 */
class RedemptionServiceTest {

    // ---- fixture ----

    private val price = 13_420L
    private val ddCents = 10_000L // $100
    private val lockHeight = 17_000_000
    private val tipHeight = 18_000_000L // well past the lock
    private val collateralSats = 26_341_281_669L

    private val healthyJson = """
        {"deployment":"active","priceMicroUsd":$price,
         "priceUpdatedAt":1730000000,"dcaMultiplierBps":10000}
    """.trimIndent()

    private val collateralTxid = "aa".repeat(32)
    private val ddTxid = "bb".repeat(32)
    private val feeTxid = "cc".repeat(32)
    private val changeHash160 = "dd".repeat(20)
    private val ddScript = "5120" + "ab".repeat(32) // 68-char P2TR DD-token script

    private val position = RedemptionService.CollateralPosition(
        txidHex = collateralTxid,
        vout = 0,
        valueSats = collateralSats,
        lockHeight = lockHeight,
        ddCents = ddCents,
    )

    private val fakeEcOps = EcOps { key, _ -> ByteArray(1) + key }

    private class FakeWallet(
        var ddLines: String,
        var walletLines: String,
        var change: String? = "dgb1qchange",
        var changeScript: ByteArray? = null,
        var ownerKey: ByteArray? = ByteArray(32) { 0x11 },
        var tip: Long = 0,
        var estimatedTip: Long = -1,
    ) : RedemptionService.WalletPort {
        val ownerKeyCalls = mutableListOf<Triple<Int, Int, Int>>()
        override fun listDigiDollarUtxos() = ddLines
        override fun listWalletUtxos() = walletLines
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
        signRedemption: (
            RedemptionBuilder.UnsignedRedemption,
            DigiDollarTxSigner.OwnerKeyPath,
            List<DigiDollarTxSigner.OwnerKeyPath>,
        ) -> ByteArray = { _, _, _ -> SIGNED },
        broadcast: (ByteArray) -> String? = { BROADCAST_TXID },
        persistCalls: MutableList<Unit> = mutableListOf(),
        recordOutgoing: (String, Long, Long, String) -> Unit = { _, _, _, _ -> },
        testnet: Boolean = true,
        gate: DigiDollarGate = DigiDollarGate(InMemoryStore()),
    ) = RedemptionService(
        wallet = wallet,
        statusClient = DigiDollarStatusClient(HttpFetcher { statusJson }),
        gate = gate,
        signRedemption = signRedemption,
        broadcast = broadcast,
        persist = { persistCalls.add(Unit) },
        recordOutgoing = recordOutgoing,
        ecOps = fakeEcOps,
        testnet = testnet,
        nowMs = { NOW_MS },
    )

    private fun redeemableWallet(
        ddLines: String = "$ddTxid:0:$ddCents:$ddScript",
        feeSats: Long = MintBuilder.DEFAULT_FEE_SATS + 50_000_000,
    ) = FakeWallet(
        ddLines = ddLines,
        walletLines = "$feeTxid:1:$feeSats:0014${"ee".repeat(20)}",
        changeScript = ("0014$changeHash160").hexBytes(),
        tip = tipHeight,
    )

    // ---- happy path ----

    @Test
    fun `unlocked position with healthy status redeems - signs, broadcasts, persists`() = runTest {
        val broadcasts = mutableListOf<ByteArray>()
        val persists = mutableListOf<Unit>()

        val result = service(
            wallet = redeemableWallet(),
            broadcast = { broadcasts.add(it); BROADCAST_TXID },
            persistCalls = persists,
        ).redeem(position)

        val success = result as RedemptionService.RedemptionResult.Success
        assertEquals(BROADCAST_TXID, success.txid)
        assertEquals(collateralSats, success.collateralReturnedSats)
        assertEquals(0L, success.ddChangeCents) // exact burn -> no DD change
        assertEquals(1, broadcasts.size)
        assertTrue(SIGNED.contentEquals(broadcasts.single()))
        assertEquals(1, persists.size)
    }

    @Test
    fun `built redeem spends the collateral, burns the DD token, and pays the fee input`() = runTest {
        val unsignedRedemptions = mutableListOf<RedemptionBuilder.UnsignedRedemption>()

        val result = service(
            wallet = redeemableWallet(),
            signRedemption = { u, _, _ -> unsignedRedemptions.add(u); SIGNED },
        ).redeem(position)

        assertTrue(result is RedemptionService.RedemptionResult.Success)
        val tx = unsignedRedemptions.single().tx
        // vin0 = collateral, vin1 = DD burn, vinLast = fee
        assertEquals(collateralTxid, tx.inputs.first().txidHex)
        assertEquals(ddTxid, tx.inputs[1].txidHex)
        assertEquals(feeTxid, tx.inputs.last().txidHex)
        // vout0 = full collateral back to the wallet change script
        assertEquals("0014$changeHash160", tx.outputs.first().scriptPubKeyHex)
        assertEquals(collateralSats, tx.outputs.first().valueSats)
    }

    @Test
    fun `owner and burn paths are all on the watched chain coinType 20`() = runTest {
        val paths = mutableListOf<Pair<DigiDollarTxSigner.OwnerKeyPath, List<DigiDollarTxSigner.OwnerKeyPath>>>()

        service(
            wallet = redeemableWallet(),
            signRedemption = { _, owner, burns -> paths.add(owner to burns); SIGNED },
        ).redeem(position)

        val (owner, burns) = paths.single()
        assertEquals(DigiDollarTxSigner.OwnerKeyPath(20, 0, 0), owner)
        assertEquals(listOf(DigiDollarTxSigner.OwnerKeyPath(20, 0, 0)), burns) // one burn
    }

    @Test
    fun `multiple DD coins are burned largest-first to cover the position, change returned`() = runTest {
        // Two coins of 6000 cents each cover the 10000-cent position; the
        // overshoot (2000 cents) comes back as DD change.
        val wallet = redeemableWallet(
            ddLines = "$ddTxid:0:6000:$ddScript\n${"9f".repeat(32)}:2:6000:$ddScript",
        )
        val captured = mutableListOf<RedemptionBuilder.UnsignedRedemption>()
        val burnPathCounts = mutableListOf<Int>()

        val result = service(
            wallet = wallet,
            signRedemption = { u, _, burns -> captured.add(u); burnPathCounts.add(burns.size); SIGNED },
        ).redeem(position)

        val success = result as RedemptionService.RedemptionResult.Success
        assertEquals(2_000L, success.ddChangeCents)
        assertEquals(2, burnPathCounts.single()) // two burns -> two owner paths
        // vin: collateral + 2 burns + fee = 4
        assertEquals(4, captured.single().tx.inputs.size)
    }

    // ---- policy blocks ----

    @Test
    fun `mainnet is hard-blocked until the 4_0_0 unlock - nothing broadcast`() = runTest {
        val broadcasts = mutableListOf<ByteArray>()

        val result = service(
            wallet = redeemableWallet(),
            broadcast = { broadcasts.add(it); BROADCAST_TXID },
            testnet = false,
        ).redeem(position)

        assertTrue((result as RedemptionService.RedemptionResult.Blocked).reason.contains("testnet"))
        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun `unreachable status endpoint blocks the redemption - fail closed`() = runTest {
        val broadcasts = mutableListOf<ByteArray>()

        val result = service(
            wallet = redeemableWallet(),
            statusJson = "not json at all",
            broadcast = { broadcasts.add(it); BROADCAST_TXID },
        ).redeem(position)

        assertTrue((result as RedemptionService.RedemptionResult.Blocked).reason.contains("status unavailable"))
        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun `inactive softfork blocks the redemption and is recorded to the gate`() = runTest {
        val store = InMemoryStore()
        val gate = DigiDollarGate(store)
        gate.record(DigiDollarDeployment.ACTIVE, NOW_MS - 1000)

        val result = service(
            wallet = redeemableWallet(),
            statusJson = healthyJson.replace("active", "defined"),
            gate = gate,
        ).redeem(position)

        assertTrue((result as RedemptionService.RedemptionResult.Blocked).reason.contains("not active"))
        assertTrue(!gate.isOpen(NOW_MS))
    }

    @Test
    fun `still-locked position is blocked - nothing signed`() = runTest {
        val signs = mutableListOf<Unit>()
        val wallet = redeemableWallet().apply {
            tip = lockHeight - 100L
            estimatedTip = lockHeight - 100L
        }

        val result = service(
            wallet = wallet,
            signRedemption = { _, _, _ -> signs.add(Unit); SIGNED },
        ).redeem(position)

        assertTrue((result as RedemptionService.RedemptionResult.Blocked).reason.contains("time-locked"))
        assertTrue(signs.isEmpty())
    }

    // ---- selection failures ----

    @Test
    fun `insufficient DD balance - actionable error, nothing signed`() = runTest {
        val signs = mutableListOf<Unit>()
        val wallet = redeemableWallet(ddLines = "$ddTxid:0:${ddCents - 1}:$ddScript")

        val result = service(
            wallet = wallet,
            signRedemption = { _, _, _ -> signs.add(Unit); SIGNED },
        ).redeem(position)

        assertTrue((result as RedemptionService.RedemptionResult.Error).message.contains("Not enough"))
        assertTrue(signs.isEmpty())
    }

    @Test
    fun `no fee coin covering the DD fee - actionable error`() = runTest {
        val wallet = redeemableWallet(feeSats = MintBuilder.DEFAULT_FEE_SATS - 1)

        val result = service(wallet = wallet).redeem(position)

        assertTrue((result as RedemptionService.RedemptionResult.Error).message.contains("fee"))
    }

    @Test
    fun `fee selection ignores non-P2WPKH coins even when larger`() = runTest {
        val sufficient = MintBuilder.DEFAULT_FEE_SATS + 50_000_000
        val legacy = "${"1a".repeat(32)}:0:${sufficient * 2}:76a914${"ee".repeat(20)}88ac"
        val p2wpkh = "$feeTxid:1:$sufficient:0014${"ee".repeat(20)}"
        val wallet = redeemableWallet().apply { walletLines = "$legacy\n$p2wpkh" }
        val captured = mutableListOf<RedemptionBuilder.UnsignedRedemption>()

        val result = service(
            wallet = wallet,
            signRedemption = { u, _, _ -> captured.add(u); SIGNED },
        ).redeem(position)

        assertTrue(result is RedemptionService.RedemptionResult.Success)
        assertEquals(feeTxid, captured.single().tx.inputs.last().txidHex)
    }

    @Test
    fun `empty DD wallet - error, not a crash`() = runTest {
        val wallet = redeemableWallet(ddLines = "")
        assertTrue(service(wallet = wallet).redeem(position) is RedemptionService.RedemptionResult.Error)
    }

    // ---- mechanical failures ----

    @Test
    fun `locked session - owner key unavailable becomes a typed error`() = runTest {
        val wallet = redeemableWallet().apply { ownerKey = null }
        val result = service(wallet = wallet).redeem(position)
        assertTrue((result as RedemptionService.RedemptionResult.Error).message.contains("locked"))
    }

    @Test
    fun `change address unavailable becomes a typed error`() = runTest {
        val wallet = redeemableWallet().apply { change = null }
        assertTrue(service(wallet = wallet).redeem(position) is RedemptionService.RedemptionResult.Error)
    }

    @Test
    fun `broadcast failure - error and the wallet snapshot is NOT persisted`() = runTest {
        val persists = mutableListOf<Unit>()
        val result = service(
            wallet = redeemableWallet(),
            broadcast = { null },
            persistCalls = persists,
        ).redeem(position)

        assertTrue((result as RedemptionService.RedemptionResult.Error).message.contains("broadcast"))
        assertTrue(persists.isEmpty())
    }

    @Test
    fun `signer refusal surfaces its reason as a typed error`() = runTest {
        val result = service(
            wallet = redeemableWallet(),
            signRedemption = { _, _, _ -> error("input 1 signature does not verify under its prevout key") },
        ).redeem(position)

        assertTrue((result as RedemptionService.RedemptionResult.Error).message.contains("does not verify"))
    }

    @Test
    fun `unsynced wallet - no tip height means no redemption`() = runTest {
        val wallet = redeemableWallet().apply { tip = 0 }
        val result = service(wallet = wallet).redeem(position)
        assertTrue((result as RedemptionService.RedemptionResult.Error).message.contains("sync"))
    }

    @Test
    fun `wallet lagging the network tip - redemption blocked until caught up`() = runTest {
        val wallet = redeemableWallet().apply { estimatedTip = tip + 500 }
        val result = service(wallet = wallet).redeem(position)
        assertTrue((result as RedemptionService.RedemptionResult.Error).message.contains("sync"))
    }

    @Test
    fun `malformed native DD utxo line - typed error, not a crash`() = runTest {
        val wallet = redeemableWallet(ddLines = "deadbeef:notanumber")
        assertTrue(service(wallet = wallet).redeem(position) is RedemptionService.RedemptionResult.Error)
    }

    @Test
    fun `second redemption while one is in flight is rejected - no double-spend`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val svc = service(
            wallet = redeemableWallet(),
            signRedemption = { _, _, _ ->
                entered.countDown()
                release.await()
                SIGNED
            },
        )

        var first: RedemptionService.RedemptionResult? = null
        val worker = Thread { first = kotlinx.coroutines.runBlocking { svc.redeem(position) } }
        worker.start()
        entered.await()

        val second = kotlinx.coroutines.runBlocking { svc.redeem(position) }

        release.countDown()
        worker.join()
        assertTrue((second as RedemptionService.RedemptionResult.Error).message.contains("in progress"))
        assertTrue(first is RedemptionService.RedemptionResult.Success)
    }

    @Test
    fun `successful redemption is recorded for the activity list`() = runTest {
        val records = mutableListOf<List<Any>>()

        val result = service(
            wallet = redeemableWallet(),
            recordOutgoing = { txid, returned, fee, to -> records.add(listOf(txid, returned, fee, to)) },
        ).redeem(position)

        assertTrue(result is RedemptionService.RedemptionResult.Success)
        val (txid, returned, fee, to) = records.single()
        assertEquals(BROADCAST_TXID, txid)
        assertEquals(collateralSats, returned)
        assertEquals(MintBuilder.DEFAULT_FEE_SATS, fee)
        assertEquals("dgb1qchange", to)
    }

    @Test
    fun `failed broadcast records nothing for the activity list`() = runTest {
        val records = mutableListOf<List<Any>>()

        service(
            wallet = redeemableWallet(),
            broadcast = { null },
            recordOutgoing = { txid, returned, fee, to -> records.add(listOf(txid, returned, fee, to)) },
        ).redeem(position)

        assertTrue(records.isEmpty())
    }

    private companion object {
        val SIGNED = byteArrayOf(0x5e, 0x22, 0x33)
        val BROADCAST_TXID = "f0".repeat(32)
        const val NOW_MS = 1_730_000_100_000L
    }
}

private fun String.hexBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
