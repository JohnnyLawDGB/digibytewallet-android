# Releasing DigiByte Mobile

The one written procedure for shipping a release. Before 2026-10-11 there was none: the steps
lived in a private evidence file, in session memory and in people's heads, and that is how a
package move turned into lost fixes, a stranded gate and a release machine nobody could find.
If this file and anything else disagree, this file wins — then fix the other one.

---

## 0. Read first: which branch builds which product

| Product | Package | Status |
|---|---|---|
| **DigiByte Mobile** | `app.aroundtheblock.wallet` | **Ships.** Play (open testing) and sideload. |
| DigiByte Wallet | `io.digibyte` | **Retired at v4.0.87.2 (2026-10-10).** Receives nothing, not even security fixes. |

> **`develop` still builds `io.digibyte`** (`applicationId = "io.digibyte"`, checked
> 2026-10-11). The product that ships exists only on `release/digibyte-mobile-<version>`
> branches, and so does everything needed to release it:
> `scripts/build-release.sh`, `docs/SIGNING.md`, `scripts/create-signing-key.sh`,
> `scripts/check-no-keystore-tracked.sh`, `scripts/gen-jni-registry.py`.
>
> Consequence: **a PR merged to `develop` does not reach the product.** It reaches it only
> when `develop` and the DigiByte Mobile line are merged. Until that merge happens it is
> **step 0 of every release** — see §2.

Retired automation still on `develop`, which must not be trusted to do anything:

- `.github/workflows/release.yml` — the io.digibyte release workflow. Its tag trigger is
  removed and its first step refuses any ref that is not `v4.0.87` / `v4.0.87.x`.
- `release-please` — triggers only on the retired `phase1-modernization` branch. That is why
  `CHANGELOG.md` stops at 3.3.0. Release notes are written by hand (§7).

**Four repositories, four jobs.** Keep them straight:

| Repository | Holds |
|---|---|
| `JohnnyLawDGB/digibytewallet-android` | app source (this repo) |
| `JohnnyLawDGB/digibytewallet-core` | the C core, a submodule at `native/src/main/jni/digibytewallet-core` |
| `JohnnyLawDGB/aroundtheblock-wallet-releases` | sideload APKs and checksums — **releases only, no source** |
| `~/dgb-wallet-private` (local, never pushed) | finding detail, triage, the bounty ledger, **the release scope list** |

---

## 1. Scope: one list, private, by ID

The scope of release `X.Y.Z` is the file
`~/dgb-wallet-private/audit/bounty/RELEASE-X.Y.Z-SCOPE.md`, and nothing else. Not a wave
index, not the payout ledger, not memory, not a PR description. When 4.0.90 was planned its
items were found spread over five places, two of which had already drifted apart.

- One row per item: **ID**, the **invariant** being established, the source, the award.
- Mechanism and attack detail stay in that private repo. Here, in commits, KAT names and PR
  text, a finding is named by ID and described by the invariant it establishes.
- **Freeze at branch cut.** A finding that arrives after `release/digibyte-mobile-X.Y.Z` is
  cut goes into the next version's scope file, never back into a cut release.

## 2. Branch

**Step 0, until it has been done once:** merge `develop` and the DigiByte Mobile line, with
the **mobile line as the base** (it carries the package rename, the signing machinery and every
bounty fix since 4.0.87). As of 2026-10-11 that is 62/14 commits and about 77 conflicting paths.
After that merge `develop` builds DigiByte Mobile and this section reduces to the next bullet.

- Cut `release/digibyte-mobile-X.Y.Z` from the development line.
- Bump `versionName` and `versionCode` in `app/build.gradle.kts`. `versionCode` must exceed
  the last DigiByte Mobile release's — Play rejects anything else. It is per package, so
  io.digibyte's numbers do not constrain it.
- Refresh the block checkpoints. A stale checkpoint is a recurring release chore, not a
  one-off.

## 3. Core changes land by merge, and the pin must be contained

- Core work lands on core `develop` by **merge — never cherry-pick.** A cherry-pick creates
  new commits, so every app branch pinned to the originals is left pinning commits that are
  on no durable branch.
- The pin rule is **containment**: the app's submodule pin must equal, or be an ancestor of,
  the tip of core `develop` or `master`. Check it with `scripts/check-submodule-pin.sh`.
- **A fix to a script CI runs does not reach open PRs.** CI runs each PR branch's *own copy*
  of the script. After changing `check-submodule-pin.sh` (or any CI-invoked script), merge the
  base into every open PR before deciding the fix worked. (On 2026-10-11, twelve PRs kept
  failing a check that had been fixed on `develop` the day before, for exactly this reason.)

## 4. Gates — all of them, at the release commit

Run every gate against the exact commit you will build. A gate run on a neighbouring commit
proves nothing about this one.

