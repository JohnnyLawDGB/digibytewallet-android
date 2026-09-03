> ## Status — 2026-09-03 (wallet at v4.0.78)
>
> | Item | Status |
> |---|---|
> | Part A — commit + push the four core headers from the Mac | ✅ **Done.** core `develop` → `f191590`, android `develop` → `297ee49a`, `check-submodule-pin.sh` passed |
> | Part B — Linux pre-flight | ⏳ **Start here.** |
> | Part C — build, host-JVM tests, parity tests on a device | ⏳ Not started |
> | Peer-canon extraction (`jni_peer.c` → core) | ⛔ **Deliberately deferred** until Part C is green. Do not start. |
>
> Sibling docs: [`../ios-port/push-down-recipe.md`](../ios-port/push-down-recipe.md) (how each
> port was done, and the gotchas) and [`../ios-port/full-triage.md`](../ios-port/full-triage.md)
> (what remains, and why).

# Handoff — verify the four C push-downs on Linux

**For a session on the Linux Android machine.** Standalone: everything needed is here or in
the two sibling docs above.

## What happened, in five lines

Four Kotlin policy objects were ported to header-only C in the shared `digibytewallet-core`
submodule, so the iOS port can import them directly instead of reimplementing them. Each came
with a RED/GREEN-gated host KAT, a test-support JNI accessor, a `CMakeLists.txt` entry, a
`NativeBridge` declaration, and an instrumented parity test binding the Kotlin mirror to the C.
All four are verified under clang on macOS and cross-compile for `iphoneos` and
`iphonesimulator`. **None has been built for Android or run on a device. That is this
handoff's job.**

After `git pull --ff-only origin develop`, android HEAD should *contain* `297ee49a`
(HEAD itself is later — this handoff was committed on top of it) and
`git submodule status` should show `f191590`. If either check fails, stop and report.

The Mac clone was already 5 commits behind `origin/develop` when it pushed — v4.0.78 had been
cut from Linux in between — and was fast-forwarded first. The Linux clone may be out of step
in the other direction; Part B exists for exactly that.

## The 20 files

Core submodule (`native/src/main/jni/digibytewallet-core/`), new:
`BRCFRecoveryPolicy.h`, `BRPublishOutcome.h`, `BRRecreateSequence.h`, `BRPeerPenaltyPersist.h`

Android, new:
`native/src/main/jni/bridge/jni_{cf_recovery_policy,publish_outcome,recreate_sequence,peer_penalty_persist}.c`;
`native/src/test/host/{cf_recovery_policy,publish_outcome,recreate_sequence,peer_penalty_persist}_kat/`
(each `_main.c` + `run.sh`);
`native/src/androidTest/java/io/digibyte/native_core/{CfRecoveryPolicy,PublishOutcome,RecreateSequence,PeerPenaltyPersist}ParityTest.kt`

Android, modified: `native/CMakeLists.txt` (four source lines) and
`core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt` (ten `external fun`s, all
documented test-support-only). **No production Kotlin changed.**

---

## Part B — pre-flight BEFORE pulling

```bash
git status --short
git rev-parse --abbrev-ref HEAD
git fetch origin
git status -sb | head -1              # ahead/behind origin/develop?

# Does the core submodule hold commits that never reached origin/develop?
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git status --short
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git fetch origin
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git log --oneline origin/develop..HEAD
```

**Stop and report if:** uncommitted work, branch ahead of `origin/develop`, or the last
command lists any commits. Those need a human decision. Otherwise:

```bash
git pull --ff-only origin develop
git submodule update --init --recursive
git merge-base --is-ancestor 297ee49a HEAD && echo 'has 297ee49a'
git submodule status                       # expect f191590
./scripts/check-submodule-pin.sh
```

---

## Part C — verification, ordered by how much each result tells you

Report as you go, not at the end.

### C1. Host KATs — fastest, platform-neutral C

```bash
./scripts/run-host-kats.sh cf_recovery
./scripts/run-host-kats.sh publish_outcome
./scripts/run-host-kats.sh recreate_sequence
./scripts/run-host-kats.sh peer_penalty
```

