# The push-down recipe

**Proven 2026-09-03 on four pilots.** How to move a Kotlin policy object into the shared C
core so iOS gets it for free. Follow this for the remaining components in
[`full-triage.md`](full-triage.md).

## Step zero: does the constant already exist in C?

Before defining anything, grep the core headers. `PeerPenaltyPersist.kt`'s
`HEADER_BYTES = 4` duplicated `BR_PEER_PENALTY_HEADER_BYTES` (`BRPeerPenalty.h:73`), which had
been there all along — and the first draft of the new header duplicated it a *third* time as
`sizeof(uint32_t)`. The compiler caught that one with a redefinition warning; nothing catches
the Kotlin copy. **Include and use; never redefine.**

## Two kinds of push-down

Decide which you have first. Getting it wrong does harm.

**A. Pure function → move it wholesale.** A decision table or predicate with no I/O, no
locking, no async. C becomes the implementation. `CfRecoveryPolicy`, `PublishOutcome`,
`PeerPenaltyPersist`.

**B. Async orchestration → move the SPECIFICATION only.** If the Kotlin takes `suspend`
lambdas, do NOT re-express it as a C struct of function pointers. That means C calling back
into Kotlin, and a coroutine step cannot be driven from a C callback without blocking the
calling thread inside JNI — precisely the hazard `KeepaliveHealth.GIVE_UP_WEDGED` describes,
where `Job.cancel()` cannot interrupt a thread inside a JNI call and the shared dispatcher
pool starves. Swift concurrency has the same problem in a different dialect.

Instead C owns the *knowledge* as data — order, names, invariants — and each platform keeps
its own executor and asks the header what the order is. The parity test then asserts the
platform executor's observed behaviour against the C spec. `RecreateSequence`.

## The shape

1. **`digibytewallet-core/BR<Name>.h`** — header-only, `static inline`, no `BRPeerManager`, no
   locking, no I/O. Same shape as `BRPeerCFStatus.h`. Source of truth; Swift imports it, so
   iOS adds no third copy.
2. **`native/src/test/host/<name>_kat/`** — `_main.c` + `run.sh`, RED/GREEN gated.
   `scripts/run-host-kats.sh` auto-discovers by directory.
3. **`native/src/main/jni/bridge/jni_<name>.c`** — test-support accessor only. Pack results
   into a `jint` so the boundary stays scalar.
4. **One line in `native/CMakeLists.txt`** — the list is explicit, no globbing.
5. **`core/src/androidTest/java/io/digibyte/core/sync/{Name}ParityTest.kt`** — binds the
   Kotlin mirror to C. It lives in `:core`, not `:native`: the dependency runs
   `:core` → `:native`, so `:native`'s androidTest can see neither `NativeBridge` nor the
   Kotlin mirror. Run with
   `./gradlew :core:connectedMainnetDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.digibyte.core.sync.{Name}ParityTest`
   (the connected task rejects `--tests`).

## Why the Kotlin survives

`NativeBridge`'s static initializer throws `UnsatisfiedLinkError` on a host JVM (which is why
the host KATs exist), so routing production Kotlin through JNI moves its unit suite onto a
device and loses the fast gate. Therefore: **C is the source of truth; Kotlin keeps a mirror;
a parity test binds them.** Drift becomes a failing test rather than a silent difference in
wallet state. Applies only to components Kotlin tests on the host JVM — the JNI-bridge logic
in §1 of the triage has no such constraint and moves outright.

## Gotchas earned on the pilots

- **Never compile a header directly.** `clang -fsyntax-only BRFoo.h` makes every static-inline
  helper look unused and `-Wunused-function` fires under `-Werror`. Compile a TU that
  `#include`s it, include it twice to prove the guard, and link multiple TUs.
- **An assertion derived from the same declaration it tests is self-satisfying.** The first
  `BRRecreateMustPrecede` compared enum *values*, which are the declared order — it would have
  passed in the RED build. It now derives from `BRRecreateStepAt`. Same failure mode as
  deriving a parity mapping from `.ordinal`.
- **C enums are ints; Kotlin `when` is exhaustive.** Every ported table needs a defined
  `default`, and the safe default is never "do the destructive thing". No Kotlin test can reach
  that case, so the parity test must.
- **`BRInt.h` is not valid C++.** It defines anonymous unions inside cast expressions
  (`*(union _u16 { uint8_t u8[2]; } *)b2 = ...`), a GNU C extension C++ rejects, plus narrowing
  conversions in initializer lists. Any header including `BRPeerPenalty.h` inherits this.
  **Consequence for iOS:** Swift's C interop uses the C compiler, so harmless there — but an
  **Objective-C++ bridging file that includes core headers will not compile.** Keep `BRInt.h`
  out of any such file.
- **`BRInt.h` also trips `-Wmissing-braces`** under gcc. Suppress exactly that class
  (`-Wno-missing-braces`) rather than dropping `-Werror` wholesale.
- **Never hardcode platform constants; include the platform header.**
- **Do not run git inside a Claude-bridged folder.** Even read-only `git status` leaves an
  `index.lock` it cannot unlink, blocking native git.
