package io.digibyte.core.reconcile

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostUpgradeReconcilerTest {

    @Before
    fun setUp() {
        PostUpgradeReconciler.resetInFlightForTest()
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        PostUpgradeReconciler.resetInFlightForTest()
        unmockkStatic(Log::class)
    }

    // ---------- Pure version-gate logic ----------

    @Test
    fun `shouldRun skips when currentVersionCode is 0 (couldn't read package info)`() {
        assertFalse(PostUpgradeReconciler.shouldRun(lastReconciledVersionCode = 0, currentVersionCode = 0))
        assertFalse(PostUpgradeReconciler.shouldRun(lastReconciledVersionCode = 30034, currentVersionCode = 0))
    }

    @Test
    fun `shouldRun fires on fresh install (no stored version)`() {
        assertTrue(PostUpgradeReconciler.shouldRun(lastReconciledVersionCode = 0, currentVersionCode = 30034))
    }

    @Test
    fun `shouldRun fires on any upgrade`() {
        assertTrue(PostUpgradeReconciler.shouldRun(lastReconciledVersionCode = 30033, currentVersionCode = 30034))
        assertTrue(PostUpgradeReconciler.shouldRun(lastReconciledVersionCode = 30001, currentVersionCode = 40000))
    }

    @Test
    fun `shouldRun skips when already reconciled for this version`() {
        assertFalse(PostUpgradeReconciler.shouldRun(lastReconciledVersionCode = 30034, currentVersionCode = 30034))
    }

    @Test
    fun `shouldRun skips on downgrade (tester or sideload scenario)`() {
        assertFalse(PostUpgradeReconciler.shouldRun(lastReconciledVersionCode = 30035, currentVersionCode = 30034))
    }

    // ---------- Orchestration: runIfNeeded ----------

    @Test
    fun `runIfNeeded writes pref on Done and schedules no retry`() = runTest {
        // Real upgrade (last>0) so the reconcile actually runs — a fresh install
        // (last==0) skips it (see the dedicated fresh-install test below).
        val harness = MockHarness(storedVersion = 30033, currentVersion = 30034)
        val service = mockk<ChainReconciliationService>()
        coEvery { service.reconcile() } returns ChainReconciliationService.State.Done(
            scannedAddresses = 430,
            utxosSeenOnChain = 1,
            txsImported = 0,
            alreadyKnown = 1,
            totalChainBalanceSat = 200_000_000L,
        )

        PostUpgradeReconciler.runIfNeeded(harness.context) { service }

        // Pref written with the current version
        verify { harness.editor.putInt(PostUpgradeReconciler.KEY_LAST_VERSION, 30034) }
        verify { harness.editor.apply() }
    }

    @Test
    fun `runIfNeeded does NOT persist version on Failed (next launch will retry)`() = runTest {
        // Real upgrade (last>0) so the reconcile runs and we can observe the
        // no-persist-on-failure behavior (a fresh install would skip entirely).
        val harness = MockHarness(storedVersion = 30033, currentVersion = 30034)
        val service = mockk<ChainReconciliationService>()
        coEvery { service.reconcile() } returns ChainReconciliationService.State.Failed("network down")

        PostUpgradeReconciler.runIfNeeded(harness.context) { service }

        verify(exactly = 0) { harness.editor.putInt(any(), any()) }
        verify(exactly = 0) { harness.editor.apply() }
    }

    @Test
    fun `runIfNeeded swallows thrown exceptions without persisting`() = runTest {
        // Real upgrade (last>0) so the reconcile runs and can throw.
        val harness = MockHarness(storedVersion = 30033, currentVersion = 30034)
        val service = mockk<ChainReconciliationService>()
        coEvery { service.reconcile() } throws RuntimeException("rpc timeout")

        // Should not propagate — startup must not crash because reconcile failed
        PostUpgradeReconciler.runIfNeeded(harness.context) { service }

        verify(exactly = 0) { harness.editor.putInt(any(), any()) }
    }

    @Test
    fun `runIfNeeded on fresh install (last=0) stamps baseline and skips the reconcile`() = runTest {
        // A fresh install has no prior baseline to reconcile from (the wallet was
        // just created/restored). Running a doomed post-upgrade reconcile here set
        // the failed flag and false-fired the "Balance refresh failed" banner on
        // brand-new wallets. So: stamp the baseline, skip the reconcile entirely.
        val harness = MockHarness(storedVersion = 0, currentVersion = 30034)
        val service = mockk<ChainReconciliationService>()
        coEvery { service.reconcile() } throws AssertionError("service must not be called on a fresh install")

        PostUpgradeReconciler.runIfNeeded(harness.context) { service }

        // Baseline stamped so genuine FUTURE upgrades still reconcile.
        verify { harness.editor.putInt(PostUpgradeReconciler.KEY_LAST_VERSION, 30034) }
        verify { harness.editor.apply() }
    }

    @Test
    fun `runIfNeeded no-ops when version gate says skip`() = runTest {
        val harness = MockHarness(storedVersion = 30034, currentVersion = 30034)
        val service = mockk<ChainReconciliationService>()
        // If this throws, the service was invoked when it shouldn't have been
        coEvery { service.reconcile() } throws AssertionError("service must not be called")

        PostUpgradeReconciler.runIfNeeded(harness.context) { service }

        verify(exactly = 0) { harness.editor.putInt(any(), any()) }
    }

    @Test
    fun `runIfNeeded is reentrant-safe via inFlight guard`() = runTest {
        // First call grabs the guard; second concurrent call should early-return
        // before hitting the service factory.
        val harness = MockHarness(storedVersion = 0, currentVersion = 30034)

        // Simulate: a second invocation fires while the first is in-flight.
        // We mark inFlight manually to simulate the concurrent scenario without
        // having to orchestrate two suspending coroutines with the delay.
        // Call runIfNeeded — it should early-return because inFlight == true.
        setInFlight(true)

        var factoryInvocations = 0
        PostUpgradeReconciler.runIfNeeded(harness.context) {
            factoryInvocations++
            mockk<ChainReconciliationService>(relaxed = true)
        }

        assertEquals("factory must not be called when another run is in flight", 0, factoryInvocations)

        // Cleanup for next test
        setInFlight(false)
    }

    // ---------- helpers ----------

    private class MockHarness(storedVersion: Int, currentVersion: Int) {
        val editor: SharedPreferences.Editor = mockk(relaxed = true)
        private val prefs: SharedPreferences = mockk(relaxed = true)
        private val packageInfo = PackageInfo().apply {
            @Suppress("DEPRECATION")
            versionCode = currentVersion
        }
        private val packageManager: PackageManager = mockk(relaxed = true)
        val context: Context = mockk(relaxed = true)

        init {
            every { editor.putInt(any(), any()) } returns editor
            every { prefs.getInt(PostUpgradeReconciler.KEY_LAST_VERSION, 0) } returns storedVersion
            every { prefs.edit() } returns editor
            every { packageManager.getPackageInfo(any<String>(), 0) } returns packageInfo
            every { context.getSharedPreferences(PostUpgradeReconciler.PREFS, Context.MODE_PRIVATE) } returns prefs
            every { context.packageManager } returns packageManager
            every { context.packageName } returns "io.digibyte"
        }
    }

    /** Flip the private inFlight flag by name via reflection — cleanest way to
     *  exercise the reentrancy branch without a second concurrent launch. */
    private fun setInFlight(value: Boolean) {
        val field = PostUpgradeReconciler::class.java.getDeclaredField("inFlight")
        field.isAccessible = true
        field.setBoolean(PostUpgradeReconciler, value)
    }

    @Suppress("unused")
    private val testScopeForReadability = TestScope()
}
