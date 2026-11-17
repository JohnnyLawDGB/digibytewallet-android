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
 *    itself was always the install's (TorManager's `dgb_tor`); the Room `torEnabled` column is
 *    written by nothing now and read by nothing.
 *  - **The auto-lock timeout**, read from the active wallet's row: a wallet just added enforced the
 *    default whatever the user had chosen, and a choice made in one wallet did not hold in another.
 *    One PIN and one lock policy cover every wallet (multi-wallet design).
 *
 * The behaviour is proven in core (`TorNewInstallDefaultsTest`, `AutoLockPrefsTest`). This gate
 * holds the call sites to it, because the way this regresses is a launch step or a settings write
 * that compiles and works for the one wallet it was tried with.
 *
 * For Tor that means every write, not only a literal one. A wallet's database can decide the setting
 * through a literal (`isEnabled = false` because a row is missing) or through a value (`isEnabled =`
 * the row's dead column); a write can name its receiver or leave it to `apply` or `with`; and a read
 * of the column can route a Tor user direct without writing anything (a worker choosing its route).
 * So the gate fixes where the setting is written (the user's answer, at the two places that take it,
 * and TorManager's new-install default), where those two places are called from (the user's own
 * action on Tor: the Network Info switch and the Tor prompt's buttons), that nothing outside
 * TorManager names its file, that inside TorManager only the setting's own setter writes it, and that
 * nothing reads the column. It reads names, not types: a read by position (destructuring a row) or
 * through reflection is beyond it.
 *
 * For the auto-lock timeout it fixes what reads and writes the value, that every launch still runs
 * its one-time move out of legacy's database, and that the Security screen shows the stored value as
 * it changes, never a copy taken when the screen opened, which the move can land after.
 */
class AppWideSettingsSourceGateTest {

    /** Every app source set a build compiles: main, and each distribution's own. */
    private val appRoots = listOf("main", "play", "sideload").map { File("src/$it/java/app/aroundtheblock/wallet") }
    private val appRoot = appRoots.first()
    private val coreRoot = File("../core/src/main/java/app/aroundtheblock/wallet/core")
    private val torManager = File(coreRoot, "tor/TorManager.kt")
    private val autoLockPrefs = File(coreRoot, "settings/AutoLockPrefs.kt")

    private fun gate(file: File) = KotlinSourceGate.of(file.readText())
    private fun gate(rel: String) = gate(File(appRoot, rel))
    private fun kotlinIn(root: File): List<File> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun sources(): List<File> = appRoots.flatMap(::kotlinIn)
    private fun coreSources(): List<File> = kotlinIn(coreRoot)

    // ── Reading code: brackets, blocks and the statements that open them ───────────────────────

    /** Where the bracket [at] stands in opens: the nearest `(`, `[` or `{` before it still open there. */
    private fun openerAt(code: String, at: Int): Int? {
        var depth = 0
        for (i in at - 1 downTo 0) when (code[i]) {
            ')', ']', '}' -> depth++
            '(', '[', '{' -> if (depth == 0) return i else depth--
        }
        return null
    }

    /** The bracket that closes the one opening at [open]. */
    private fun closerOf(code: String, open: Int): Int? {
        var depth = 0
        for (i in open until code.length) when (code[i]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> if (--depth == 0) return i
        }
        return null
    }

    /**
     * The code that opens the block at [open], read back to the end of the statement before it, or
     * to the bracket the block is an argument in: a function's signature, what a lambda is handed
     * to (`… onClick =`, `lifecycleScope.launch`), a class's header. Whitespace squeezed.
     */
    private fun headOf(code: String, open: Int): String {
        var depth = 0
        var at = open - 1
        scan@ while (at >= 0) {
            when (code[at]) {
                ')', ']' -> depth++
                '(', '[' -> if (depth == 0) break@scan else depth--
                '}' -> if (depth == 0) break@scan else depth++
                '{' -> if (depth == 0) break@scan else depth--
                ';' -> if (depth == 0) break@scan
            }
            at--
        }
        return KotlinSourceGate.squeeze(code.substring(at + 1, open))
    }

    /** True when the block at [open] is a class's or an object's body, not a function's or a lambda's. */
    private fun isClassBody(code: String, open: Int): Boolean =
        Regex("""(?<![\w:.])(class|object|interface|fun)(?!\w)""").findAll(headOf(code, open)).lastOrNull()?.value.let { it != null && it != "fun" }

    /**
     * Where the value assigned by the `=` at [eq] ends: the end of its statement, read on through a
     * line that starts by continuing it (`.`, `?.`, `?:`, `&&`, `||`, `as`), so `= enabled` with
     * more on the next line is not read as `enabled`.
     */
    private fun valueEnd(code: String, eq: Int): Int {
        var depth = 0
        var at = eq + 1
        scan@ while (at < code.length) {
            when (code[at]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (--depth < 0) break@scan
                ';' -> if (depth == 0) break@scan
                '\n' -> if (depth == 0 && code.substring(eq + 1, at).isNotBlank()) {
                    val next = code.substring(at + 1, minOf(code.length, at + 200)).trimStart()
                    if (continuations.none { next.startsWith(it) }) break@scan
                }
            }
            at++
        }
        return at
    }

    private val continuations = listOf(".", "?.", "?:", "&&", "||", "as ", "as?")

    /** The string literal [at] stands in, as written: from the quote before it to the quote after it. */
    private fun literalAround(g: KotlinSourceGate, at: Int): String {
        val open = g.code.lastIndexOf('"', at)
        val close = g.code.indexOf('"', at)
        return if (open < 0 || close < 0) "" else g.written.substring(open, close + 1)
    }

    /** The line of code [at] stands on, whitespace squeezed: how a finding is named. */
    private fun lineAt(code: String, at: Int): String {
        val start = code.lastIndexOf('\n', at - 1) + 1
        val end = code.indexOf('\n', at).let { if (it < 0) code.length else it }
        return KotlinSourceGate.squeeze(code.substring(start, end))
    }

    // ── Writes of the install's Tor setting ─────────────────────────────────────────────────────

    /** One write of the install's Tor setting: the value it assigns, whole, and the block it stands in. */
    private class TorWrite(val file: File, val line: String, val value: String, val head: String?, val body: String?) {
        override fun toString() = "${file.name}: $line (assigns: $value)"
    }

    /**
     * Every write of `isEnabled` in [file], whatever its receiver: `torManager.isEnabled = …`, and
     * also `torManager.apply { isEnabled = … }` or `with(torManager) { isEnabled = … }`, which name no
     * receiver, and a reference (`torManager::isEnabled`), through which it is set where no scan
     * follows. A scan cannot know a receiver's type, so every `isEnabled` counts: app and core have
     * no other. A named argument (`f(isEnabled = …)`) and a declaration are not writes.
     */
    private fun torWritesIn(file: File): List<TorWrite> {
        val g = gate(file)
        val code = g.code
        fun write(at: Int, value: String): TorWrite {
            val block = g.enclosingBlock(at)
            return TorWrite(
                file, lineAt(code, at), value,
                head = block?.let { headOf(code, it.first) },
                body = block?.let { code.substring(it.first + 1, it.last) },
            )
        }
        val assigned = Regex("""(?<!\w)isEnabled\s*=(?!=)""").findAll(code)
            .filterNot { m -> openerAt(code, m.range.first)?.let { code[it] } == '(' }
            .filterNot { m -> Regex("""(?<!\w)va[lr]$""").containsMatchIn(code.substring(maxOf(0, m.range.first - 8), m.range.first).trimEnd()) }
            .map { m -> write(m.range.first, KotlinSourceGate.squeeze(code.substring(m.range.last + 1, valueEnd(code, m.range.last)))) }
        val referenced = Regex("""::\s*isEnabled(?!\w)""").findAll(code).map { write(it.range.first, "a reference to it") }
        return (assigned + referenced).toList()
    }

    /**
     * The user's own answer: [write] assigns `enabled`, whole, directly in the block [head] opens (a
     * function's body, or a lambda whose one parameter is `enabled`), and nothing in that block
     * declares another `enabled`, so what is written is what the user chose.
     */
    private fun isTheUsersAnswer(write: TorWrite, head: String?): Boolean {
        if (head == null || write.value != "enabled" || write.head?.endsWith(head) != true) return false
        var body = write.body ?: return false
        if (head.endsWith("=")) {
            val parameter = Regex("""^\s*enabled\s*->""").find(body) ?: return false
            body = body.substring(parameter.range.last + 1)
        }
        return shadows.none { it.containsMatchIn(body) }
    }

    /** What declares an `enabled` of its own: `val enabled`, `val (enabled, …)`, `{ enabled -> }`, `(enabled: …)`. */
    private val shadows = listOf(
        Regex("""(?<!\w)va[lr]\s+enabled(?!\w)"""),
        Regex("""(?<!\w)va[lr]\s*\([^)]*(?<!\w)enabled(?!\w)"""),
        Regex("""(?<!\w)enabled\s*(->|:(?!:))"""),
    )

    // ── Reads of the wallet database's Tor column ───────────────────────────────────────────────

    /** The plain name a `.` or `::` at [at] is applied to, or null when it is applied to anything longer. */
    private fun receiverOf(code: String, at: Int): String? =
        Regex("""(?<![\w.?!])(\w+)\s*$""").find(code.substring(maxOf(0, at - 60), at))?.groupValues?.get(1)

    /**
     * Each `torEnabled` in [code] read bare (no `.` or `::` before it, and neither a declaration nor
     * a named argument) where no local or parameter `torEnabled` is in scope. Kotlin reads a bare
     * name as a local or a parameter in scope first, and only then as a member of an implicit
     * receiver (`with(cfg) { torEnabled }`, `cfg.run { … }`). With none in scope the read is a
     * receiver's: a class's own member, or a wallet_config row's column.
     */
    private fun unscopedBareReads(code: String): List<Int> {
        val scopes = mutableListOf<IntRange>()
        val reads = mutableListOf<Int>()
        for (m in Regex("""(?<!\w)torEnabled(?!\w)""").findAll(code)) {
            val at = m.range.first
            val before = code.substring(maxOf(0, at - 40), at).trimEnd()
            val after = code.substring(m.range.last + 1, minOf(code.length, m.range.last + 40)).trimStart()
            when {
                before.endsWith(".") || before.endsWith("::") -> Unit
                Regex("""(?<!\w)va[lr]$""").containsMatchIn(before) -> localScope(code, at)?.let(scopes::add)
                after.startsWith("->") && isLambdaParameter(code, at, before) -> openerAt(code, at)?.let { open -> closerOf(code, open)?.let { scopes += open..it } }
                after.startsWith(":") && !after.startsWith("::") -> parameterScope(code, at)?.let(scopes::add)
                after.startsWith("=") && !after.startsWith("==") -> Unit
                else -> reads += at
            }
        }
        return reads.filter { read -> scopes.none { read in it } }
    }

    /** A name before `->` that a lambda declares (`{ torEnabled -> }`), not a `when` branch's condition. */
    private fun isLambdaParameter(code: String, at: Int, before: String): Boolean {
        if (!before.endsWith("{") && !before.endsWith(",")) return false
        val open = openerAt(code, at) ?: return false
        return code[open] == '{' && !Regex("""(?<!\w)when(\s*\(.*\))?$""").containsMatchIn(headOf(code, open))
    }

    /** Where a `val torEnabled` at [at] is in scope: the rest of its block, or null for a class's member. */
    private fun localScope(code: String, at: Int): IntRange? {
        val open = openerAt(code, at) ?: return null
        if (code[open] != '{' || isClassBody(code, open)) return null
        return closerOf(code, open)?.let { at..it }
    }

    /** Where a parameter `torEnabled: …` at [at] is in scope: its function's body, a block or `= expression`. */
    private fun parameterScope(code: String, at: Int): IntRange? {
        val open = openerAt(code, at) ?: return null
        if (code[open] == '{') return closerOf(code, open)?.let { open..it }
        var depth = 0
        var i = (closerOf(code, open) ?: return null) + 1
        while (i < code.length) {
            when (code[i]) {
                '(', '[' -> depth++
                ')', ']' -> if (--depth < 0) return null
                '{' -> if (depth == 0) return closerOf(code, i)?.let { i..it }
                '=' -> if (depth == 0 && code.getOrNull(i + 1) != '=' && code.getOrNull(i - 1) !in setOf('!', '<', '>', '=')) return i..valueEnd(code, i)
                ';', '}' -> if (depth == 0) return null
                '\n' -> if (depth == 0 && code.substring(i + 1, minOf(code.length, i + 200)).trimStart().firstOrNull() !in setOf('{', '=', ':')) return null
            }
            i++
        }
        return null
    }

    // ── TorManager's own writes of its file ─────────────────────────────────────────────────────

    /**
     * The Tor file's name or its setting's key spelt out: the file's anywhere in a string (a path to
     * it too: `"shared_prefs/dgb_tor.xml"`), the key's as a string of its own, which is how a key is
     * passed. A log line that mentions the key in a sentence is not a use of it.
     */
    private val spelt = Regex("""(?<!\w)dgb_tor(?!\w)|["]tor_enabled(?!\w)""")

    /**
     * Where the `edit` at [at] stands in TorManager and what it writes. The first statement of a
     * property's setter reads "<property>'s setter, first: <statement>", the statement whole and read
     * on through a line that continues it; anything else is named by its line and the code that opens
     * its block.
     */
    private fun describeEdit(g: KotlinSourceGate, at: Int): String {
        val code = g.code
        val block = g.enclosingBlock(at) ?: return "outside every block: ${lineAt(code, at)}"
        val head = headOf(code, block.first)
        val receiver = Regex("""(?<!\w)prefs\s*\.\s*$""").find(code.substring(block.first + 1, at))
            ?: return "not an edit of prefs: ${lineAt(code, at)} (in: $head)"
        val start = block.first + 1 + receiver.range.first
        val setter = if (head.endsWith("set(value)")) Regex("""(?<!\w)va[lr]\s+(\w+)""").findAll(head).lastOrNull()?.groupValues?.get(1) else null
        val first = code.substring(block.first + 1, start).isBlank()
        val statement = KotlinSourceGate.squeeze(code.substring(start, valueEnd(code, start - 1)))
        return if (setter != null && first) "$setter's setter, first: $statement" else "${lineAt(code, at)} (in: $head)"
    }

    // ── Steps a function runs ──────────────────────────────────────────────────────────────────

    /** The body `{ … }` of the function [signature] declares (it ends with the `(` of its parameters), or null when it has none. */
    private fun bodyOf(g: KotlinSourceGate, signature: String): IntRange? {
        val code = g.code
        val at = code.indexOf(signature).takeIf { it >= 0 } ?: return null
        val close = closerOf(code, at + signature.length - 1) ?: return null
        val open = code.indexOf('{', close).takeIf { it >= 0 } ?: return null
        // Between the parameters and the body only a return type may stand; `= …` is an expression body.
        if (!Regex("""\s*(:\s*[\w.<>?, ]+)?\s*""").matches(code.substring(close + 1, open))) return null
        return g.blockAt(open)
    }

    /**
     * Why the statement that starts at [start] might not run each time the function whose body is
     * [body] runs, or null when the code gives no reason: it stands in a lambda or a branch inside the
     * body rather than in the body itself; code comes before it on its line (`if (…) step`); the code
     * before it makes it the branch of an `if`, a `while`, a `for` or an `else` on the line before, or
     * the right-hand side of an operator (`=`, `&&`, `||`, `?:`, …); or the function can return before
     * it. A local or an anonymous function's `return` is that function's.
     */
    private fun whySkippable(g: KotlinSourceGate, body: IntRange, start: Int): String? {
        val code = g.code
        if (g.enclosingBlock(start) != body) return "it stands in a block inside the function, not in the function's own body"
        if (code.substring(code.lastIndexOf('\n', start - 1) + 1, start).isNotBlank()) return "code comes before it on its line: ${lineAt(code, start)}"
        val before = code.substring(body.first + 1, start).trimEnd()
        if (before.endsWith(")")) {
            val opener = openerAt(code, body.first + before.length)
            val keyword = opener?.let { Regex("""(\w+)\s*$""").find(code.substring(maxOf(0, it - 40), it))?.groupValues?.get(1) }
            if (keyword in setOf("if", "while", "for")) return "it is the branch of the `$keyword` before it: ${lineAt(code, body.first + before.length)}"
        }
        if (Regex("""(?<!\w)(else|do)$""").containsMatchIn(before)) return "it is the branch of the `else` or `do` before it"
        if (Regex("""(=|&&|\|\||\?:|->|\.|\(|\[|,)$""").containsMatchIn(before)) return "it continues the expression before it: ${lineAt(code, body.first + before.length)}"
        val returns = Regex("""(?<![\w@])return(?![\w@])""").findAll(code.substring(body.first + 1, start))
            .map { body.first + 1 + it.range.first }
            .filterNot { inNestedFunction(g, body, it) }
        return returns.firstOrNull()?.let { "the function can return before it: ${lineAt(code, it)}" }
    }

    /** True when [at] stands in a function declared inside [body]: a local function, or an anonymous one. */
    private fun inNestedFunction(g: KotlinSourceGate, body: IntRange, at: Int): Boolean {
        var block = g.enclosingBlock(at)
        while (block != null && block != body) {
            if (isFunctionBody(g.code, block.first)) return true
            block = g.enclosingBlock(block.first)
        }
        return false
    }

    /**
     * True when the block at [open] is a function's body: its head ends with a signature, `fun`, a
     * name or none, the parameters and a return type or none. A head can run back over earlier
     * statements, so a `fun` somewhere in it is not enough (`fun f() = 1` on the line before a lambda).
     */
    private fun isFunctionBody(code: String, open: Int): Boolean {
        val head = headOf(code, open)
        val fn = Regex("""(?<!\w)fun(?!\w)""").findAll(head).lastOrNull() ?: return false
        val rest = head.substring(fn.range.last + 1)
        val paren = rest.indexOf('(').takeIf { it >= 0 && Regex("""[\s\w.<>,?]*""").matches(rest.substring(0, it)) } ?: return false
        val close = closerOf(rest, paren) ?: return false
        return Regex("""\s*(:\s*[\w.<>?, ]+)?\s*""").matches(rest.substring(close + 1))
    }

    // ── The checks ─────────────────────────────────────────────────────────────────────────────

    @Test fun `the scan sees the sources at all`() {
        appRoots.forEach { assertTrue("cannot see the app source set $it", it.isDirectory) }
        assertTrue("cannot see the app sources", kotlinIn(appRoot).size > 100)
        assertTrue("cannot see the core sources", coreSources().size > 100)
        assertTrue("cannot see TorManager", torManager.isFile)
        assertTrue("cannot see AutoLockPrefs", autoLockPrefs.isFile)
        // The user's own Tor switch is a write the scan must see, or the write checks below are blind.
        assertEquals(
            "the scan no longer sees the Settings Tor switch write",
            listOf("enabled"), torWritesIn(File(appRoot, "ui/settings/SettingsViewModel.kt")).map { it.value },
        )
        // Network Info reads its own `torEnabled` bare, in scope; with no scope the read check would see it.
        val networkInfo = gate("ui/settings/NetworkInfoScreen.kt").code
        assertTrue("the scan no longer sees Network Info read its torEnabled", Regex("""!\s*torEnabled(?!\w)""").containsMatchIn(networkInfo))
        assertEquals("the scan no longer sees Network Info's torEnabled declared", emptyList<Int>(), unscopedBareReads(networkInfo))
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
        // onCreate's own body: a function placed after it, which nothing may call, is not part of it.
        val onCreate = bodyOf(main, "override fun onCreate(")
        assertTrue("MainActivity.onCreate is missing", onCreate != null)
        val defaults = main.calls("torManager.applyNewInstallDefaults", onCreate!!)
        assertEquals("onCreate applies the install's Tor defaults ${defaults.size} times; it has to be once", 1, defaults.size)
        // The defaults themselves are decided where no wallet database can be seen.
        val tor = gate(torManager).code
        val sees = listOf("WalletConfig", "WalletDatabase", "walletConfigDao").filter { tor.contains(it) }
        assertEquals("TorManager reads a wallet database", emptyList<String>(), sees)
    }

    @Test fun `the install's Tor setting is written with the user's answer alone`() {
        // App: the Settings switch and the old prompt's answer, once each, each writing its own
        // parameter whole. Anything else the app writes there it decided, and app code sees only the
        // active wallet's state to decide it from.
        val takesTheAnswer = mapOf(
            "SettingsViewModel.kt" to "fun setTorEnabled(enabled: Boolean)",
            "MainActivity.kt" to "val promptCallback: (Boolean) -> Unit =",
        )
        val writes = sources().flatMap(::torWritesIn)
        val notTheUsers = writes.filterNot { isTheUsersAnswer(it, takesTheAnswer[it.file.name]) }
        assertEquals("the app writes a Tor value the user did not choose", emptyList<String>(), notTheUsers.map { it.toString() })
        assertEquals(
            "the app's Tor writes are not the Settings switch and the prompt's answer, once each",
            takesTheAnswer.keys.sorted(), writes.map { it.file.name }.sorted(),
        )
        // Those two take the answer from the user's own action on Tor: a tap on the Network Info
        // screen's Tor row or switch, and the old Tor prompt's buttons (called in a lambda handed to
        // one, or handed to one as a reference). Called from anywhere else, a launch step or even
        // another tap (adding a wallet, say), a value the app chose is written as surely as by a
        // write of its own.
        val userActions = listOf("onClick =", "onCheckedChange =", "onDismissRequest =")
        val entries = Regex("""(?<!\w)(setTorEnabled|promptCallback)(?!\w)""")
        class Use(val file: String, val name: String, val line: String, val fromUser: Boolean)
        val uses = sources().flatMap { f ->
            val g = gate(f)
            val code = g.code
            val torPrompt = g.calls("AlertDialog").filter { "R.string.tor_title" in it.text }
            entries.findAll(code)
                .filterNot { Regex("""(?<!\w)(fun|va[lr])$""").containsMatchIn(code.substring(maxOf(0, it.range.first - 8), it.range.first).trimEnd()) }
                .map { m ->
                    val called = code.substring(m.range.last + 1, minOf(code.length, m.range.last + 40)).trimStart().startsWith("(")
                    val handedTo = if (called) {
                        g.enclosingBlock(m.range.first)?.let { headOf(code, it.first) }
                    } else {
                        Regex("""(?<![\w.])\w+\s*::\s*$""").find(code.substring(maxOf(0, m.range.first - 200), m.range.first))
                            ?.let { KotlinSourceGate.squeeze(code.substring(maxOf(0, m.range.first - 200), m.range.first).substring(0, it.range.first)) }
                    }
                    val onTap = handedTo != null && userActions.any { handedTo.endsWith(it) }
                    val onTor = when (m.value) {
                        "setTorEnabled" -> f.name == "NetworkInfoScreen.kt"
                        else -> f.name == "MainActivity.kt" && torPrompt.any { m.range.first in it.range }
                    }
                    Use(f.name, m.value, lineAt(code, m.range.first), onTap && onTor)
                }
                .toList()
        }
        assertEquals(
            "the user's Tor answer is taken from something other than their own action on Tor",
            emptyList<String>(), uses.filterNot { it.fromUser }.map { "${it.file}: ${it.line}" },
        )
        assertTrue("the scan no longer sees the Network Info switch call setTorEnabled", uses.any { it.file == "NetworkInfoScreen.kt" && it.name == "setTorEnabled" })
        assertTrue("the scan no longer sees the prompt's buttons call promptCallback", uses.any { it.file == "MainActivity.kt" && it.name == "promptCallback" })
        // Core: one write, the new-install default, which TorManager makes only while its file holds
        // no setting (TorNewInstallDefaultsTest runs it).
        val coreWrites = coreSources().flatMap(::torWritesIn)
        val outsideDefaults = coreWrites.filterNot {
            it.file.canonicalPath == torManager.canonicalPath && it.head?.endsWith("fun applyNewInstallDefaults(): Boolean") == true
        }
        assertEquals("core writes the Tor setting outside TorManager's new-install defaults", emptyList<String>(), outsideDefaults.map { it.toString() })
        assertEquals("the scan no longer sees TorManager's new-install default", 1, coreWrites.size)
        // Under the setting is TorManager's file. A write there, or a clear or a delete of it, changes
        // the setting as surely: a file with no setting in it reads as a new install, and the next
        // launch writes Tor off. Its constants are internal to core, so app code that names them does
        // not compile; this check covers core as well, and a name spelt out ([spelt]). TorManager's own
        // writes of the file are the next test's.
        val named = Regex("""(?<!\w)TorManager\s*\.\s*(Companion\s*\.\s*)?(PREFS|KEY_ENABLED)(?!\w)""")
        val elsewhere = (sources() + coreSources()).filterNot { it.canonicalPath == torManager.canonicalPath }.flatMap { f ->
            val g = gate(f)
            (named.findAll(g.code) + spelt.findAll(g.written)).map { "${f.name}: ${lineAt(g.written, it.range.first)}" }.toList()
        }
        assertEquals("the Tor setting's file is named outside TorManager", emptyList<String>(), elsewhere)
    }

    @Test fun `TorManager writes its own file only in the setters of its two settings`() {
        // The checks above stop at TorManager's door: nothing outside it names its file, and core's one
        // write of `isEnabled` is the new-install default. A second writer of the file inside TorManager
        // would pass them, and it changes the setting as surely as a write of `isEnabled`: a `remove` or
        // a `clear` leaves a file the next launch reads as a new install, whose defaults write Tor off,
        // and another `putBoolean` writes a value nobody chose. So TorManager opens the file once, as
        // its private `prefs`; `prefs` never leaves it (every use is a read of it or an edit of it);
        // the file and the setting keep the names 4.1.4 wrote them under (a new name reads as never
        // written too); and each edit is one whole statement, the first of a setter: `isEnabled`'s,
        // which every write of the setting goes through and which the checks above pin, and
        // `upgradePromptShown`'s.
        val g = gate(torManager)
        val code = g.code
        val opening = "private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)"
        assertEquals(
            "TorManager opens or deletes a preferences file other than once, as its own prefs",
            listOf(opening),
            (g.calls("getSharedPreferences") + g.calls("deleteSharedPreferences")).map { lineAt(code, it.range.first) },
        )
        assertEquals(
            "TorManager names its file other than where it declares the name 4.1.4 wrote and opens it",
            listOf(opening, "internal const val PREFS = \"dgb_tor\""),
            Regex("""(?<!\w)PREFS(?!\w)""").findAll(code).map { lineAt(g.written, it.range.first) }.toList(),
        )
        assertEquals(
            "TorManager spells its file's or its setting's name other than in their declarations, as 4.1.4 wrote them",
            listOf("internal const val PREFS = \"dgb_tor\"", "internal const val KEY_ENABLED = \"tor_enabled\""),
            spelt.findAll(g.written).map { lineAt(g.written, it.range.first) }.toList(),
        )
        val handedOn = Regex("""(?<!\w)prefs(?!\w)""").findAll(code)
            .filterNot { m -> Regex("""^\s*\.\s*(get\w*|contains|edit)\s*\(""").containsMatchIn(code.substring(m.range.last + 1, minOf(code.length, m.range.last + 40))) }
            .map { lineAt(code, it.range.first) }.toList()
        assertEquals("TorManager's prefs is used other than by a read or an edit of its own: it can be edited where no check looks", listOf(opening), handedOn)
        assertEquals(
            "TorManager edits its file other than once in each setter",
            listOf(
                "isEnabled's setter, first: prefs.edit().putBoolean(KEY_ENABLED, value).apply()",
                "upgradePromptShown's setter, first: prefs.edit().putBoolean(KEY_UPGRADE_PROMPT_SHOWN, value).apply()",
            ),
            Regex("""(?<!\w)edit(?!\w)""").findAll(code).map { describeEdit(g, it.range.first) }.toList(),
        )
    }

    @Test fun `nothing reads the wallet database's Tor column`() {
        // Nothing writes the column any more, so it holds nothing to read: a wallet just added has no
        // row, and an older row holds whatever was last written there. Read to set Tor, or to route a
        // connection, it sends a Tor user direct again, and a route needs no write the checks above see.
        val member = Regex("""(\.|::)\s*torEnabled(?!\w)""")
        // The one `torEnabled` member the app may read is the Settings ViewModel's flow of
        // TorManager's setting, through a name its file declares as a SettingsViewModel.
        fun memberReads(files: List<File>) = files.flatMap { f ->
            val code = gate(f).code
            val viewModels = Regex("""(?<!\w)(\w+)\s*:\s*SettingsViewModel(?!\w)""").findAll(code).map { it.groupValues[1] }.toSet()
            member.findAll(code).map { m -> Triple(f.name, receiverOf(code, m.range.first)?.let { it in viewModels } == true, lineAt(code, m.range.first)) }.toList()
        }
        val app = memberReads(sources())
        assertEquals("an app source reads the wallet database's Tor column", emptyList<String>(), app.filterNot { it.second }.map { "${it.first}: ${it.third}" })
        assertTrue("the scan no longer sees Network Info read the Settings ViewModel's Tor flow", app.any { it.second && it.first == "NetworkInfoScreen.kt" })
        assertEquals("a core source reads the wallet database's Tor column", emptyList<String>(), memberReads(coreSources()).map { "${it.first}: ${it.third}" })
        // A read that names no receiver, where no local or parameter of that name is in scope.
        val unscoped = (sources() + coreSources()).flatMap { f ->
            val code = gate(f).code
            unscopedBareReads(code).map { "${f.name}: ${lineAt(code, it)}" }
        }
        assertEquals("a source reads a torEnabled it does not declare: a receiver's, such as a wallet_config row's", emptyList<String>(), unscoped)
        // And no query names the column (a string that names it and its table), except the migration
        // that added it.
        val sql = (sources() + coreSources()).filterNot { it.name.startsWith("Migration") }.flatMap { f ->
            val g = gate(f)
            Regex("""(?<!\w)torEnabled(?!\w)""").findAll(g.written)
                .filter { g.code.substring(it.range).isBlank() }
                .filter { m -> literalAround(g, m.range.first).contains("wallet_config") }
                .map { "${f.name}: ${lineAt(g.written, it.range.first)}" }.toList()
        }
        assertEquals("a query names the wallet database's Tor column", emptyList<String>(), sql)
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
        // The one-time move out of legacy's database. Only legacy's own mainnet database from disk
        // holds the 4.1.4 value, and "no row" in another wallet's, testnet's or a temporary database
        // would finish the move with nothing. Which database may answer is decided in core, by
        // AutoLockPrefs.readLegacy, which AutoLockPrefsTest runs; the launch hands it the facts about
        // the database its process has open, and its block is that call and nothing else. Each fact
        // has a way to lose the choice: the wallet scope (an added wallet's empty database answers),
        // the name (null is how the temporary database is told apart), and the row (another database's).
        val move = main.calls("AutoLockPrefs.migrateFromLegacy")
        assertEquals("MainActivity runs the one-time move ${move.size} times; it has to be once", 1, move.size)
        val inMove = main.trailingBlock(move.single())
        assertTrue("the one-time move has no block to read legacy's database in", inMove != null)
        val decide = main.calls("AutoLockPrefs.readLegacy")
        assertEquals("MainActivity asks core which database answers the move ${decide.size} times; it has to be once", 1, decide.size)
        assertEquals(
            "the move is not handed the wallet this process runs, its database's name and that database's own row",
            listOf("ActiveWallet.scope(applicationContext)", "walletDatabase.openHelper.databaseName", "walletDatabase.walletConfigDao()"),
            decide.single().arguments,
        )
        assertEquals(
            "the one-time move's block is not core's answer alone",
            decide.single().text, main.code.substring(inMove!!.first + 1, inMove.last).trim(),
        )
        // And every launch runs it. A private function nothing calls still compiles, so counting the
        // move's call sites cannot see a launch that stopped running it, and every 4.1.4 user's chosen
        // timeout would become the default in every wallet. So onCreate runs it: one statement of its
        // own body, which nothing before it can skip, starting a coroutine that runs the move and
        // nothing else; the function holds the move alone; and nothing else calls it.
        val onCreate = bodyOf(main, "override fun onCreate(")
        assertTrue("MainActivity.onCreate is missing", onCreate != null)
        val runs = main.calls("moveLegacyAutoLockTimeout")
        assertEquals("MainActivity calls moveLegacyAutoLockTimeout ${runs.size} times; it has to be once, from onCreate", 1, runs.size)
        val coroutine = main.enclosingBlock(runs.single().range.first)
        val launch = main.calls("lifecycleScope.launch", onCreate!!).singleOrNull { coroutine != null && main.trailingBlock(it) == coroutine }
        assertTrue("onCreate does not start the one-time move in a coroutine of its own", launch != null)
        assertEquals(
            "onCreate's step for the one-time move is not a statement of its own",
            "lifecycleScope.launch(Dispatchers.IO) { moveLegacyAutoLockTimeout() }",
            KotlinSourceGate.squeeze(main.code.substring(launch!!.range.first, valueEnd(main.code, launch.range.first - 1))),
        )
        assertEquals("onCreate can skip the one-time move", null, whySkippable(main, onCreate, launch.range.first))
        assertEquals(
            "moveLegacyAutoLockTimeout does something other than the one-time move",
            main.code.substring(move.single().range.first, inMove.last + 1),
            bodyOf(main, "fun moveLegacyAutoLockTimeout(")?.let { main.code.substring(it.first + 1, it.last).trim() },
        )
        // The wallet database's column is read once, by that decision: nowhere in the app, and in core
        // only inside readLegacy. The entity's own declaration of the column is not a read.
        val column = Regex("""(?<!\w)autoLockTimeoutMs(?!\w)""")
        fun readsIn(files: List<File>) = files.flatMap { f ->
            val code = gate(f).code
            column.findAll(code).map { it.range.first }.filterNot { code.substring(0, it).trimEnd().endsWith("val") }.map { f to it }.toList()
        }
        assertEquals("an app source reads the wallet database's auto-lock column", emptyList<String>(), readsIn(sources()).map { (f, at) -> "${f.name}@$at" })
        val decision = gate(autoLockPrefs).range("suspend fun readLegacy(", "\n    }")
        assertTrue("AutoLockPrefs.readLegacy is missing", decision != null)
        val coreReads = readsIn(coreSources())
        val outsideDecision = coreReads.filterNot { (f, at) -> f.canonicalPath == autoLockPrefs.canonicalPath && at in decision!! }
        assertEquals("the wallet database's auto-lock column is read outside the one-time move", emptyList<String>(), outsideDecision.map { (f, at) -> "${f.name}@$at" })
        assertEquals("the one-time move does not read legacy's column", 1, coreReads.size)
    }

    @Test fun `the Security screen shows the stored timeout as it is now, not a copy taken when it opened`() {
        // The launch's one-time move runs off the main thread and can land after Settings → Security
        // has opened. A copy of the stored timeout taken as the screen opened then shows the 60 s
        // default while the inactivity lock enforces the moved value, until the screen is built
        // again. So the ViewModel keeps no copy. Its auto-lock value is AutoLockPrefs.timeoutFlow,
        // which hears every write of the stored value (AutoLockPrefsTest runs it), through the one
        // default, collected from the ViewModel's start. The stored value is read directly once, as
        // that flow's value until its own first answer: no MutableStateFlow, nor anything else, is
        // handed a copy of it. And the setter only writes; the flow then shows what landed.
        val vm = gate("ui/settings/SettingsViewModel.kt")
        val code = vm.code
        val declared = Regex("""(?<!\w)val\s+autoLockTimeout\s*:\s*StateFlow\s*<\s*Long\s*>\s*=(?!=)""").findAll(code).toList()
        assertEquals("SettingsViewModel declares autoLockTimeout ${declared.size} times; it has to be once", 1, declared.size)
        val eq = declared.single().range.last
        val derivation = (eq + 1) until valueEnd(code, eq)
        val derived = KotlinSourceGate.squeeze(code.substring(derivation)).replace(" .", ".")
        val set = vm.range("fun setAutoLockTimeout(", "\n    }")
        assertTrue("setAutoLockTimeout is missing", set != null)
        val findings = buildList {
            val fromItsFlow = "AutoLockPrefs.timeoutFlow(context).map(::autoLockTimeoutOrDefault)" +
                ".stateIn(viewModelScope, SharingStarted.Eagerly, autoLockTimeoutOrDefault(AutoLockPrefs.storedTimeoutMs(context)))"
            if (derived != fromItsFlow) add("autoLockTimeout is not the stored value's flow from the ViewModel's start: $derived")
            Regex("""(?<!\w)storedTimeoutMs(?!\w)""").findAll(code).filterNot { it.range.first in derivation }
                .forEach { add("a copy of the stored timeout, read outside its flow: ${lineAt(code, it.range.first)}") }
            Regex("""\.\s*value\s*=(?!=)|(?<!\w)(emit|tryEmit|update|updateAndGet|getAndUpdate|compareAndSet)\s*[({]""")
                .findAll(code.substring(set!!))
                .forEach { add("setAutoLockTimeout sets the screen's value itself: ${lineAt(code, set.first + it.range.first)}") }
        }
        assertEquals("the Security screen can show a timeout the inactivity lock does not enforce", emptyList<String>(), findings)
    }
}