1. **Host KATs:** `scripts/run-host-kats.sh` — every suite, zero failures. (116 suites at
   4.0.89.) Every arm that is expected to fault must run under a time limit (`bounded()`); an
   AddressSanitizer stack-overflow report can deadlock instead of aborting, and one unbounded
   arm stalls the whole sweep.
2. **Unit tests:** core, app (play flavour), app (sideload flavour).
3. **JNI registry:** `python3 scripts/gen-jni-registry.py --check`.
4. **Submodule pin:** `scripts/check-submodule-pin.sh`.
5. **Upgrade test on an emulator** holding the previous release with a funded test wallet:
   install over it; saved data loads, sync completes, balance and history are unchanged, the
   missing-funds scan is clean.

## 5. Build — on the owner's machine, never in CI

**The release key never goes to CI** (owner decision 2026-10-05). It is also the app-signing
key Google Play holds, so a stolen CI secret would sign a malicious update for every Play and
sideload user.

In your own terminal — not through a Claude session, so the password never enters a transcript:

```bash
export ATB_SIGNING_STORE_FILE=<path to the .p12>
read -rs ATB_SIGNING_STORE_PASSWORD && export ATB_SIGNING_STORE_PASSWORD
scripts/build-release.sh
```

The script refuses a dirty tree, an artifact signed by anything but the release key, more than
one signer, and the wrong package. Output, under `dist/vX.Y.Z/`:

| File | Goes to |
|---|---|
| `app-mainnet-play-release.aab` | Play Console |
| `aroundtheblock-wallet-vX.Y.Z.apk` | the sideload release |
| `mapping-play/`, `mapping-sideload/` | **keep forever** — a crash report is unreadable without them |
| `SHA256SUMS` | the sideload release |

## 6. Verify before publishing

- Both artifacts are signed by the release certificate, SHA-256
  `7E:36:DD:B2:85:61:B7:85:6A:D0:B6:27:BF:15:65:39:F8:B5:7F:97:24:21:48:D5:65:09:0C:B0:AA:8E:DF:87`
  (public; it is in `scripts/build-release.sh`).
- `SHA256SUMS` is generated from the files on disk, **never edited by hand**.

## 7. Publish

1. **Sideload:** a release on `JohnnyLawDGB/aroundtheblock-wallet-releases`, tag `vX.Y.Z`,
   with the APK and `SHA256SUMS`. Release notes written by hand, **with reporter credits as
   they are written** (§8), not added afterwards.
2. **Play:** the AAB to open testing, with that release's "what's new" text.
3. **digiscope.me downloads page:** link the new release.
   **Never repoint `digibyte-wallet-latest.apk` or `latest.json`.** The retired io.digibyte
   app's in-app updater polls them; they must keep pointing at io.digibyte 4.0.87.2.
4. If the privacy policy changed, update its effective date where it is published.

## 8. Bounty obligations that ship with the release

- **Credit** every reporter whose finding the release fixes, under the name they gave
  permission for (Hall of Fame list in the private repo). Nobody is credited without
  permission; nobody with permission is left out.
- **Move the bounty target** in `BUG-BOUNTY.md` to this release. The previous target stays in
  scope for 14 days.
- **Advisories.** For every finding this release fixes, publish its GitHub security advisory
  or record in the scope file why not. Reporters are told to keep details private "until the
  fix is publicly disclosed"; shipping the fix without ever publishing the advisory leaves them
  waiting on an event that does not come.
- **Tell each reporter** the fix has shipped and in which version.

## 9. After the release

- Mark the scope file shipped and open `RELEASE-<next>-SCOPE.md`.
- Commit the private repo (its pre-commit hook refuses recovery phrases and keys) and refresh
  its offline bundles.
- Merge the release branch back into the development line, so nothing fixed on the release
  branch is lost from the next one.
- **Push every branch you want to keep.** A branch that exists on one machine is one disk
  failure from gone. (On 2026-10-11 a finished gate, its CI hook and a lesson about lost work
  were found on a branch that had never been pushed.)

---

## Checklist

```
[ ] 0  develop and the mobile line merged (until done once)
[ ] 1  RELEASE-X.Y.Z-SCOPE.md complete; frozen at cut
[ ] 2  release/digibyte-mobile-X.Y.Z cut; version + versionCode bumped; checkpoints refreshed
[ ] 3  core landed by merge; check-submodule-pin.sh OK
[ ] 4  KATs all green · unit tests ×3 · JNI registry · upgrade test PASS — at the release commit
[ ] 5  build-release.sh in the owner's terminal
[ ] 6  signer = release cert on both artifacts · SHA256SUMS from disk
[ ] 7  releases repo · Play · digiscope.me link (latest.json untouched)
[ ] 8  credits · bounty target moved · advisories published or justified · reporters told
[ ] 9  scope closed, next opened · private repo committed + bundled · release merged back · branches pushed
```