- **Core `develop`'s tip must EQUAL the android pin.** CI's "Submodule pin is durable" step
  clones the submodule `--depth=1`, so a pin that is merely an *ancestor* of the tip fails the
  check even though it is durable. Pushing a core commit to core `develop` without bumping
  android's pin in the same push turns every android branch red (bit twice: 2026-08-31 and
  2026-09-03, the `__OBJC__` guard). Push core, then immediately commit the pin bump on
  android; never push core ahead and leave it. `./scripts/check-submodule-pin.sh` now
  requires equality too (it used to pass on a full clone in exactly this case), so run it
  locally before pushing — a "BEHIND" failure means bump the pin, not fix core.

## The four pilots

**1. `CfRecoveryPolicy` → `BRCFRecoveryPolicy.h`** (kind A). Whether a CF recovery may delete
the scan ledger — dropping it on a routine stall is the ~6-hour, 1.4M-block rescan on a Note 8.
RED gate restores "drop both on every recovery".

**2. `PublishOutcome` → `BRPublishOutcome.h`** (kind A). Kotlin hardcoded `ENOTCONN = 107`,
`ETIMEDOUT = 110` — **Linux**. Darwin is **57** and **60** (confirmed against the macOS SDK).
*No live Android bug* — Android is Linux. The hazard is an iOS copy, where a timeout matches
no case and `UNCONFIRMED_DELIVERY` becomes unreachable, destroying the only evidence a
transaction was refused. The fix is `<errno.h>` symbols, not a Darwin table. RED gate is
**conditional**: `run.sh` probes `ETIMEDOUT` and enforces only when it is not 110, printing a
skip on Linux rather than faking a pass. It fired on macOS.

**3. `RecreateSequence` → `BRRecreateSequence.h`** (kind B — spec only). The v4.0.40 ordering:
flush → reload near-tip → forceReconnect → startSync → restore ledger. C exposes
`BRRecreateStepAt`/`StepName`/`ContinuesAfterFailure`/`MustPrecede`/`IsSkippable`. No executor.
The parity test runs the **real Kotlin executor** with recording lambdas and asserts the
observed call order equals `cOrder()`, including with an injected failure at each step.

**4. `PeerPenaltyPersist` → `BRPeerPenaltyPersist.h`** (kind A). "Nothing to save" vs "can't
tell" — a NULL blob means the probe failed, and reading it as empty discards banked penalties,
the on-ramp to the 0-peer dead wedge. Includes `BRPeerPenalty.h` and uses its existing
constants. The KAT cross-checks against the **real serializer**: empty → 4 bytes, three live
entries → 82 = 4 + 3×26. RED gate restores null-is-empty.

## Verified on macOS

All four: clean under `-Wall -Wextra -Wpedantic -Werror` across c99/c11/c17; the three
libc-only headers clean under `clang++ -std=c++17`; multi-TU link; and all
**cross-compile for `iphoneos` and `iphonesimulator`**. All four RED gates fire.

## The fifth: the peer canon (done 2026-09-05)

The **peer canon** (15 mainnet CF oracle IPs + 3 testnet) was the highest-value single move:
the wallet's only reliable filter source, living in an Android-only compilation unit the
XCFramework would not contain. Deferred until one green Android build confirmed the JNI and
CMake conventions (Part C of the 2026-09-03 handoff), then moved as **`BRPeerCanon.h`**.

What the move taught, beyond the four pilots:

- **Not additive.** `jni_peer.c` lost 134 lines and gained 32; `SyncService.kt` lost its own
  copy of the testnet set (three IPs, the port, `0x41`). The accessors in `jni_peer_canon.c`
  are therefore **production**, not test-support — Kotlin reads the canon through them.
- **Function-local tables.** The IP arrays live inside `BRPeerCanonIPs()` rather than at file
  scope, so a TU that includes the header and never asks for the table carries no unused copy.
- **The port is NOT in the header.** It is `BRChainParams.h`'s `standardPort`. Including
  `BRChainParams.h` from a policy header drags `odocrypt.h` in, which does not compile under
  the KATs' `-Wall -Wextra -Werror`; restating `12024`/`12033` would be a second copy. So the
  header says nothing about ports and callers dial the canon on the active chain's port.
- **`BRPeer.h` needs `-Wno-gnu-folding-constant`** under `-std=c99` for the same `odocrypt.h`
  reason. Suppressed as exactly that class in `peer_canon_kat/run.sh`, with the rationale.
- **A resolver-free parser is the enforcement.** `BRPeerCanonParseIPv4` accepts only a
  dotted quad, so a hostname in the table fails the KAT on every machine instead of resolving
  on a developer's network and stranding a user's. The RED gate puts `digiscope.me` in slot 0
  — the pre-oracle-bootstrap shape — and the KAT must fail there.
- **One behaviour change, deliberate:** the persisted-penalty exemption used to exempt the
  MAINNET set unconditionally, so on testnet26 the three canon nodes were never exempt. It now
  exempts the active network's canon.
