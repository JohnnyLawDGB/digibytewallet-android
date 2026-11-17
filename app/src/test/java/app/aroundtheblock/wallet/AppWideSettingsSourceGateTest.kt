package app.aroundtheblock.wallet

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * App-wide settings are never decided by, or written into, a wallet's database.
 *
 * Since multi-wallet step 1 every wallet has its own Room database, so the `wallet_config` row the
 * app reads is the ACTIVE wallet's. Two app-wide settings were still decided there:
 *
 *  - **Tor.** The launch read "this database has no config row" as a new install and wrote the Tor
 *    setting off. A wallet just added has no row, and adding one restarts into it, so its first
 *    launch turned a Tor user's setting off and every connection after it went direct. The setting
 *    itself was always the install's (TorManager's `dgb_tor`); the Room `torEnabled` column was
 *    written and read by nothing.
 *  - **The auto-lock timeout**, read from the active wallet's row: a wallet just added enforced the
 *    default whatever the user had chosen, and a choice made in one wallet did not hold in another.
 *    One PIN and one lock policy cover every wallet (multi-wallet design).
 *
 * The behaviour is proven in core (`TorNewInstallDefaultsTest`, `AutoLockPrefsTest`). This gate
 * holds the app's call sites to it, because the way this regresses is a launch step or a settings
 * write that compiles and works for the one wallet it was tried with.
 */
class AppWideSettingsSourceGateTest {

    private val appRoot = File("src/main/java/app/aroundtheblock/wallet")
    private val torManager = File("../core/src/main/java/app/aroundtheblock/wallet/core/tor/TorManager.kt")

    private fun gate(file: File) = KotlinSourceGate.of(file.readText())
    private fun gate(rel: String) = gate(File(appRoot, rel))
    private fun sources(): List<File> = appRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun `the scan sees the sources at all`() {
        assertTrue("cannot see the app sources", sources().size > 100)
        assertTrue("cannot see TorManager", torManager.isFile)
        // The user's own Tor switch is a write the scan must see, or the literal-write check below is blind.
        assertEquals(
            "the scan no longer sees the Settings Tor switch write",
            1, Regex("""torManager\s*\.\s*isEnabled\s*=\s*enabled(?!\w)""").findAll(gate("ui/settings/SettingsViewModel.kt").code).count(),
        )
        // The per-wallet "prompt shown" mark is still written by name: the named-argument check below reads these calls.
        assertTrue(
            "the scan cannot read the named arguments of a wallet_config copy",
            gate("MainActivity.kt").calls("copy").any { "torPromptShown" in it.named.keys },
        )
    }

    @Test fun `no app source writes Tor or the auto-lock timeout into a wallet database`() {
        val written = setOf("torEnabled", "autoLockTimeoutMs")
        val offenders = sources().flatMap { f ->
            val g = gate(f)
            (g.calls("copy") + g.calls("WalletConfigEntity"))
                .filter { call -> call.named.keys.any { it in written } }
                .map { "${f.name}: ${it.text}" }
        }
        assertEquals("app-wide settings written into the active wallet's database", emptyList<String>(), offenders)
    }

    @Test fun `a launch applies the install's Tor defaults, never a wallet database's`() {
        val main = gate("MainActivity.kt")
        val onCreate = main.range("override fun onCreate(", "override fun ")
        assertTrue("MainActivity.onCreate is missing", onCreate != null)
        val defaults = main.calls("torManager.applyNewInstallDefaults", onCreate!!)
        assertEquals("onCreate applies the install's Tor defaults ${defaults.size} times; it has to be once", 1, defaults.size)
        // The only Tor writes left in the app are the user's own choice (the Settings switch, the old
        // prompt's answer). A literal written there is a default the app decided, and app code sees
        // only the active wallet's state to decide it from.
        val literal = Regex("""torManager\s*\.\s*isEnabled\s*=\s*(true|false)(?!\w)""")
        val decided = sources().flatMap { f -> literal.findAll(gate(f).code).map { "${f.name}: ${it.value}" }.toList() }
        assertEquals("the app writes a Tor value the user did not choose", emptyList<String>(), decided)
        // The defaults themselves are decided where no wallet database can be seen.
        val tor = gate(torManager).code
        val sees = listOf("WalletConfig", "WalletDatabase", "walletConfigDao").filter { tor.contains(it) }
        assertEquals("TorManager reads a wallet database", emptyList<String>(), sees)
    }

    @Test fun `the auto-lock timeout is read and written as one app-wide value`() {
        val main = gate("MainActivity.kt")
        val vm = gate("ui/settings/SettingsViewModel.kt")
        // The inactivity lock and the Security screen read the app-wide value; the screen writes it.
        val mainReads = main.calls("autoLockTimeoutOrDefault").map { it.arguments.single() }
        assertEquals("the inactivity lock does not read the app-wide timeout", listOf("AutoLockPrefs.storedTimeoutMs(applicationContext)"), mainReads)
        val vmReads = vm.calls("autoLockTimeoutOrDefault").map { it.arguments.single() }
        assertTrue("the Security screen does not read the app-wide timeout", vmReads.isNotEmpty() && vmReads.all { it == "AutoLockPrefs.storedTimeoutMs(context)" })
        val set = vm.range("fun setAutoLockTimeout(", "\n    }")
        assertTrue("setAutoLockTimeout is missing", set != null)
        assertEquals("the Security screen does not store the app-wide timeout", 1, vm.calls("AutoLockPrefs.setTimeoutMs", set!!).size)
        assertTrue("the Security screen still writes the timeout into the wallet database", vm.calls("walletConfigDao.upsert", set).isEmpty())
        // The wallet database's column is read once, by the one-time move out of legacy's database.
        val move = main.calls("AutoLockPrefs.migrateFromLegacy")
        assertEquals("MainActivity runs the one-time move ${move.size} times; it has to be once", 1, move.size)
        val inMove = main.trailingBlock(move.single())
        assertTrue("the one-time move has no block to read legacy's database in", inMove != null)
        // Only legacy's own mainnet database from disk holds the 4.1.4 value: "no row" in another
        // wallet's, testnet's or a temporary database would finish the move with nothing.
        assertEquals("the move does not ask whether the open database is legacy's own", 1, main.calls("AutoLockPrefs.holdsLegacyTimeout", inMove!!).size)
        assertTrue("the move cannot tell a temporary database from legacy's on disk", main.code.substring(inMove).contains("walletDatabase.openHelper.databaseName"))
        val column = Regex("""(?<!\w)autoLockTimeoutMs(?!\w)""")
        val reads = sources().flatMap { f -> column.findAll(gate(f).code).map { f.name to it.range.first }.toList() }
        val outsideMove = reads.filterNot { (name, at) -> name == "MainActivity.kt" && at in inMove }
        assertEquals("the wallet database's auto-lock column is read outside the one-time move", emptyList<Pair<String, Int>>(), outsideMove)
        assertEquals("the one-time move does not read legacy's column", 1, reads.size)
    }
}
