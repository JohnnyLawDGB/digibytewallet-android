package app.aroundtheblock.wallet.core

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.HubSessionRevoke
import app.aroundtheblock.wallet.core.digiscope.HubTokenStore
import app.aroundtheblock.wallet.core.security.PinManager
import app.aroundtheblock.wallet.core.sync.ChainTipStore
import app.aroundtheblock.wallet.core.wallets.ActiveWallet
import app.aroundtheblock.wallet.core.wallets.WalletOrigin
import app.aroundtheblock.wallet.core.wallets.WalletRegistry
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.wallets.WalletStoreEraser
import app.aroundtheblock.wallet.core.wallets.WalletStores
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a full wipe leaves in `shared_prefs`, looked at as a directory listing, over preferences
 * that behave like the platform's ([FilePrefsFactory]): a held instance that commits after its
 * file was deleted writes the file back.
 *
 * After a duress wipe a file name that carries a wallet id says that wallet existed, so no such
 * name may be left; nor the registry or legacy's seed record file. And the wipe erases LEGACY's Hub
 * session whichever wallet is active — a new wallet made as `legacy` must not inherit it.
 */
class WipeLeavesNoWalletNamesTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() {
        ActiveWallet.resetForTest()
        ChainTipStore.invalidateCache()
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After fun tearDown() {
        unmockkStatic(Log::class)
        ActiveWallet.resetForTest()
        ChainTipStore.invalidateCache()
    }

    private inner class Device {
        val root: File = tmp.newFolder()
        val ctx: Context
        val prefs: FilePrefsFactory
        init { filePrefsContext(root).also { (c, p) -> ctx = c; prefs = p } }
        val vault = FakeVault()
        val aliases = vault.asKeystoreAliases()
        lateinit var b: WalletScope

        /** Legacy and wallet B (active), each with its record, a store and a Hub session. */
        fun populate() {
            prefs.prefsFor("dgb_wallet_seed").seed("encrypted_seed_v2", "c1")
            prefs.prefsFor(HubTokenStore.LEGACY_PREFS_NAME).seed(HubTokenStore.KEY_JWT, "legacy-jwt")
            prefs.prefsFor(HubTokenStore.SECURE_PREFS_NAME).seed(HubTokenStore.KEY_JWT, "legacy-jwt-sealed")
            vault.createUnboundKey(WalletStores.keyAliasV2(WalletScope.legacy(false)))
            val registry = ActiveWallet.registry(ctx)
            val entry = registry.reserve(false, WalletOrigin.CREATED, 1L)!!
            b = WalletScope(entry.id, false)
            prefs.prefsFor(WalletStores.seedRecordPrefs(b)).seed("encrypted_seed_v2", "c2")
            prefs.prefsFor(WalletStores.prefsName("dgb_sync_data", b)).seed("held", "x")
            prefs.prefsFor(WalletStores.prefsName(HubTokenStore.LEGACY_PREFS_NAME, b)).seed(HubTokenStore.KEY_JWT, "b-jwt")
            prefs.prefsFor(WalletStores.prefsName(HubTokenStore.SECURE_PREFS_NAME, b)).seed(HubTokenStore.KEY_JWT, "b-jwt-sealed")
            vault.createUnboundKey(WalletStores.keyAliasV2(b))
            assertTrue(registry.complete(entry.id, makeActive = true))
            ActiveWallet.resetForTest()
            assertEquals("precondition: B is the active wallet", b, ActiveWallet.scope(ctx))
        }

        fun hubStoreOf(scope: WalletScope) = HubTokenStore(ctx, scope) { name -> ctx.getSharedPreferences(name, Context.MODE_PRIVATE) }

        fun walletManager(
            identitySessions: IdentitySessionWipe = IdentitySessionWipe { true },
            hubRevoke: HubSessionRevoke = HubSessionRevoke.NONE,
            /** The Keystore the legacy eraser asks; the wipe's own seed phases keep [aliases]. */
            eraserAliases: KeystoreAliases = aliases,
        ) = WalletManager(
            context = ctx,
            keyStoreManager = keyStoreManagerOver(vault, alias = WalletStores.keyAlias(b)),
            utxoManager = mockk(relaxed = true),
            dataEraser = AndroidWalletDataEraser(ctx, eraserAliases) { hubStoreOf(WalletScope.legacy(false)) },
            quiesceNative = { },
            identitySessions = identitySessions,
            hubRevoke = hubRevoke,
            scope = b,
            scopeEraser = WalletStoreEraser(ctx, aliases, { true }) { name -> File(root, "shared_prefs/$name.xml").exists() },
            keystoreAliases = aliases,
        )

        fun leftWith(id: String) = prefs.files().filter { it.contains(id) }
    }