All four should pass. `publish_outcome` prints `SKIP RED gate: ETIMEDOUT is 110 here` — that
is correct and deliberate on Linux; the gate fires on macOS and did.

### C2. Host-JVM unit tests — WITH NO DEVICE ATTACHED

```bash
adb devices                # confirm nothing is listed
./gradlew :core:testMainnetDebugUnitTest --tests "*CfRecoveryPolicy*"
./gradlew :core:testMainnetDebugUnitTest --tests "*PublishOutcome*"
./gradlew :core:testMainnetDebugUnitTest --tests "*RecreateSequence*"
./gradlew :core:testMainnetDebugUnitTest --tests "*PeerPenaltyPersist*"
```

**The single most important result.** The Kotlin mirrors were kept, not deleted, precisely
because these run on a host JVM with no device. If any now fails with `UnsatisfiedLinkError`
or needs a device, the premise is wrong and the mirrors should go in favour of delegating to
C. Report either way.

### C3. Android build

```bash
./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug
```

Failure modes to look for:
- CMake `Cannot find source file` → a `CMakeLists.txt` entry is wrong.
- Linker `undefined reference to Java_io_digibyte_core_bridge_NativeBridge_*` → a JNI symbol
  does not match its `NativeBridge` declaration. Convention is
  `Java_io_digibyte_core_bridge_NativeBridge_<methodName>`.
- `BRInt.h` warnings promoted to errors → the android build may be stricter than the KATs.
  `BRInt.h` is known not to be `-Wmissing-braces` clean; pre-existing.

### C4. Parity tests on a device

```bash
adb devices                                          # Note 8 or an AVD
./gradlew :native:tasks --all | grep -i connected    # find the real task name
./gradlew :native:connectedMainnetDebugAndroidTest \
  --tests "*CfRecoveryPolicyParityTest*" \
  --tests "*PublishOutcomeParityTest*" \
  --tests "*RecreateSequenceParityTest*" \
  --tests "*PeerPenaltyPersistParityTest*"
```

Task name is a guess from the flavor setup; use what `tasks --all` shows. What a failure means:
- `reasonOrdinalsMatchC` / `namesMatchKotlinFailureLabels` → enum mapping in the test is
  wrong. Test bug.
- `decisionsAgreeForEveryReason` / `outcomesAgreeForEveryKnownCode` → **Kotlin and C
  genuinely differ.** Real finding; report the exact case.
- `kotlinConstantsMatchThePlatform` → Kotlin's errno literals are not this platform's. Should
  be impossible on Linux; report the printed values.
- `kotlinExecutorFollowsTheCOrder` → `RecreateSequence.run` visits steps in a different order
  than C declares. Real finding — the six-hour bug.
- `RecreateSequenceParityTest` fails to **compile** on `runBlocking` → `:native` androidTest
  lacks `kotlinx-coroutines`. Move that one test to `core/src/androidTest/` rather than adding
  a dependency to `:native`.
- `headerBytesMatchesTheWireFormat` → `PeerPenaltyPersist.HEADER_BYTES` no longer equals
  `BR_PEER_PENALTY_HEADER_BYTES`. Real finding.

### C5. Full guardrails

```bash
./scripts/check-submodule-pin.sh
./scripts/run-host-kats.sh          # everything, to prove nothing pre-existing broke
```

---

## Part D — report

Exit codes and failure lines, not full logs:

1. Part B: was the clone clean; did the submodule have stranded commits?
2. C2: do the four host-JVM tests run with no device? Yes/no per test.
3. C3: did it build? If not, which failure mode.
4. C4: which parity tests passed; exact assertion message of any that failed.
5. Which device or AVD C4 ran on.

## Part E — do not

- Do not edit `jni_peer.c` or start the peer-canon extraction. Highest-value next move (15
  mainnet CF oracle IPs in an Android-only compilation unit) but it edits a 1,985-line shipping
  file and was deferred until one green Android build confirms the JNI and CMake conventions
  these four share. A green Part C is the signal — in a fresh session, with the triage open.
- Do not `git add -A`. Explicit paths.
- Do not run git inside a Claude-bridged folder; even `git status` leaves an `index.lock`.
