/*
 * jni_cf_recovery_policy.c
 *
 * JNI accessor for BRCFRecoveryPolicy.h -- the compact-filter recovery decision
 * table (which artifacts a recovery is allowed to destroy).
 *
 * This exists ONLY so an instrumented parity test can compare the C table
 * against Kotlin's CfRecoveryPolicy. Production Kotlin deliberately does NOT
 * route through here.
 *
 * Why the duplicate is tolerated rather than eliminated: CfRecoveryPolicy.kt is
 * covered by a plain host-JVM unit test (CfRecoveryPolicyTest), and NativeBridge's
 * static initializer throws UnsatisfiedLinkError on a host JVM -- so making the
 * Kotlin delegate to C would move that suite onto a device and lose the fast
 * host-JVM gate. The C header is the SOURCE OF TRUTH (iOS imports it directly,
 * so Swift adds no third copy); the Kotlin is a mirror; CfRecoveryPolicyParityTest
 * is what stops them drifting. A divergence becomes a failing test rather than a
 * silent difference in where two platforms resume a scan.
 *
 * Returns a bitmask rather than a struct so the boundary stays a jint:
 *   bit 0 (1) = dropFilterChain
 *   bit 1 (2) = dropScanLedger
 * Touches no wallet state, takes no lock, and is safe to call before a wallet
 * exists -- it is a pure function of its argument.
 */

#include "jni_bridge.h"
#include "BRCFRecoveryPolicy.h"

#define CF_RECOVERY_BIT_DROP_FILTER_CHAIN 1
#define CF_RECOVERY_BIT_DROP_SCAN_LEDGER  2

JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_cfRecoveryDecide(JNIEnv *env, jobject thiz,
                                                            jint reason) {
    (void)env;
    (void)thiz;

    /* Deliberately NOT range-checked here. The header's default case is itself
     * under test (an unknown reason must keep the scan ledger), and clamping or
     * rejecting out-of-range input in the bridge would hide exactly the behavior
     * the parity test needs to observe. */
    BRCFRecoveryDecision d = BRCFRecoveryDecide((BRCFRecoveryReason)reason);

    return (d.dropFilterChain ? CF_RECOVERY_BIT_DROP_FILTER_CHAIN : 0)
         | (d.dropScanLedger  ? CF_RECOVERY_BIT_DROP_SCAN_LEDGER  : 0);
}