    @Test fun `a verified wipe leaves no prefs file naming a wallet, the registry or legacy's seed record`() = runTest {
        val d = Device().apply { populate() }
        // The running Hub client holds B's store from its start, and the wipe's identity step
        // logs it out after the core eraser has deleted B's files (IdentitySessionEraser).
        val runningHub = d.hubStoreOf(d.b).also { it.load() }
        val pin = PinManager(RecordingPinStore().apply { map["pin_hash"] = "00"; map["pin_salt"] = "00"; map["pin_method"] = "pbkdf2" })

        val report = d.walletManager(IdentitySessionWipe { runningHub.clear(); true }).wipeThenReleasePin(pin)

        assertTrue("$report", report.verified)
        assertEquals("prefs files naming wallet B", emptyList<String>(), d.leftWith(d.b.walletId))
        assertFalse("the registry file is left", "${WalletRegistry.PREFS}.xml" in d.prefs.files())
        assertFalse("legacy's seed record file is left", "dgb_wallet_seed.xml" in d.prefs.files())
    }

    /**
     * The names multi-wallet step 2 reserves (plan task A1, "Names reserved here"), as literals: no
     * build writes them yet, and the wipe erases them so a later downgrade or rollback finds none.
     */
    private val reservedNets = listOf("", "_testnet")
    private val reservedBandPrefs = reservedNets.map { "dgb_sync_data_legacy$it" }
    private val reservedBandFiles = reservedNets.flatMap { net ->
        listOf("saved_filter_headers", "saved_cf_ledger", "saved_blocks").flatMap { listOf("${it}_legacy$net.bin", "${it}_legacy$net.bin.tmp") }
    }
    private val reservedWrapAlias = "dgb_wallet_keys_wrap"

    /** Every reserved name, held: legacy's and wallet B's key records, legacy's band stores on both networks, the alias. */
    private fun Device.plantReservedNames() {
        (listOf("dgb_wallet_keys", "dgb_wallet_keys_${b.walletId}") + reservedBandPrefs).forEach { prefs.prefsFor(it).seed("held", "x") }
        reservedBandFiles.forEach { File(ctx.filesDir, it).writeBytes(byteArrayOf(1)) }
        vault.createUnboundKey(reservedWrapAlias)
    }

    /** Every file under the device's data directory whose name carries a reserved spelling. */
    private fun Device.reservedNamesLeft(): List<String> = root.walkTopDown().filter { it.isFile }
        .filter { "_legacy" in it.name || "dgb_wallet_keys" in it.name }
        .map { it.relativeTo(root).path }.sorted().toList()

    @Test fun `a verified wipe leaves no name multi-wallet step 2 reserves`() = runTest {
        val d = Device().apply { populate(); plantReservedNames() }
        assertEquals("precondition: every reserved name is planted", 2 + reservedBandPrefs.size + reservedBandFiles.size, d.reservedNamesLeft().size)
        val pin = PinManager(RecordingPinStore().apply { map["pin_hash"] = "00"; map["pin_salt"] = "00"; map["pin_method"] = "pbkdf2" })

        val report = d.walletManager().wipeThenReleasePin(pin)

        assertTrue("$report", report.verified)
        assertEquals("files or prefs files naming _legacy or dgb_wallet_keys", emptyList<String>(), d.reservedNamesLeft())
        assertFalse("the alias $reservedWrapAlias is left", reservedWrapAlias in d.vault.keys.keys)
        assertFalse("the PIN store was not cleared", pin.hasPin())
    }

    /**
     * "Deleted" is the file AND its backup: the platform restores a preferences file from
     * `<name>.xml.bak` on the next open, so a backup left behind is the store left behind.
     */
    @Test fun `a reserved prefs backup that will not go keeps the wipe unverified`() = runTest {
        for (name in reservedBandPrefs + "dgb_wallet_keys") {
            val d = Device().apply { populate() }
            // A directory that holds something cannot be deleted as a file; there is no `<name>.xml`.
            val backup = File(d.root, "shared_prefs/$name.xml.bak")
            backup.apply { mkdirs() }.resolve("held").writeBytes(byteArrayOf(1))
            val pin = PinManager(RecordingPinStore().apply { map["pin_hash"] = "00"; map["pin_salt"] = "00"; map["pin_method"] = "pbkdf2" })

            val report = d.walletManager().wipeThenReleasePin(pin)

            assertTrue("$name: precondition: the backup is still there", backup.exists())
            assertFalse("$name: precondition: only the backup is left", File(d.root, "shared_prefs/$name.xml").exists())
            assertTrue("$name: no seed step waits on it", report.seedGone)
            assertFalse("$name: its backup is left, yet the wipe verified", report.verified)
            assertTrue("$name: the PIN store must be kept", pin.hasPin())
            ActiveWallet.resetForTest()
        }
    }

