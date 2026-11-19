package app.aroundtheblock.wallet.ui.onboarding

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import app.aroundtheblock.wallet.WalletSwitcher
import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.recovery.RecoveryScanService
import app.aroundtheblock.wallet.core.security.OnboardingPin
import app.aroundtheblock.wallet.core.security.PinManager
import app.aroundtheblock.wallet.core.wallets.WalletProvisioner
import app.aroundtheblock.wallet.core.wallets.WalletRegistry
import io.mockk.mockk

/**
 * The onboarding ViewModel as its first-run tests need it: the add-mode collaborators are relaxed
 * mocks, and the registry reports no wallet record (so a first-run restore clears a stale PIN, as
 * it always has). The PIN reaches the ViewModel as production hands it over: the real
 * [OnboardingPin] over [pinManager] and [registry].
 */
internal fun onboardingViewModel(
    walletManager: WalletManager,
    pinManager: PinManager,
    scanService: RecoveryScanService,
    mode: OnboardingMode = OnboardingMode.FIRST_RUN,
    registry: WalletRegistry = mockk(relaxed = true),
    provisioner: WalletProvisioner = mockk(relaxed = true),
    switcher: WalletSwitcher = mockk(relaxed = true),
    quickStartScans: QuickStartScans = mockk(relaxed = true),
    context: Context = mockk(relaxed = true),
): OnboardingViewModel = OnboardingViewModel(
    walletManager, OnboardingPin(pinManager) { registry.anyRecordExists() }, scanService,
    SavedStateHandle(mapOf("mode" to if (mode == OnboardingMode.ADD) "add" else "first_run")),
    provisioner, switcher, registry, context, quickStartScans,
)
