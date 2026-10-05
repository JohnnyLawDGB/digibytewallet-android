#!/usr/bin/env bash
#
# Builds, signs and verifies a release of app.aroundtheblock.wallet ON THE OWNER'S MACHINE.
#
# The release key never goes to CI (owner decision 2026-10-05): it is also the app-signing key Play
# holds, so a stolen CI secret would sign a malicious update for every Play and sideload user. This
# script is the whole release build instead. It runs the gates the CI release job used to run, then:
#
#   app-mainnet-play-release.aab        -> Play Console (Internal testing first)
#   aroundtheblock-wallet-v<ver>.apk    -> the sideload release (releases repo + digiscope.me)
#   mapping-play/, mapping-sideload/    -> R8 mappings; KEEP them, a crash report is unreadable without
#   SHA256SUMS
#
# all under dist/v<version>/ (gitignored). Both artifacts must be signed by the key whose SHA-256 is
# EXPECTED_SHA256 below, or nothing is kept: a build signed by anything else cannot update a single
# existing install, and finding that out after uploading it to Play is the expensive way.
#
# The key is read as the Gradle build reads it — ATB_SIGNING_* or keystore.properties — see
# docs/SIGNING.md. Nothing here prints, stores or forwards the password.
#
# Usage: scripts/build-release.sh [--allow-dirty]
set -euo pipefail

# Public: the certificate of the key generated 2026-10-04 (scripts/create-signing-key.sh).
EXPECTED_SHA256="${ATB_EXPECTED_SHA256:-7E:36:DD:B2:85:61:B7:85:6A:D0:B6:27:BF:15:65:39:F8:B5:7F:97:24:21:48:D5:65:09:0C:B0:AA:8E:DF:87}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
die() { echo "ERROR: $*" >&2; exit 1; }

if [ "${1:-}" != "--allow-dirty" ] && [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    die "the working tree has uncommitted changes. A release must be a commit someone can check out
       and rebuild. Commit or stash, or pass --allow-dirty for a test build that will not ship."
fi

VERSION=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | cut -d'"' -f2)
CODE=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | head -1 | grep -oE '[0-9]+')
[ -n "$VERSION" ] && [ -n "$CODE" ] || die "could not read versionName / versionCode"
echo "Release v$VERSION (versionCode $CODE) at $(git rev-parse --short HEAD)"

# ── The gates the CI release job ran ────────────────────────────────────────────────────────────
./scripts/check-security-cycle.sh
./scripts/check-no-keystore-tracked.sh
python3 scripts/gen-jni-registry.py --check

# ── Build. The Gradle build stops here if no key is configured. ─────────────────────────────────
./gradlew --console=plain clean :app:bundleMainnetPlayRelease :app:assembleMainnetSideloadRelease

AAB=app/build/outputs/bundle/mainnetPlayRelease/app-mainnet-play-release.aab
APK=app/build/outputs/apk/mainnetSideload/release/app-mainnet-sideload-release.apk
[ -f "$AAB" ] || die "no AAB at $AAB"
[ -f "$APK" ] || die "no signed APK at $APK (an -unsigned.apk means signing did not run)"

# ── Verify both are signed by the expected key ──────────────────────────────────────────────────
BT="$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/* | sort -V | tail -1)"
APKSIGNER="$BT/apksigner"; AAPT2="$BT/aapt2"
[ -x "$APKSIGNER" ] || die "apksigner not found under $BT"

norm() { tr -d ': \n' | tr 'a-f' 'A-F'; }
want=$(printf '%s' "$EXPECTED_SHA256" | norm)

apk_signers=$("$APKSIGNER" verify --print-certs "$APK" | grep -E 'certificate SHA-256 digest' | sed 's/.*digest: //' | sort -u)
[ "$(printf '%s\n' "$apk_signers" | wc -l)" -eq 1 ] || die "the APK has more than one signer:
$apk_signers"
[ "$(printf '%s' "$apk_signers" | norm)" = "$want" ] || die "the APK is signed by $apk_signers, not the release key"

aab_signer=$(keytool -printcert -jarfile "$AAB" | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | sort -u)
[ "$(printf '%s\n' "$aab_signer" | wc -l)" -eq 1 ] || die "the AAB has more than one signer:
$aab_signer"
[ "$(printf '%s' "$aab_signer" | norm)" = "$want" ] || die "the AAB is signed by $aab_signer, not the release key"

pkg=$("$AAPT2" dump packagename "$APK")
[ "$pkg" = "app.aroundtheblock.wallet" ] || die "the APK's package is $pkg"
echo "Signed by the release key ($EXPECTED_SHA256): AAB and APK. Package $pkg."

# ── Collect ─────────────────────────────────────────────────────────────────────────────────────
OUT="dist/v$VERSION"
rm -rf "$OUT"; mkdir -p "$OUT"
cp "$AAB" "$OUT/app-mainnet-play-release.aab"
cp "$APK" "$OUT/aroundtheblock-wallet-v$VERSION.apk"
cp -r app/build/outputs/mapping/mainnetPlayRelease "$OUT/mapping-play"
cp -r app/build/outputs/mapping/mainnetSideloadRelease "$OUT/mapping-sideload"
(cd "$OUT" && sha256sum app-mainnet-play-release.aab "aroundtheblock-wallet-v$VERSION.apk" > SHA256SUMS)
git rev-parse HEAD > "$OUT/COMMIT"

cat <<EOF

  Release v$VERSION is in $OUT/
$(sed 's/^/    /' "$OUT/SHA256SUMS")

  Play: Play Console -> Test and release -> Internal testing -> Create new release ->
        upload app-mainnet-play-release.aab. (First upload only: Play App Signing -> "Use your
        own key" -> the PEPK steps in docs/SIGNING.md.)
  Sideload: publish aroundtheblock-wallet-v$VERSION.apk to the releases repository.
  Keep mapping-play/ and mapping-sideload/ with this release.
EOF