    /**
     * A key record is itself a way to find its wallet: after a rollback it can be the only thing a
     * wallet left (no registry entry, no seed record, no seed key alias, no id on the owed record).
     */
    @Test fun `a key record whose wallet is known nowhere else is found and erased by the wipe`() = runTest {
        val d = Device().apply { populate() }
        val stray = "0123456789abcdef"
        val name = "dgb_wallet_keys_$stray"
        d.prefs.prefsFor(name).seed("held", "x")
        val file = File(d.root, "shared_prefs/$name.xml")
        assertTrue("precondition: the key record is on the device", file.exists())
        val pin = PinManager(RecordingPinStore().apply { map["pin_hash"] = "00"; map["pin_salt"] = "00"; map["pin_method"] = "pbkdf2" })

        val report = d.walletManager().wipeThenReleasePin(pin)

        assertFalse("a key record is on the device after the wipe", file.exists())
        assertTrue("$report", report.verified)
        assertFalse("a verified wipe clears the PIN store", pin.hasPin())
    }

    @Test fun `a key record file names its wallet, and nothing else does`() {
        assertEquals("0123456789abcdef", WalletStores.walletIdOfKeyRecordPrefsFile("dgb_wallet_keys_0123456789abcdef.xml"))
        assertEquals("0123456789abcdef", WalletStores.walletIdOfKeyRecordPrefsFile("dgb_wallet_keys_0123456789abcdef.xml.bak"))
        for (not in listOf(
            "dgb_wallet_keys.xml", "dgb_wallet_keys_legacy.xml", "dgb_wallet_keys_wrap.xml", "dgb_wallet_keys_0123.xml",
            "dgb_wallet_keys_0123456789ABCDEF.xml", "dgb_wallet_keys_0123456789abcdef", "dgb_wallet_seed_0123456789abcdef.xml",
            "xdgb_wallet_keys_0123456789abcdef.xml", "dgb_wallet_keys_0123456789abcdef_testnet.xml",
        )) assertEquals(not, null, WalletStores.walletIdOfKeyRecordPrefsFile(not))
    }

    /** A Keystore that cannot answer for the alias has not said the alias is gone. */
    @Test fun `a Keystore that cannot answer for the wrap alias keeps the wipe unverified`() = runTest {
        val d = Device().apply { populate(); plantReservedNames() }
        val cannotAnswer = object : KeystoreAliases by d.aliases {
            override fun contains(alias: String): Boolean =
                if (alias == reservedWrapAlias) throw KeystoreHiccup() else d.aliases.contains(alias)
        }
        val pin = PinManager(RecordingPinStore().apply { map["pin_hash"] = "00"; map["pin_salt"] = "00"; map["pin_method"] = "pbkdf2" })

        val report = d.walletManager(eraserAliases = cannotAnswer).wipeThenReleasePin(pin)

        assertTrue("it is not a seed key: the seed is gone", report.seedGone)
        assertFalse("the alias was never answered for, yet the wipe verified", report.verified)
        assertTrue("the PIN store must be kept", pin.hasPin())
    }

