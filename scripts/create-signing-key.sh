#!/usr/bin/env bash
#
# Creates the release signing key for app.aroundtheblock.wallet — the key that signs every build
# of the new app, on Google Play and on the digiscope.me download alike.
#
# This key IS the app's identity. Android installs an update only when it is signed by the same
# key, so losing it means no user can ever be updated again (that is exactly how io.digibyte was
# lost), and leaking it lets anyone ship an "update" that steals funds. Hence:
#
#   - Run it once, on a machine you trust, by hand. It never runs in CI and makes no network call.
#   - It refuses to write inside a git checkout, and refuses to overwrite an existing key.
#   - The password is typed at a prompt and handed to keytool through the environment of that one
#     process — never on a command line (visible in `ps`), never written to a file.
#   - Only public material comes out besides the keystore: the certificate (.pem) and its
#     fingerprints, which are what Play's developer verification asks for.
#
# Play App Signing mode (decided): we upload THIS key to Play with PEPK at the first upload,
# because the same signature has to cover the sideloaded APKs. Nothing here talks to Play.
#
# Usage: scripts/create-signing-key.sh [OUTPUT_DIR]        (default: ~/aroundtheblock-wallet-keys)
# Override the certificate subject only if the legal name changes:
#   ATB_KEY_DNAME="CN=..., O=..., L=..., ST=..., C=US" scripts/create-signing-key.sh
set -euo pipefail

ALIAS="aroundtheblock-wallet"
KEYALG="RSA"
KEYSIZE=4096
VALIDITY_DAYS=10000
DNAME="${ATB_KEY_DNAME:-CN=Around The Block LLC, O=Around The Block LLC, L=Tulsa, ST=Oklahoma, C=US}"
MIN_PASSWORD_LEN=16

OUT_DIR="${1:-$HOME/aroundtheblock-wallet-keys}"
KEYSTORE="$OUT_DIR/$ALIAS.p12"
CERT="$OUT_DIR/$ALIAS-cert.pem"
FINGERPRINTS="$OUT_DIR/$ALIAS-fingerprints.txt"

die() { echo "ERROR: $*" >&2; exit 1; }

command -v keytool >/dev/null || die "keytool not found — install a JDK (17+) first."

# ── Where the key goes ─────────────────────────────────────────────────────────────────────────
umask 077
# Ask git about the nearest directory that already exists, so a refusal creates nothing.
probe="$OUT_DIR"
while [ ! -d "$probe" ]; do probe="$(dirname "$probe")"; done
if command -v git >/dev/null && git -C "$probe" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    die "$OUT_DIR is inside a git checkout ($(git -C "$probe" rev-parse --show-toplevel)).
       A signing key must never be one 'git add' away from a repository. Pick a directory outside any repo."
fi
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
KEYSTORE="$OUT_DIR/$ALIAS.p12"; CERT="$OUT_DIR/$ALIAS-cert.pem"; FINGERPRINTS="$OUT_DIR/$ALIAS-fingerprints.txt"
for f in "$KEYSTORE" "$CERT" "$FINGERPRINTS"; do
    [ -e "$f" ] && die "$f already exists. This script never overwrites a key — move it aside first if you really mean to start over."
done

EXPIRES="$(date -u -d "+$VALIDITY_DAYS days" +%Y-%m-%d 2>/dev/null || echo "in $VALIDITY_DAYS days")"

cat <<EOF

  About to create the release signing key for app.aroundtheblock.wallet

    Subject   : $DNAME
    Alias     : $ALIAS
    Algorithm : $KEYALG $KEYSIZE
    Valid     : $VALIDITY_DAYS days (until $EXPIRES)
    Keystore  : $KEYSTORE   (PKCS12)
    Public    : $CERT
                $FINGERPRINTS

  The subject is baked into the certificate and can never be changed afterwards.

EOF
read -r -p "  Type GENERATE to continue: " answer
[ "$answer" = "GENERATE" ] || die "aborted — nothing was created."

