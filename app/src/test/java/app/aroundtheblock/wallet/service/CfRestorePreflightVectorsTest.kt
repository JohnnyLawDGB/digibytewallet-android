package app.aroundtheblock.wallet.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds [cfRestoreResetReason] and [compactFilterBirthHeight] to the C core's
 * `BRCFRestorePreflight.h`, the source of truth iOS calls.
 *
 * The Kotlin is the code Android runs, and it is internal to this module, so the device-side
 * parity pattern (a NativeBridge accessor and an androidTest) cannot reach it. Instead the
 * header's answers are written down in the core submodule (`vectors/cf-restore-preflight/`,
 * generated from the header; the host KAT `cf_restore_preflight_kat` checks the file is current
 * and agrees with its model of this Kotlin). A change to the rule here that the header does not
 * make fails on the host JVM, in every CI run. Change both, and regenerate the vectors.
 */
class CfRestorePreflightVectorsTest {

    private val vectors: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "native/src/main/jni/digibytewallet-core/vectors/cf-restore-preflight/vectors.txt")
            if (f.isFile) return@lazy f
            dir = dir.parentFile
        }
        error("vectors/cf-restore-preflight/vectors.txt not found above ${System.getProperty("user.dir")}: is the core submodule checked out?")
    }

    private fun hex(h: String): ByteArray =
        if (h == "empty") ByteArray(0) else ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun everyResetIsTheHeaders() {
        val ledgers = HashMap<String, ByteArray>()
        val windows = HashMap<String, ByteArray>()
        var resets = 0
        val wrong = mutableListOf<String>()
        vectors.forEachLine { line ->
            val f = line.split(' ')
            when {
                line.isBlank() || line.startsWith("#") -> Unit
                f[0] == "blob" && f[1].startsWith("L") -> ledgers.put(f[1], hex(f[2]))
                f[0] == "blob" && f[1].startsWith("W") -> windows.put(f[1], hex(f[2]))
                f[0] == "reset" -> {
                    resets++
                    val got = cfRestoreResetReason(
                        if (f[1] == "-") null else ledgers.getValue(f[1]),
                        if (f[2] == "-") null else windows.getValue(f[2]),
                        wasSynced = f[3] == "1",
                        hasTransactionCheckpoint = f[4] == "1",
                    )?.name ?: "RESUME"
                    if (got != f[5]) wrong += "$line (Kotlin: $got)"
                }
            }
        }
        assertTrue("only $resets reset vectors were read", resets > 2000)
        assertEquals("CfRestorePreflight.kt is not BRCFRestorePreflight.h on ${wrong.size} vectors, first: ${wrong.take(5)}",
            0, wrong.size)
    }

    @Test
    fun everyArmingHeightIsTheHeaders() {
        var arms = 0
        vectors.forEachLine { line ->
            val f = line.split(' ')
            if (f[0] != "arm") return@forEachLine
            arms++
            assertEquals(line, f[5].toLong(), compactFilterBirthHeight(
                wasSynced = f[1] == "1",
                savedTip = f[2].toLong(),
                walletBirth = f[3].toLong(),
                persistedBirth = if (f[4] == "-") null else f[4].toLong(),
            ))
        }
        assertTrue("only $arms arming vectors were read", arms > 100)
    }
}