    @Test fun `a wipe of a device that holds no reserved name verifies and creates no file`() = runTest {
        // A device upgraded from 4.1.4: one wallet, legacy, and none of the reserved names.
        val d = Device()
        d.prefs.prefsFor("dgb_wallet_seed").seed("encrypted_seed_v2", "c1")
        d.prefs.prefsFor("dgb_sync_data").seed("held", "x")
        d.vault.createUnboundKey("dgb_wallet_master")
        File(d.ctx.filesDir, "saved_blocks.bin").writeBytes(byteArrayOf(1))
        fun listing() = d.root.walkTopDown().filter { it.isFile }.map { it.relativeTo(d.root).path }.toSet()
        val before = listing()
        val pin = PinManager(RecordingPinStore().apply { map["pin_hash"] = "00"; map["pin_salt"] = "00"; map["pin_method"] = "pbkdf2" })
        val wm = WalletManager(
            context = d.ctx,
            keyStoreManager = keyStoreManagerOver(d.vault),
            utxoManager = mockk(relaxed = true),
            dataEraser = AndroidWalletDataEraser(d.ctx, d.aliases) { d.hubStoreOf(WalletScope.legacy(false)) },
            quiesceNative = { },
            scopeEraser = WalletStoreEraser(d.ctx, d.aliases, { true }) { name -> File(d.root, "shared_prefs/$name.xml").exists() },
            keystoreAliases = d.aliases,
        )

        val report = wm.wipeThenReleasePin(pin)

        assertTrue("$report", report.verified)
        assertFalse("the PIN store was not cleared", pin.hasPin())
        // The wipe only deletes the reserved names: looking for one that is not there creates no file.
        // (Three of legacy's own 4.1.4 stores are written back empty by today's wipe; not this test's.)
        assertEquals(
            "files the wipe created under a reserved name",
            emptyList<String>(),
            (listing() - before).filter { "_legacy" in it || "dgb_wallet_keys" in it },
        )
        assertEquals(emptyList<String>(), d.reservedNamesLeft())
        assertEquals("aliases left", emptySet<String>(), d.vault.keys.keys.toSet())
    }

    @Test fun `the production eraser clears legacy's Hub session while another wallet is active`() {
        val d = Device().apply { populate() }
        clearMocks(d.ctx, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true, exclusionRules = false)

        // The production constructor, as WalletManager's default builds it.
        assertTrue(AndroidWalletDataEraser(d.ctx).eraseHubSession())

        assertNull("legacy's Hub token survived the wipe",
            d.prefs.prefsFor(HubTokenStore.LEGACY_PREFS_NAME).getString(HubTokenStore.KEY_JWT, null))
        // The step is legacy's: it opens none of B's stores (B's are the per-wallet eraser's). The
        // sealed store cannot be opened on a JVM, so its name is the one asserted, not its content.
        verify(exactly = 0) { d.ctx.getSharedPreferences(match { it.contains(d.b.walletId) }, any()) }
        verify(exactly = 0) { d.ctx.deleteSharedPreferences(match { it.contains(d.b.walletId) }) }
        assertEquals("b-jwt", d.prefs.prefsFor(WalletStores.prefsName(HubTokenStore.LEGACY_PREFS_NAME, d.b)).getString(HubTokenStore.KEY_JWT, null))
    }

    @Test fun `clearing stores that are not there creates no file`() {
        val d = Device()
        val legacy = WalletScope.legacy(false)
        val hub = d.hubStoreOf(legacy).also { it.load() }

        hub.clear()
        assertTrue(hub.clearConfirmed())
        val eraser = AndroidWalletDataEraser(d.ctx, d.aliases) { d.hubStoreOf(legacy) }
        assertTrue(eraser.eraseLeftoverState())
        assertTrue(eraser.eraseHubSession())
        WalletStoreEraser(d.ctx, d.aliases, { true }) { name -> File(d.root, "shared_prefs/$name.xml").exists() }
            .eraseStores(legacy)

        assertEquals("files a clear of nothing created", emptyList<String>(), d.prefs.files())
    }

    @Test fun `the wipe asks the server to end every wallet's Hub session before it erases the tokens`() = runTest {
        val d = Device().apply { populate() }
        val seen = mutableMapOf<String, String?>()
        val revoke = HubSessionRevoke { scope ->
            // What the store still holds when the revoke is asked: the token, not yet erased.
            seen[scope.walletId] = d.prefs.prefsFor(WalletStores.prefsName(HubTokenStore.LEGACY_PREFS_NAME, scope))
                .getString(HubTokenStore.KEY_JWT, null)
        }

        val report = d.walletManager(hubRevoke = revoke).wipeWallet()

        assertTrue("$report", report.verified)
        assertEquals(mapOf("legacy" to "legacy-jwt", d.b.walletId to "b-jwt"), seen)
    }

    @Test fun `a revoke that fails does not hold up the wipe`() = runTest {
        val d = Device().apply { populate() }

        val report = d.walletManager(hubRevoke = HubSessionRevoke { throw java.io.IOException("offline") }).wipeWallet()

        assertTrue("$report", report.verified)
        assertEquals(emptyList<String>(), d.leftWith(d.b.walletId))
    }
}