# ── Password ───────────────────────────────────────────────────────────────────────────────────
# PKCS12 uses one password for the store and the key; the release build reads it as both.
echo
echo "  Choose the keystore password (at least $MIN_PASSWORD_LEN characters; a passphrase is fine)."
echo "  Store it in your password manager — separately from every copy of the keystore file."
read -r -s -p "  Password: " pass1; echo
read -r -s -p "  Again:    " pass2; echo
[ "$pass1" = "$pass2" ] || die "the passwords do not match — nothing was created."
[ "${#pass1}" -ge "$MIN_PASSWORD_LEN" ] || die "the password is shorter than $MIN_PASSWORD_LEN characters — nothing was created."
unset pass2

kt() { ATB_KS_PASS="$pass1" keytool "$@"; }

# ── Generate ───────────────────────────────────────────────────────────────────────────────────
kt -genkeypair -v \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -storepass:env ATB_KS_PASS -keypass:env ATB_KS_PASS \
    -alias "$ALIAS" -keyalg "$KEYALG" -keysize "$KEYSIZE" -sigalg SHA256withRSA \
    -validity "$VALIDITY_DAYS" -dname "$DNAME" >/dev/null 2>&1 \
    || { rm -f "$KEYSTORE"; die "keytool could not create the key — nothing was kept."; }
chmod 600 "$KEYSTORE"

kt -exportcert -rfc -keystore "$KEYSTORE" -storepass:env ATB_KS_PASS -alias "$ALIAS" -file "$CERT" >/dev/null 2>&1 \
    || die "the key was created at $KEYSTORE but its certificate could not be exported."

listing="$(kt -list -v -keystore "$KEYSTORE" -storepass:env ATB_KS_PASS -alias "$ALIAS")"
unset pass1

sha256="$(printf '%s\n' "$listing" | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -1)"
sha1="$(printf '%s\n' "$listing" | sed -n 's/^[[:space:]]*SHA1:[[:space:]]*//p' | head -1)"
[ -n "$sha256" ] || die "could not read the SHA-256 fingerprint back from $KEYSTORE."

# A second, independent read of the same certificate: if openssl is present, the two must agree.
if command -v openssl >/dev/null; then
    check="$(openssl x509 -in "$CERT" -noout -fingerprint -sha256 | sed 's/^.*=//')"
    [ "$check" = "$sha256" ] || die "keytool ($sha256) and openssl ($check) disagree on the fingerprint — do not use this key."
fi

cat > "$FINGERPRINTS" <<EOF
app.aroundtheblock.wallet release signing certificate
Subject : $DNAME
Alias   : $ALIAS
Created : $(date -u +%Y-%m-%dT%H:%MZ)
Expires : $EXPIRES

SHA-256 : $sha256
SHA-1   : $sha1
SHA-256 (no colons, lower case — some forms and assetlinks tooling want this):
          $(printf '%s' "$sha256" | tr -d ':' | tr 'A-F' 'a-f')
EOF
chmod 644 "$CERT" "$FINGERPRINTS"

cat <<EOF

  Done. The key exists in exactly one place right now:

    $KEYSTORE

  SHA-256: $sha256

  Public (safe to share, paste, or commit):
    $CERT
    $FINGERPRINTS

  Before anything else — back it up:
    1. Copy $ALIAS.p12 to at least two offline places that are not this machine
       (e.g. two encrypted USB drives kept in different locations).
    2. Keep the password in your password manager, never next to a copy of the file.
    3. Prove a backup works: keytool -list -keystore <backup copy> -alias $ALIAS
       must ask for the password and print the SHA-256 above.
    Never email it, never upload it to a chat or a cloud drive, never put it in a repo.

  Then:
    - Play Console → developer verification: register app.aroundtheblock.wallet with the
      SHA-256 above (or upload $ALIAS-cert.pem if the form asks for the certificate).
    - Later, at the first upload: Play App Signing → "Use your own key" → export with PEPK.
    - The CI release secrets for this key are set up in the rebrand's Phase 3.

EOF
