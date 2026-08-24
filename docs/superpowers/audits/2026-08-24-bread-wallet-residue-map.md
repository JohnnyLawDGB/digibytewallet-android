# Bread-wallet residue: what's actually left

**24 August 2026** · inventory before cutting, not a plan to cut everything

> **STATUS: Tiers 0 and 1 are DONE** (except locale directories, held for a
> deliberate decision — see step 3). 1,903 lines of uncompiled C and 143
> resource files removed; exported symbol list proven byte-identical before and
> after; verified running on a Note 8. Tier 2 deliberately untouched.

## The headline, which is not what I expected

**Almost none of the bread-wallet residue is reachable code.** The native legacy
bridge is already out of the build, and the Kotlin side has no legacy packages at
all. What remains is overwhelmingly dead *weight* and dead *source* — real
maintenance burden, but not attack surface.

That distinction matters, because the stated goal is reducing attack surface. It
would be easy to delete 4.9 MB of resources, feel productive, and not have moved
the security needle at all. Resources are not code.

---

## Tier 0 — dead source, NOT compiled, zero runtime reachability

`native/src/main/jni/bridge/` still holds three legacy files that
`CMakeLists.txt` **already comments out**:

| file | lines |
|---|---|
| `PeerManager.c` | 534 |
| `wallet.c` | 1110 |
| `core.c` | 259 |

Confirmed against the shipped library, not just the build file: the arm64
`libcore-lib.so` exports **113** `Java_io_digibyte_core_bridge_NativeBridge_*`
symbols, one test hook, and **zero** `Java_io_digibyte_wallet_*` or
`Java_io_digibyte_presenter_*`.

These are why I spent time today chasing `FindClass("io/digibyte/wallet/BRPeerManager")`
and `GetFieldID(..., "pkiType", ...)` as if they were live JNI seams needing R8
keep rules. **They were never in the binary.** That is the actual cost of leaving
them: not risk, but everyone who reads the JNI layer — human or otherwise —
concluding the app has surfaces it does not have.

**Risk to delete: none.** Not compiled, not linked, not exported.

## Tier 1 — dead resources, shipped but inert

Measured by reference, not by name:

| kind | present | unreferenced |
|---|---|---|
| drawables (all densities) | 87 | **82** |
| `anim` | 11 | 10 |
| `animator` | 3 | 3 |
| `raw` | 3 | 3 |
| strings (`values/strings.xml`) | 16 | 15 |
| locale directories | **60** | translations of mostly-unused strings |

Total `res/` is **4.9 MB**. Names are unambiguous about origin: `bread_gradient`,
`bread_toggle`, `bread_dialog_rounded`, `b_blue`, `cad_bg`.

**33 orphaned layouts were already removed today** — they were blocking R8
entirely, because aapt generated malformed keep rules from data-binding lambdas
for a binding feature this app never enabled.

**Risk to delete: low, but not zero.** A resource can be reached by
`getIdentifier()` at runtime, which no grep will find. Worth one search for
dynamic lookups before bulk removal.

**Value: weight, not security.** And note `isShrinkResources = true` on
`minifiedDebug` already strips these from that APK — the 89.5 MB → 78.5 MB drop
is mostly this. Once release enables shrinking, the size win arrives without
deleting anything. Deleting is for the humans reading the repo.

## Tier 2 — compiled and live

`bridge/` files that ARE in the build total ~5,700 lines, dominated by
`jni_peer.c` (1,985) and `jni_wallet.c` (1,011). These are the modern bridge and
are genuinely reachable.

**This is where attack surface actually lives**, and none of it is inventoried
here as "legacy" — it is current code. Any reduction is a real refactor with real
risk, not an excision.

## Tier 3 — clean already

`app/` and `core/` contain only `io/digibyte/{core,di,service,ui,util}`. No
`presenter`, no `io.digibyte.wallet`, no `tools`, no `adapter`. The Kotlin rewrite
left nothing behind.

---

## Suggested order, if we do this

1. **Delete the three uncompiled C files.** Zero risk, removes the ghost seams
   that already cost a session's worth of chasing.
2. **Grep for `getIdentifier` / dynamic resource lookup**, then bulk-remove the
   orphaned drawables/anim/animator/raw. Verify by building and diffing the APK
   resource table, not by eye.
3. **Decide about the 60 locale directories deliberately.** They are dead weight
   today, but they are also the only translation work anyone has ever done on
   this app. Deleting them is easy; recreating them is not. Consider whether
   localisation is on the roadmap before treating them as debris.
4. **Leave Tier 2 alone** unless a specific reduction is identified. "It looks
   old" is not a reason to touch code that moves money.

## What this does NOT buy

Reducing attack surface against an attacker with the APK. That attacker gets the
compiled artifact, where none of Tier 0 exists and Tier 1 is inert data. The
measures that actually change their cost are the ones already in flight: R8
obfuscation (proven, awaiting a release flip) and the stripped native library
(already shipping, 260 dynamic symbols).
