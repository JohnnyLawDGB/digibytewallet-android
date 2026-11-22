#!/usr/bin/env bash
# Host KAT runner for BRDigiId.h: the Digi-ID per-site identity (SLIP-0013) and login signing, moved from the
# Android bridge (bridge/slip13.c; seed_derive_identity_key in jni_wallet.c; sign_with_key, base64_encode,
# write_varint, message_utf8, signMessage, signIdentityMessage and identityAddress in jni_wallet_sign.c). The
# assertions of slip13_identity_kat and identity_sign_bytes_kat, the derivation inputs of DigiIdRequestTest
# and the request of DigiIdIdentityChoiceTest run here, with SLIP-0013's published vector and the per-site
# identity each master-key root gives the three callback URLs Coinomi was measured signing in to
# (docs/superpowers/specs/2026-11-17-coinomi-digiid-design.md): BR_DIGIID_ROOT_DIGIBYTE ("DigiByte seed", the
# bridge's) and BR_DIGIID_ROOT_STANDARD ("Bitcoin seed": the addresses Coinomi showed, and BitID's published
# vector, and an independent model's answers where nothing was measured); an unknown root is refused, and so is a
# hasUri but 0 or 1 (a root swapped with it). Beside them a line-for-line C model of the bridge that the header is
# compared with over fixed pseudo-random inputs. See digiid_core_kat_main.c. Then the REAL bridge, booted over a
# host JNI, is held to the header answer for answer under the DigiByte root (digiid_core_bridge_main.c).
#
# WHICH IDENTITY SIGNS is the header's too (THE DIGISCOPE LIST and THE CHOICE: BRDigiIdIsDigiScopeHost,
# BRDigiIdChooseIdentity), and has no bridge to be held to. Its cases are by hand: iOS's DigiIdIdentityTests list
# cases, value for value (digiscope_list_*), each host crossed with both digiScopeIdentity values and both roots;
# the choice on a DigiScope host and off the list (choice_digiscope_shared, choice_digiscope_site, choice_other_host);
# every refusal (choice_refused, choice_unknown_scope_refused, choice_unknown_root_refused); and END TO END
# (choice_end_to_end), the choice fed to BRDigiIdAddress and the signing calls, where a wallet restored as from
# Coinomi signs DigiScope with DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri, the address Coinomi signed DigiScope's login with.
#
#   comparison  one arm per rule, -DDIGIID_UNFIXED=<bit> (BRDigiId.h): 1 the account index is hashed
#               big-endian; 2 A..D are read big-endian; 4 A..D are not hardened; 8 a negative index is
#               taken as its uint32 bits; 16 the shared identity's address is given for any index; 32 a
#               message over 0xffff bytes gets one length byte (the pre-MSG-LEN encoder); 64 the message is hashed once;
#               128 the key is not marked compressed; 256 address format 2 answers P2PKH; 512 the caller's
#               key is left unwiped after signing; 1024 a lone surrogate is written as three bytes; 2048
#               modified UTF-8; 4096 the shared identity is m/0'/1/0; 8192 unpadded base64; 16384 the URI's
#               surrogate pairs as two three-byte halves (the JNI specification's form, which ART does not
#               write); 32768 the URI written by the message's rules (U+0000 one byte, a lone surrogate '?');
#               65536 BRDigiIdAddressWithKey leaves the caller's key unwiped; 131072 every known root derives
#               with "DigiByte seed" (the per-site master key before the root was an argument); 262144 every
#               known root derives with "Bitcoin seed" (the constant swapped in place, which moves every
#               existing identity); 524288 a root that is neither DIGIBYTE nor STANDARD is not refused (it
#               derives with "DigiByte seed", and BRDigiIdChooseIdentity chooses with it); 1048576 BRDigiIdAddress
#               takes any nonzero hasUri as 1, so a root swapped with hasUri is not refused; 2097152 a name ending
#               in ".digiscope.me" counts as a DigiScope host (a suffix match in place of the exact one); 4194304
#               the list is compared without regard to letter case, so a spelling no parse gives is on it; 8388608
#               a wallet whose digiScopeIdentity is SITE still gets the shared identity on a DigiScope host (the
#               rule before the property: it signs in as another account without a word); 16777216 SITE on a
#               DigiScope host answers the request's own callback, not DigiScope's login URL; 33554432 a
#               digiScopeIdentity that is neither SHARED nor SITE is not refused; 67108864 only api.digiscope.me
#               is on the list (digiscope.me is not); 134217728 a name that begins with a listed host counts as one
#               (the length is not compared: a trailing dot, a port, the host's NUL counted in); 268435456 the
#               SHARED answer names the wallet's root, not none; 536870912 a request whose callback is DigiScope's
#               login URL gets DigiScope's answer on any host (the callback read where the host decides);
#               1073741824 a refusal leaves the answer as the caller had it, not all zero; 2147483648 a host off the
#               list with no callback URL is not refused. Each arm fails exactly the cases CASES lists for its bit
#               and passes every other, and every case has at least one arm: a case no arm fails proves nothing
#               about the rule it holds, and the gate refuses it.
#   shipped     -DDIGIID_UNFIXED=0: every case passes, exit 0.
#   bridge      the shipped header under BR_DIGIID_ROOT_DIGIBYTE against the bridge's own jni_wallet_sign.c /
#               jni_wallet.c / slip13.c, over identity_sign_bytes_kat's host_jni stand-ins: every answer and
#               refusal the same. The bridge takes no root (its per-site master key is "DigiByte seed"), so
#               BR_DIGIID_ROOT_STANDARD has no bridge answer to match until step 2 of the design; its cases in
#               digiid_core_kat_main.c hold it. The host env's GetStringUTFChars writes what ART's does (a
#               surrogate pair as four bytes), and the header gets the URI through BRDigiIdUriBytesFromUtf16.
# All under ASan + UBSan, 64-bit; the shipped arm and the bridge comparison again 32-bit where a 32-bit link
# works (said so, and skipped, where it does not: no 32-bit link on an arm64 Mac). The arms run with
# detect_leaks=0; then every case runs once more on the shipped arm under LeakSanitizer where the platform
# has one (said so, and skipped, where it does not -- Apple clang on macOS).
#
# Gates first: the header must test the seam's VALUE (#if, never #ifdef) with an #ifndef default.
# Then:
#   PORTABILITY GATE  the header includes only libc, the three core headers whose functions it calls and
#                     BRDerivationProfiles.h (the standard master key's name; libc only, nothing to link),
#                     reads no clock, does no I/O, allocates nothing and uses no rand(); builds clean under
#                     -Wall -Wextra -Wpedantic -Werror at c99/c11/c17 (two TUs, the header included twice in
#                     the second); for iphoneos/iphonesimulator, and through Swift's C importer (swiftc
#                     -import-objc-header) on macOS, where an Xcode SDK / swiftc is present (skipped, and
#                     said so, elsewhere).
#   SOURCE GATE       one DigiScope list. Until Android reads the list from the core (Coinomi step 2) it is written
#                     twice in shipped code: in BRDigiId.h (BRDigiIdIsDigiScopeHost) and in Android's
#                     IdentityKeyPolicy.kt (DIGISCOPE_HOSTS). Both must be exactly the hosts digiid_core_kat_main.c
#                     holds the header to (`hosts`), so neither copy can drift from the other meanwhile.
#                     digiscope_hosts.py reads the Kotlin set literal with a scanner that tells a string from a
#                     comment, and the header's list out of the compiler's own preprocessing. Its comparison arm
#                     plants a third host in a temp copy of each file, and the gate must refuse both by name; a
#                     host planted only in comments, and a literal written over several lines, must still read as
#                     the two.
#   MODEL             slip13_model.py, an independent model (Python's standard library only: its own secp256k1
#                     arithmetic, BIP32, RIPEMD-160, Base58Check and RFC 6979 signature; no code shared with the
#                     core, the bridge or digiid_core_kat_main.c), gives the literals again. Each `KAT <id>` line it
#                     prints must be a string literal of digiid_core_kat_main.c, on the line MODEL_ROWS names for
#                     that id (or the line after it): the addresses Coinomi showed under "Bitcoin seed", BitID's
#                     published vector, the STANDARD answers nothing measured, and the bridge's own under "DigiByte
#                     seed". Each `GOLDEN` line must be a whole line of bridge_golden.txt, and every ADDRESS and
#                     SIGN line there is one the model gives (but MODEL_NOT_GOLDEN's, named). Its comparison arms
#                     run the model with one root's HMAC key replaced (STANDARD with "DigiByte seed", DIGIBYTE with
#                     "Bitcoin seed", the shared identity with "Bitcoin seed"): exactly that root's lines must be
#                     refused, each by name, and every other line held. No python3 is a failure, not a skip.
#   GOLDEN            bridge_golden.txt: every ADDRESS and SIGN line `digiid_core_bridge print` answers and every
#                     SWEEP line of its sweep (the count and SHA-256 of the bridge's answers over the fixed stream,
#                     refusals included), written once from the bridge of 4.1.5 and compared byte for byte, 64-bit
#                     and 32-bit, on every run. This is the characterization taken before the Coinomi design's step
#                     2 changes how the bridge derives a per-site key: once the bridge calls the header, "the bridge
#                     equals the header" proves nothing, and this file is what still holds the bridge to the
#                     answers it gave. Its comparison arms: a file with one answer changed and a file with one line
#                     missing are refused by that line's name; and a bridge whose per-site path begins m/14' (a
#                     temp copy of jni_wallet.c) is refused on every per-site and SWEEP line while both shared
#                     lines hold.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DDIGIID_UNFIXED, a set of bits (one bit per comparison arm, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HOST_JNI="$SCRIPT_DIR/../identity_sign_bytes_kat/host_jni"
HEADER="$CORE_DIR/BRDigiId.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<the comparison arms (bits of DIGIID_UNFIXED) that must fail it, comma-separated; at least one>
CASES=(
    slip13_published_vectors:2
    slip13_identity_carried:2,4,262144
    slip13_index_values:1,2
    slip13_edges:1,2
    site_key_derivation:1,2,4,8,262144
    site_key_digibyte_coinomi_uris:2,4,64,8192,262144
    site_key_standard_coinomi_uris:2,4,64,8192,131072,262144
    site_key_standard_bitid_vector:2,4,131072
    site_key_standard_login_and_indexes:1,2,4,8,64,8192,131072
    shared_key_derivation:4096
    shared_address_both_roots:16,4096
    unknown_root_refused:524288
    address_root_hasuri_swapped:1048576
    message_prefix:32
    message_digest:64
    utf8_bytes:1024,2048
    uri_bytes:16384,32768
    uri_identity:2,4,64,8192,16384,32768,262144
    sign_shared_login:64,256,4096,8192
    sign_site_request:2,4,8,64,2048,4096,8192,262144
    request_golden_inputs:2,4,262144
    address_rules:1,2,4,8,16,4096,262144
    sign_with_key_wipes:128,512
    address_with_key_wipes:128,65536
    long_messages:2,4,32,64,4096,8192,262144
    edges:1,2,4,8,16,64,256,4096,8192,262144,67108864
    buffer_edges:512
    digiscope_list_hosts:67108864,268435456
    digiscope_list_subdomains:2097152
    digiscope_list_lookalikes:134217728
    digiscope_list_letter_case:4194304
    digiscope_list_edges:67108864,134217728,1073741824
    choice_digiscope_shared:67108864,268435456
    choice_digiscope_site:8388608,16777216,67108864
    choice_other_host:536870912
    choice_refused:1073741824,2147483648
    choice_unknown_scope_refused:33554432,1073741824
    choice_unknown_root_refused:524288,1073741824
    choice_end_to_end:2,4,64,4096,8192,131072,262144,524288,8388608,16777216,67108864,268435456
    sweep_indexes:1,2
    sweep_keys:1,2,4,8,4096,262144
    sweep_keys_standard:1,2,4,8,64,8192,131072
    sweep_sign:1,2,4,8,16,64,256,4096,8192,262144
    sweep_utf8:1024,2048
    sweep_uri_bytes:16384,32768
)
ARMS=(1:index-big-endian 2:words-big-endian 4:soft-path 8:negative-index 16:shared-any-index 32:compact-size
      64:single-sha 128:uncompressed 256:segwit-ignored 512:key-kept 1024:lone-surrogate-kept 2048:modified-utf8
      4096:shared-path 8192:base64-unpadded 16384:uri-spec-pairs 32768:uri-as-message 65536:address-key-kept
      131072:root-ignored 262144:root-always-standard 524288:any-root 1048576:any-hasuri 2097152:list-suffix
      4194304:list-casefold 8388608:site-signs-shared 16777216:site-uses-callback 33554432:any-scope
      67108864:list-api-only 134217728:list-prefix 268435456:shared-names-root 536870912:callback-decides
      1073741824:refusal-keeps-out 2147483648:no-callback-answers)
for entry in "${CASES[@]}"; do   # every case is failed by an arm this script runs
    arms_of="${entry##*:}"
    [ -n "$arms_of" ] && [ "$arms_of" != "$entry" ] && [ "$arms_of" != - ] || {
        echo "GATE FAILURE: case ${entry%%:*} lists no comparison arm; nothing shows it can fail."; exit 1; }
    for bit in ${arms_of//,/ }; do
        case " ${ARMS[*]%%:*} " in *" $bit "*) ;; *) echo "GATE FAILURE: case ${entry%%:*} lists arm $bit, which is not in ARMS."; exit 1 ;; esac
    done
done

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before Digi-ID moved into the core."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRDigiIdSlip13Indexes BRDigiIdSiteKey BRDigiIdSharedKey BRDigiIdMessagePrefix BRDigiIdMessageDigest BRDigiIdUtf8FromUtf16 \
          BRDigiIdUriBytesFromUtf16 BRDigiIdAddressWithKey BRDigiIdSignWithKey BRDigiIdSignShared BRDigiIdSignSite BRDigiIdAddress \
          BRDigiIdIsDigiScopeHost BRDigiIdChooseIdentity; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn."; exit 1
    fi
done
if ! grep -Eq '^[[:space:]]*#[[:space:]]*(el)?if[[:space:]]+!?[[:space:]]*\(?DIGIID_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not test the value of DIGIID_UNFIXED; the arms would not differ."; exit 1
fi
if ! grep -Eq '^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+DIGIID_UNFIXED' <<<"$HDR" ||
   grep -Eq '^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+DIGIID_UNFIXED' <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
fi
for arm in "${ARMS[@]}"; do   # every arm's bit is one the header reads
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*define[[:space:]]+DIGIID_RED_[A-Z0-9_]+[[:space:]]+${arm%%:*}[[:space:]]*$" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER names no DIGIID_RED_* seam of value ${arm%%:*}; that arm would be the shipped code."; exit 1
    fi
done
if [ ! -f "$BRIDGE_DIR/slip13.c" ] || [ ! -f "$HOST_JNI/jni.h" ]; then
    echo "GATE FAILURE: the bridge's slip13.c or identity_sign_bytes_kat's host_jni is gone; nothing to compare with."; exit 1
fi

FAIL=0

# ---- the core the header calls into, built once per flag set -------------------------------
CORE_SRCS=(BRBIP32Sequence.c BRKey.c BRNetwork.c BRAddress.c BRCrypto.c BRBase58.c BRBech32.c BRBIP39Mnemonic.c
           crypto/groestl.c crypto/skein.c crypto/qubit.c crypto/odocrypt.c)
shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
core_objs() {   # <dir> <flags...>: compiles the core into <dir>; prints nothing; fails on a build error
    local dir="$1" src obj; shift
    mkdir -p "$dir"
    for src in "${CORE_SRCS[@]/#/$CORE_DIR/}" "${SHA3_SRCS[@]}"; do
        obj="${src#"$CORE_DIR"/}"; obj="$dir/${obj//\//_}"; obj="${obj%.c}.o"   # crypto/groestl.c and crypto/sha3/groestl.c are two
        "$CC" -w -include stdint.h "$@" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -c "$src" -o "$obj" || return 1
    done
}

# ---- PORTABILITY GATE ----------------------------------------------------------------------
strip_comments() {   # a C file with // and /* */ comments removed
    sed -e 's@//.*$@@' "$1" | awk 'BEGIN{c=0} { line=$0; out="";
        while (length(line)) {
            if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
            else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
                   out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
        print out }'
}
CODE="$(strip_comments "$HEADER")"
includes="$(grep -E '^[[:space:]]*#[[:space:]]*include' <<<"$CODE" | sed -E 's/.*include[[:space:]]*//; s/[[:space:]]+$//' | sort -u | tr '\n' ' ')"
want='"BRBIP32Sequence.h" "BRCrypto.h" "BRDerivationProfiles.h" "BRKey.h" <stddef.h> <stdint.h> <string.h> '
if [ "$includes" != "$want" ]; then
    echo "PORTABILITY GATE FAILED: the header includes $includes(want $want)"; FAIL=1
fi
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|puts|open|read|write|socket|malloc|calloc|realloc|free|rand|random|arc4random|syslog)[[:space:]]*\('
if grep -Eq "$IO_RE" <<<"$CODE"; then
    echo "PORTABILITY GATE FAILED: the header calls a clock, I/O, the allocator or a random source:"
    grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
fi
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRDigiId.h"
int strict_tu1(void);
int strict_tu1(void)
{
    uint32_t idx[4];
    uint8_t work[64];
    return BRDigiIdSlip13Indexes(idx, "http://bitid.bitcoin.blue/callback", 34, 0, work, sizeof(work)) == BR_DIGIID_OK &&
           idx[0] == 0xbe553112u && idx[3] == 0xedd2bf37u;
}
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRDigiId.h"
#include "BRDigiId.h"
#include "BRBIP39Mnemonic.h"
int strict_tu1(void);
int main(void)
{
    uint8_t seed[64], work[128];
    char out[BR_DIGIID_ANSWER_CAPACITY], addr[BR_DIGIID_ADDRESS_CAPACITY];
    static const uint16_t units[] = { 'h', 0xD83D, 0xDE00 };
    uint8_t bytes[16];
    size_t len = 0;
    BRDigiIdChoice choice;
    int ok;
    BRBIP39DeriveKey(seed, "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", NULL);
    ok = BRDigiIdAddress(addr, sizeof(addr), seed, sizeof(seed), BR_DIGIID_ROOT_DIGIBYTE, 0, NULL, 0, 0, work, sizeof(work)) == BR_DIGIID_OK &&
         strcmp(addr, "DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn") == 0 &&
         BRDigiIdSignSite(out, sizeof(out), seed, sizeof(seed), BR_DIGIID_ROOT_DIGIBYTE, (const uint8_t *)"m", 1, "https://example.com/callback",
                          28, 0, work, sizeof(work)) == BR_DIGIID_OK && strncmp(out, "DPeEMYki92jeUSDgjkrfrTMVpvGWCHYd68|", 35) == 0 &&
         BRDigiIdUtf8FromUtf16(bytes, sizeof(bytes), &len, units, 3) == BR_DIGIID_OK && len == 5 &&
         BRDigiIdIsDigiScopeHost("digiscope.me", 12) == 1 && BRDigiIdIsDigiScopeHost("hub.digiscope.me", 16) == 0 &&
         BRDigiIdChooseIdentity(&choice, "api.digiscope.me", 16, NULL, 0, BR_DIGIID_SCOPE_SITE, BR_DIGIID_ROOT_STANDARD) == BR_DIGIID_OK &&
         choice.kind == BR_DIGIID_IDENTITY_SITE && choice.uriLen == sizeof(BR_DIGIID_DIGISCOPE_LOGIN_URI) - 1 &&
         BRDigiIdAddress(addr, sizeof(addr), seed, sizeof(seed), choice.root, 1, choice.uri, choice.uriLen, 0, work, sizeof(work)) == BR_DIGIID_OK &&
         strcmp(addr, "DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri") == 0;
    mem_clean(seed, sizeof(seed));
    return (ok && strict_tu1()) ? 0 : 1;
}
EOF
strict_ok=1
if ! core_objs "$BUILD_DIR/core_plain"; then echo "GATE FAILURE: the core did not compile (a build error is not evidence)."; exit 1; fi
for std in c99 c11 c17; do
    if ! "$CC" -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" \
            "$BUILD_DIR/strict1.c" "$BUILD_DIR/strict2.c" "$BUILD_DIR"/core_plain/*.o -lm -lpthread -o "$BUILD_DIR/strict_$std" ||
       ! "$BUILD_DIR/strict_$std"; then
        echo "PORTABILITY GATE FAILED: -std=$std -Wall -Wextra -Wpedantic -Werror (two TUs)"; FAIL=1; strict_ok=0
    fi
done
ios_note="no Xcode SDK here, iOS cross-compile skipped"
if command -v xcrun >/dev/null 2>&1 && xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    ios_note="iphoneos + iphonesimulator"
    for sdk in iphoneos iphonesimulator; do
        if ! xcrun --sdk $sdk clang -arch arm64 -std=c11 -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" \
                -c "$BUILD_DIR/strict2.c" -o "$BUILD_DIR/ios_$sdk.o"; then
            echo "PORTABILITY GATE FAILED: $sdk arm64"; FAIL=1; strict_ok=0
        fi
    done
fi
swift_note="not macOS, Swift import skipped (the iOS toolchain is Apple's)"
if [ "$(uname -s)" = Darwin ] && command -v swiftc >/dev/null 2>&1; then
    printf '#include "BRDigiId.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
let seed = [UInt8](repeating: 1, count: 64)
// The URI's bytes as Android hashes them: from its UTF-16 units (THE URI in BRDigiId.h), not uri.utf8,
// which differs for U+0000.
let uri = "https://example.com/\u{1F600}"
let uriUnits: [UInt16] = Array(uri.utf16)
var uriRaw = [UInt8](repeating: 0, count: 3 * uriUnits.count)
var uriLength = 0
let uriStatus: BRDigiIdStatus = BRDigiIdUriBytesFromUtf16(&uriRaw, uriRaw.count, &uriLength, uriUnits, uriUnits.count)
let uriBytes: [CChar] = uriRaw[0..<uriLength].map { CChar(bitPattern: $0) }
let units: [UInt16] = Array("gm \u{1F600}".utf16)
var message = [UInt8](repeating: 0, count: 3 * units.count)
var length = 0
let utf8: BRDigiIdStatus = BRDigiIdUtf8FromUtf16(&message, message.count, &length, units, units.count)
var work = [UInt8](repeating: 0, count: BRDigiIdSignSiteWorkSize(uriLength, length))
var out = [CChar](repeating: 0, count: Int(BR_DIGIID_ANSWER_CAPACITY))
var addr = [CChar](repeating: 0, count: Int(BR_DIGIID_ADDRESS_CAPACITY))
let workCount = work.count
let signed: BRDigiIdStatus = BRDigiIdSignSite(&out, out.count, seed, seed.count, BR_DIGIID_ROOT_DIGIBYTE, message, length, uriBytes,
                                              uriLength, 0, &work, workCount)
let shared: BRDigiIdStatus = BRDigiIdSignShared(&out, out.count, seed, seed.count, message, length, 2, &work, workCount)
let address: BRDigiIdStatus = BRDigiIdAddress(&addr, addr.count, seed, seed.count, BR_DIGIID_ROOT_DIGIBYTE, 0, nil, 0, 0, &work, workCount)
var idx = [UInt32](repeating: 0, count: 4)
let indexes: BRDigiIdStatus = BRDigiIdSlip13Indexes(&idx, uriBytes, uriLength, 0, &work, workCount)
var key = BRKey()
let siteKey: BRDigiIdStatus = BRDigiIdSiteKey(&key, seed, seed.count, BR_DIGIID_ROOT_DIGIBYTE, uriBytes, uriLength, 0, &work, workCount)
let keyAddress: BRDigiIdStatus = BRDigiIdAddressWithKey(&addr, addr.count, &key)
// Which identity signs (THE CHOICE in BRDigiId.h): the request's host and callback as bytes, the wallet's two
// properties as the enums' constants, and DigiScope's login URL, a plain literal that Swift imports as a String.
let host: [CChar] = "api.digiscope.me".utf8.map { CChar(bitPattern: $0) }
let listed: Int32 = BRDigiIdIsDigiScopeHost(host, host.count)
var choice = BRDigiIdChoice()
let chosen: BRDigiIdStatus = BRDigiIdChooseIdentity(&choice, host, host.count, uriBytes, uriLength, BR_DIGIID_SCOPE_SITE, BR_DIGIID_ROOT_STANDARD)
let chosenKind: BRDigiIdIdentityKind = choice.kind
let chosenRoot: BRDigiIdRoot = choice.root
let chosenUri: UnsafePointer<CChar>? = choice.uri
let chosenLength: Int = choice.uriLen   // read after the call: on a DigiScope host the URI is the header's literal
// A host off the list: the answer's URI is the pointer passed in (THE ANSWER'S URI in BRDigiId.h), which Swift keeps
// only for one call when an array is passed. So the choice and the signing are made inside one
// withUnsafeBufferPointer over the callback's bytes, and nothing of the answer is read outside it.
let otherHost: [CChar] = "example.com".utf8.map { CChar(bitPattern: $0) }
var otherOut = [CChar](repeating: 0, count: Int(BR_DIGIID_ANSWER_CAPACITY))
let otherOutCount = otherOut.count
let otherSigned: Bool = uriBytes.withUnsafeBufferPointer { (callback: UnsafeBufferPointer<CChar>) -> Bool in
    var other = BRDigiIdChoice()
    let status: BRDigiIdStatus = BRDigiIdChooseIdentity(&other, otherHost, otherHost.count, callback.baseAddress, callback.count,
                                                        BR_DIGIID_SCOPE_SHARED, BR_DIGIID_ROOT_DIGIBYTE)
    if status != BR_DIGIID_OK || other.kind != BR_DIGIID_IDENTITY_SITE || other.uri != callback.baseAddress { return false }
    let signedOther: BRDigiIdStatus = BRDigiIdSignSite(&otherOut, otherOutCount, seed, seed.count, other.root, message, length, other.uri,
                                                       other.uriLen, 0, &work, workCount)
    return signedOther == BR_DIGIID_OK
}
let loginUri: String = BR_DIGIID_DIGISCOPE_LOGIN_URI
let unsetScope: BRDigiIdScopeIdentity = BR_DIGIID_SCOPE_NONE
print(uriStatus == BR_DIGIID_OK, signed == BR_DIGIID_OK, shared == BR_DIGIID_OK, address == BR_DIGIID_OK, indexes == BR_DIGIID_OK,
      utf8 == BR_DIGIID_OK, siteKey == BR_DIGIID_OK, keyAddress == BR_DIGIID_OK, DIGIID_UNFIXED, BR_DIGIID_MESSAGE_HEADER_LENGTH,
      listed == 1, chosen == BR_DIGIID_OK, chosenKind == BR_DIGIID_IDENTITY_SITE, chosenRoot == BR_DIGIID_ROOT_STANDARD, chosenUri != nil,
      chosenLength == loginUri.utf8.count, unsetScope.rawValue == 0, BR_DIGIID_SCOPE_SHARED.rawValue == 1, otherSigned)
EOF
    # The headers alone, without the core's module.modulemap (Swift would read it as the BRCore module, and it
    # names headers this core no longer has): the way an app's bridging header sees them.
    # BRCrypto.h includes crypto/odocrypt.h, so the crypto headers come too.
    mkdir -p "$BUILD_DIR/swift_inc/crypto"
    cp "$CORE_DIR"/*.h "$BUILD_DIR/swift_inc/"
    cp "$CORE_DIR"/crypto/*.h "$BUILD_DIR/swift_inc/crypto/"
    if swiftc -typecheck -import-objc-header "$BUILD_DIR/bridging.h" -Xcc -I"$BUILD_DIR/swift_inc" \
            "$BUILD_DIR/use.swift" >"$BUILD_DIR/swift.out" 2>&1; then
        swift_note="Swift imports it"
    else
        echo "PORTABILITY GATE FAILED: swiftc could not import the header"; sed -n '1,12p' "$BUILD_DIR/swift.out" | sed 's/^/      /'
        FAIL=1; strict_ok=0
    fi
fi
[ $strict_ok -eq 1 ] && echo "portability gate: libc + four core headers, no clock/I/O/allocation/rand; clean at c99/c11/c17 ($ios_note; $swift_note)"

# ---- the arms ------------------------------------------------------------------------------
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRDigiId.h"
#include "BRDigiId.h"
int digiid_core_kat_tu2(void);
int digiid_core_kat_tu2(void)
{
    uint32_t idx[4];
    uint8_t work[64];
    BRDigiIdChoice choice;
    return BRDigiIdSlip13Indexes(idx, "http://bitid.bitcoin.blue/callback", 34, 0, work, sizeof(work)) == BR_DIGIID_OK && idx[0] == 0xbe553112u &&
           BRDigiIdDigestWorkSize(2) == 29 && BRDigiIdIsDigiScopeHost("api.digiscope.me", 16) == 1 &&
           BRDigiIdChooseIdentity(&choice, "digiscope.me", 12, NULL, 0, BR_DIGIID_SCOPE_SHARED, BR_DIGIID_ROOT_DIGIBYTE) == BR_DIGIID_OK &&
           choice.kind == BR_DIGIID_IDENTITY_SHARED;
}
EOF

SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
if ! core_objs "$BUILD_DIR/core_san" "${SAN[@]}"; then echo "GATE FAILURE: the core did not compile under the sanitizers."; exit 1; fi
build_arm() {   # <out> <unfixed bits>
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -DDIGIID_UNFIXED="$2" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$BRIDGE_DIR" \
        "$SCRIPT_DIR/digiid_core_kat_main.c" "$BUILD_DIR/tu2.c" "$BRIDGE_DIR/slip13.c" "$BUILD_DIR"/core_san/*.o -lm -lpthread -o "$1"; }

for arm in "${ARMS[@]}"; do
    build_arm "$BUILD_DIR/red_${arm%%:*}" "${arm%%:*}" || { echo "GATE FAILURE: comparison arm ${arm%%:*} did not compile (a build error is not evidence)."; exit 1; }
done
build_arm "$BUILD_DIR/green" 0 || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

# ---- SOURCE GATE: one DigiScope list -------------------------------------------------------
# Remove this gate, and digiscope_hosts.py's kotlin reader, in the change that makes Android read the list from the
# core (Coinomi step 2): IdentityKeyPolicy.kt's DIGISCOPE_HOSTS goes then, and the reader says so when it is gone.
KOTLIN_POLICY="$REPO_ROOT/core/src/main/java/app/aroundtheblock/wallet/core/digiid/IdentityKeyPolicy.kt"
HOSTS_PY="$SCRIPT_DIR/digiscope_hosts.py"
LIST_ARM="$BUILD_DIR/list_arm"
mkdir -p "$LIST_ARM/hdr"
printf '#include "BRDigiId.h"\n' > "$LIST_ARM/tu.c"
"$BUILD_DIR/green" hosts > "$LIST_ARM/kat.txt"
# one_list <IdentityKeyPolicy.kt> <the directory of the BRDigiId.h to read>: prints its verdict, and returns 0 when
# that header's list, that file's DIGISCOPE_HOSTS and the hosts digiid_core_kat_main.c names are the same hosts.
one_list() {
    local kotlin="$1" hdrdir="$2" k c t
    t="$(LC_ALL=C sort "$LIST_ARM/kat.txt" | tr '\n' ' ')"
    [ -n "$t" ] || { echo "digiid_core_kat_main.c names no host"; return 1; }
    if ! "$CC" -E -P -DDIGIID_UNFIXED=0 -I "$hdrdir" -I "$CORE_DIR" "$LIST_ARM/tu.c" > "$LIST_ARM/pre.c" 2> "$LIST_ARM/err.txt"; then
        echo "BRDigiId.h did not preprocess: $(head -1 "$LIST_ARM/err.txt")"; return 1
    fi
    if ! python3 -I "$HOSTS_PY" header "$LIST_ARM/pre.c" > "$LIST_ARM/c.txt" 2> "$LIST_ARM/err.txt"; then
        echo "BRDigiId.h: $(cat "$LIST_ARM/err.txt")"; return 1
    fi
    [ -f "$kotlin" ] || { echo "IdentityKeyPolicy.kt is not at $kotlin"; return 1; }
    if ! python3 -I "$HOSTS_PY" kotlin "$kotlin" > "$LIST_ARM/k.txt" 2> "$LIST_ARM/err.txt"; then
        echo "IdentityKeyPolicy.kt: $(cat "$LIST_ARM/err.txt")"; return 1
    fi
    c="$(LC_ALL=C sort "$LIST_ARM/c.txt" | tr '\n' ' ')"; k="$(LC_ALL=C sort "$LIST_ARM/k.txt" | tr '\n' ' ')"
    if [ "$c" != "$t" ]; then echo "BRDigiId.h lists [ $c] where digiid_core_kat_main.c holds it to [ $t]"; return 1; fi
    if [ "$k" != "$t" ]; then echo "IdentityKeyPolicy.kt's DIGISCOPE_HOSTS is [ $k] where BRDigiId.h lists [ $c]"; return 1; fi
    echo "BRDigiId.h, IdentityKeyPolicy.kt and digiid_core_kat_main.c name the same hosts: $t"
}
# expect_list <label> refused|same <what a refusal must name> <IdentityKeyPolicy.kt> <header directory>
expect_list() {
    local label="$1" want="$2" name="$3" out rc
    out="$(one_list "$4" "$5")"; rc=$?
    if [ "$want" = refused ]; then
        if [ $rc -ne 0 ] && grep -qF -- "$name" <<<"$out"; then echo "  [list $label] refused, as it must be: ${out:0:160}"
        else echo "  [list $label] GATE FAILURE: the gate did not refuse it, or not by name (rc=$rc): $out"; FAIL=1; fi
    elif [ $rc -eq 0 ]; then echo "  [list $label] read as the same hosts, as it must be"
    else echo "  [list $label] GATE FAILURE: $out"; FAIL=1; fi
}
echo "--- SOURCE GATE: one DigiScope list (BRDigiId.h, IdentityKeyPolicy.kt, digiid_core_kat_main.c) ---"
if ! command -v python3 >/dev/null 2>&1; then
    echo "  GATE FAILURE: no python3 to read the lists with (digiscope_hosts.py)"; FAIL=1
elif [ ! -f "$KOTLIN_POLICY" ]; then
    echo "  GATE FAILURE: $KOTLIN_POLICY is gone. If Android reads the list from the core now, remove this gate with it."; FAIL=1
elif ! out="$(one_list "$KOTLIN_POLICY" "$CORE_DIR")"; then
    # The comparison arm below plants in copies of these two files, so it has nothing to say while they disagree.
    echo "  [list] GATE FAILURE: $out"; FAIL=1
else
    echo "  [list] $out"
    # COMPARISON ARM, in temp copies: a third host planted in either file is refused by name; so are a host missing
    # from the Kotlin list and a list this gate cannot read; comments and layout change nothing.
    sed -E 's/(DIGISCOPE_HOSTS[^=]*=[[:space:]]*setOf\()/\1"planted.example", /' "$KOTLIN_POLICY" > "$LIST_ARM/third.kt"
    sed -E 's/(DIGISCOPE_HOSTS[^=]*=[[:space:]]*setOf\([^)]*\))/\1 + "planted.example"/' "$KOTLIN_POLICY" > "$LIST_ARM/plus.kt"
    { printf '// val DIGISCOPE_HOSTS: Set<String> = setOf("planted.example")\n/* "planted.example", /* "nested.example" */ */\n'
      sed -E 's/(DIGISCOPE_HOSTS[^=]*=[[:space:]]*setOf\()/\1\/* "planted.example", *\/ /' "$KOTLIN_POLICY"; } > "$LIST_ARM/comments.kt"
    { printf 'object IdentityKeyPolicy {\n    val DIGISCOPE_HOSTS : Set < String > =\n        setOf (\n'
      while IFS= read -r h; do printf '            "%s"   // "planted.example",\n            ,\n' "$h"; done < "$LIST_ARM/kat.txt"
      printf '        )\n}\n'; } > "$LIST_ARM/layout.kt"
    printf 'object IdentityKeyPolicy {\n    val DIGISCOPE_HOSTS: Set<String> = setOf("%s")\n}\n' "$(head -1 "$LIST_ARM/kat.txt")" > "$LIST_ARM/one.kt"
    sed -E 's/(\{ "digiscope\.me", "api\.digiscope\.me")( \};)/\1, "planted.example"\2/' "$HEADER" > "$LIST_ARM/hdr/BRDigiId.h"
    if [ "$(grep -c 'planted\.example' "$LIST_ARM/third.kt")" -ne 1 ] || [ "$(grep -c 'planted\.example' "$LIST_ARM/plus.kt")" -ne 1 ] ||
       [ "$(grep -c 'planted\.example' "$LIST_ARM/comments.kt")" -ne 3 ] || [ "$(grep -c 'planted\.example' "$LIST_ARM/hdr/BRDigiId.h")" -ne 1 ]; then
        echo "  [list] GATE FAILURE: a plant found no anchor in IdentityKeyPolicy.kt or BRDigiId.h -- the comparison arm is blind"; FAIL=1
    else
        expect_list "a third host in IdentityKeyPolicy.kt" refused planted.example "$LIST_ARM/third.kt" "$CORE_DIR"
        expect_list "a third host in BRDigiId.h" refused planted.example "$KOTLIN_POLICY" "$LIST_ARM/hdr"
        expect_list "a host missing from IdentityKeyPolicy.kt" refused "IdentityKeyPolicy.kt's DIGISCOPE_HOSTS is" "$LIST_ARM/one.kt" "$CORE_DIR"
        expect_list "a list that is not plain literals" refused "cannot read it" "$LIST_ARM/plus.kt" "$CORE_DIR"
        expect_list "a third host only in comments" same "" "$LIST_ARM/comments.kt" "$CORE_DIR"
        expect_list "the list written over several lines" same "" "$LIST_ARM/layout.kt" "$CORE_DIR"
    fi
fi

# ---- MODEL: an independent model gives the literals again ----------------------------------
MODEL_PY="$SCRIPT_DIR/slip13_model.py"
GOLDEN="$SCRIPT_DIR/bridge_golden.txt"
MODEL_DIR="$BUILD_DIR/model"
mkdir -p "$MODEL_DIR"
# digiid_core_kat_main.c without its whole-line comments: a literal quoted in a comment holds nothing.
grep -vE '^[[:space:]]*//' "$SCRIPT_DIR/digiid_core_kat_main.c" > "$MODEL_DIR/kat_code.c"
: > "$MODEL_DIR/golden.txt"
[ -f "$GOLDEN" ] && grep -vE '^#' "$GOLDEN" > "$MODEL_DIR/golden.txt"
SHARED_NAME_RE='^(ADDRESS shared$|SIGN shared )'   # the golden lines the shared identity answers; every other is per-site
# <the model's id>|<the case of digiid_core_kat_main.c that holds the literal, or - for a constant of the file's>|<line:
# the literal's own line carries the text; above: that line or the one before it>|<the text>. The case matters: both
# roots' tables name the same three callback URLs, and a STANDARD answer found in the DIGIBYTE table holds nothing.
MODEL_ROWS=(
    'standard.coinomi.0|site_key_standard_coinomi_uris|line|"https://api.digiscope.me/api/digiid-probe/callback"'
    'standard.coinomi.1|site_key_standard_coinomi_uris|line|"https://api.digiscope.me/api/auth/digiid/callback"'
    'standard.coinomi.2|site_key_standard_coinomi_uris|line|"https://digiscope.me/api/digiid-probe/callback"'
    'standard.login.index.1|site_key_standard_login_and_indexes|line|{ 1, '
    'standard.login.index.2|site_key_standard_login_and_indexes|line|{ 2, '
    'standard.login.index.2147483647|site_key_standard_login_and_indexes|line|{ INT32_MAX, '
    'standard.login.answer|-|above|LOGIN_ANSWER_STANDARD_SITE ='
    'standard.bitid|site_key_standard_bitid_vector|line|BRBase58CheckDecode(published'
    'digibyte.coinomi.0|site_key_digibyte_coinomi_uris|line|"https://api.digiscope.me/api/digiid-probe/callback"'
    'digibyte.coinomi.1|site_key_digibyte_coinomi_uris|line|"https://api.digiscope.me/api/auth/digiid/callback"'
    'digibyte.coinomi.2|site_key_digibyte_coinomi_uris|line|"https://digiscope.me/api/digiid-probe/callback"'
    'digibyte.login.answer|site_key_digibyte_coinomi_uris|above|"https://api.digiscope.me/api/auth/digiid/callback"'
    'digibyte.bitid|site_key_derivation|line|the BitID phrase and callback'
    'digibyte.request.0|request_golden_inputs|line|"https://example.com/callback"'
    'digibyte.request.1|request_golden_inputs|line|"https://example.com:8443/callback"'
    'digibyte.request.2|request_golden_inputs|line|"https://example.com:443/callback"'
    'digibyte.request.3|request_golden_inputs|line|"https://shop.example.com/digiid/callback/v1"'
    'digibyte.request.4|request_golden_inputs|line|"http://example.com/auth"'
    'digibyte.request.5|request_golden_inputs|line|"http://localhost:8080/auth"'
    'digibyte.request.6|request_golden_inputs|line|"https://api.digiscope.me/api/digiid/callback"'
    'digibyte.request.7|request_golden_inputs|line|"https://example.com/cb"'
    'digibyte.request.8|request_golden_inputs|line|"https://example.com/"'
    'digibyte.request.9|request_golden_inputs|line|"https://example.com"'
    'digibyte.request.10|request_golden_inputs|line|"https://sub.domain-with-dash.co.uk/digiid/login.php"'
    'digibyte.request.11|request_golden_inputs|line|"http://192.168.1.10:3000/digiid"'
    'digibyte.request.12|request_golden_inputs|line|"https://Example.COM/cb"'
    'digibyte.request.index.1|address_rules|line|REQUEST_CALLBACK, 1)'
    'digibyte.request.index.2147483647|address_rules|line|REQUEST_CALLBACK, INT32_MAX)'
    'digibyte.empty-uri|address_rules|line|hAddress(out, seed, "", 0)'
    'digibyte.emoji-uri|-|line|EMOJI_URI_ADDRESS ='
    'digibyte.emoji-uri.spec-bytes|-|line|EMOJI_URI_SPEC_ADDRESS ='
    'digibyte.nul-uri|-|line|NUL_URI_ADDRESS ='
    'digibyte.lone-surrogate-uri|-|line|LONE_URI_ADDRESS ='
    'digibyte.request.answer|-|above|REQUEST_SITE_ANSWER ='
    'digibyte.emoji-uri.answer|-|above|EMOJI_URI_REQUEST_ANSWER ='
    'digibyte.emoji-message.answer|-|above|EMOJI_SITE_ANSWER ='
    'shared.address|-|line|SHARED_ADDRESS ='
    'shared.login.answer|-|above|LOGIN_ANSWER_P2PKH ='
    'shared.empty.answer|-|above|EMPTY_ANSWER ='
    'shared.request.answer|-|above|REQUEST_SHARED_ANSWER ='
)
# bridge_golden.txt's ADDRESS and SIGN lines the model does not give, by name: the P2WPKH answer (the model
# writes no bech32; its signature is the format 0 line's, which the model gives).
MODEL_NOT_GOLDEN=('SIGN shared login format 2')
# model_verdict <the model's output>: one "REFUSED<tab><id><tab><why>" line for each line of the model's that
# the literals do not hold (a GOLDEN line's id is "GOLDEN <the line but its last field>"); returns 0 when none.
model_verdict() {
    local file="$1" row id rest section where anchor ans hits line name skip src bad=0 printed listed
    printed="$(awk '$1 == "KAT" { print $2 }' "$file" | LC_ALL=C sort | tr '\n' ' ')"
    listed="$(printf '%s\n' "${MODEL_ROWS[@]%%|*}" | LC_ALL=C sort | tr '\n' ' ')"
    if [ "$printed" != "$listed" ]; then
        printf 'REFUSED\tthe rows\tthe model prints [ %s] where MODEL_ROWS lists [ %s]\n' "$printed" "$listed"; bad=1
    fi
    for row in "${MODEL_ROWS[@]}"; do
        id="${row%%|*}"; rest="${row#*|}"; section="${rest%%|*}"; rest="${rest#*|}"; where="${rest%%|*}"; anchor="${rest#*|}"
        ans="$(awk -v id="$id" '$1 == "KAT" && $2 == id { print $3 }' "$file")"
        [ -n "$ans" ] || continue   # said above
        src="$MODEL_DIR/kat_code.c"
        if [ "$section" != - ]; then   # that case's body alone
            src="$MODEL_DIR/section.c"
            awk -v f="static void $section(void)" '$0 == f { on = 1 } on { print } on && /^}/ { exit }' "$MODEL_DIR/kat_code.c" > "$src"
            if [ ! -s "$src" ]; then
                printf 'REFUSED\t%s\tdigiid_core_kat_main.c has no case %s\n' "$id" "$section"; bad=1; continue
            fi
        fi
        if [ "$where" = above ]; then hits="$(grep -B1 -F -- "\"$ans\"" "$src")"
        else hits="$(grep -F -- "\"$ans\"" "$src")"; fi
        if [ -z "$hits" ]; then
            printf 'REFUSED\t%s\t%s holds no literal "%s"\n' "$id" "${section/#-/digiid_core_kat_main.c}" "$ans"; bad=1
        elif ! grep -qF -- "$anchor" <<<"$hits"; then
            printf 'REFUSED\t%s\t"%s" is a literal of %s, but not beside %s\n' "$id" "$ans" "${section/#-/digiid_core_kat_main.c}" "$anchor"; bad=1
        fi
    done
    sed -n 's/^GOLDEN //p' "$file" > "$MODEL_DIR/given.txt"
    sed 's/ [^ ]*$//' "$MODEL_DIR/given.txt" > "$MODEL_DIR/given_names.txt"
    while IFS= read -r line; do
        if ! grep -qFx -- "$line" "$MODEL_DIR/golden.txt"; then
            printf 'REFUSED\tGOLDEN %s\tbridge_golden.txt has no line "%s"\n' "${line% *}" "$line"; bad=1
        fi
    done < "$MODEL_DIR/given.txt"
    while IFS= read -r line; do
        name="${line% *}"
        case "$name" in ADDRESS*|SIGN*) ;; *) continue ;; esac
        grep -qFx -- "$name" "$MODEL_DIR/given_names.txt" && continue
        skip=0
        for row in "${MODEL_NOT_GOLDEN[@]}"; do [ "$row" = "$name" ] && skip=1; done
        [ $skip -eq 1 ] && continue
        printf 'REFUSED\tGOLDEN %s\tthe model gives no such line, and MODEL_NOT_GOLDEN does not name it\n' "$name"; bad=1
    done < "$MODEL_DIR/golden.txt"
    return $bad
}
# model_arm <label> <ROOT=KEY> <the id prefix of the KAT lines it must lose> <site|shared|none: the GOLDEN lines it must lose>
model_arm() {
    local label="$1" swap="$2" prefix="$3" kind="$4" out want got
    if ! python3 -I "$MODEL_PY" --hmac-key "$swap" > "$MODEL_DIR/arm.txt" 2> "$MODEL_DIR/err.txt"; then
        echo "  [model $label] GATE FAILURE: the model did not run: $(head -1 "$MODEL_DIR/err.txt")"; FAIL=1; return
    fi
    out="$(model_verdict "$MODEL_DIR/arm.txt")"
    got="$(cut -f2 <<<"$out" | LC_ALL=C sort | tr '\n' '|')"
    want="$( { printf '%s\n' "${MODEL_ROWS[@]%%|*}" | grep -E "^$prefix\\."
               case "$kind" in
                   site) grep -vE "$SHARED_NAME_RE" "$MODEL_DIR/good_names.txt" | sed 's/^/GOLDEN /' ;;
                   shared) grep -E "$SHARED_NAME_RE" "$MODEL_DIR/good_names.txt" | sed 's/^/GOLDEN /' ;;
               esac; } | LC_ALL=C sort | tr '\n' '|')"
    if [ -n "$want" ] && [ "$got" = "$want" ]; then
        echo "  [model $label] refused, as it must be: exactly its $(tr -cd '|' <<<"$want" | wc -c | tr -d ' ') lines, each by name, and no other"
    else
        echo "  [model $label] GATE FAILURE: the comparison did not refuse exactly that root's lines"
        echo "      refused: $got"; echo "      wanted:  $want"; FAIL=1
    fi
}
model_note="FAILED"
echo "--- MODEL: slip13_model.py (standard library only) gives digiid_core_kat_main.c's literals and bridge_golden.txt's lines again ---"
if ! command -v python3 >/dev/null 2>&1; then
    echo "  GATE FAILURE: no python3 to run the model with (slip13_model.py)"; FAIL=1
elif [ ! -f "$MODEL_PY" ] || [ ! -s "$MODEL_DIR/golden.txt" ]; then
    echo "  GATE FAILURE: slip13_model.py or bridge_golden.txt is gone, or the golden file holds no line"; FAIL=1
elif ! python3 -I "$MODEL_PY" > "$MODEL_DIR/model.txt" 2> "$MODEL_DIR/err.txt"; then
    echo "  GATE FAILURE: the model did not run: $(head -1 "$MODEL_DIR/err.txt")"; FAIL=1
elif ! out="$(model_verdict "$MODEL_DIR/model.txt")"; then
    echo "  [model] GATE FAILURE: the model and the literals disagree"
    while IFS=$'\t' read -r _ id why; do echo "      $id: ${why:0:220}"; done <<<"$out"
    FAIL=1
else
    sed -n 's/^GOLDEN //p' "$MODEL_DIR/model.txt" | sed 's/ [^ ]*$//' > "$MODEL_DIR/good_names.txt"
    model_note="${#MODEL_ROWS[@]} literals and $(grep -c '' "$MODEL_DIR/good_names.txt") golden lines given again"
    echo "  [model] ${#MODEL_ROWS[@]} literals of digiid_core_kat_main.c, each on the line named for it, and $(grep -c '' "$MODEL_DIR/good_names.txt") of bridge_golden.txt's lines, are the model's"
    before=$FAIL
    model_arm 'STANDARD derived with "DigiByte seed"' "STANDARD=DigiByte seed" standard none
    model_arm 'DIGIBYTE derived with "Bitcoin seed"' "DIGIBYTE=Bitcoin seed" digibyte site
    model_arm 'the shared identity derived with "Bitcoin seed"' "SHARED=Bitcoin seed" shared shared
    [ $FAIL -ne $before ] && model_note="FAILED"
fi

for arm in "${ARMS[@]}"; do
    bit="${arm%%:*}"; name="${arm#*:}"
    echo "--- COMPARISON ARM -DDIGIID_UNFIXED=$bit ($name): fails exactly its cases ---"
    red_any=0
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; arms_of="${entry##*:}"
        want=pass
        case ",$arms_of," in *",$bit,"*) want=fail ;; esac
        out="$("$BUILD_DIR/red_$bit" "$c" 2>&1)"; rc=$?
        if grep -Eq "$SAN_RE" <<<"$out"; then echo "  [red$bit $c] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" <<<"$out" | head -3; FAIL=1; continue; fi
        if [ "$want" = fail ]; then
            red_any=1
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" <<<"$out"; then
                first="$(grep -m1 '^FAIL:' <<<"$out")"
                echo "  [red$bit $c] failed, as it must: ${first:6:150}"
            else
                echo "  [red$bit $c] GATE FAILURE: this arm did not fail the case -- it cannot see the rule"; FAIL=1
            fi
        elif [ $rc -ne 0 ] || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [red$bit $c] GATE FAILURE: failed in an arm that is not its own"; grep '^FAIL:' <<<"$out" | cut -c1-200 | sed 's/^/      /'; FAIL=1
        fi
    done
    [ $red_any -eq 0 ] && { echo "  [red$bit] GATE FAILURE: no case is listed for this arm; it proves nothing"; FAIL=1; }
done

run_shipped() {   # <binary> <label>
    local entry c out rc
    echo "--- SHIPPED ARM (-DDIGIID_UNFIXED=0, $2): every case passes, no sanitizer report ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"
        out="$("$1" "$c" 2>&1)"; rc=$?
        if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [$2 $c] passed ($(grep -c '^PASS:' <<<"$out") checks)"
        else
            echo "  [$2 $c] GATE FAILURE (rc=$rc)"; grep -E "^FAIL:|$SAN_RE" <<<"$out" | cut -c1-200 | sed 's/^/      /'; FAIL=1
        fi
    done
}
run_shipped "$BUILD_DIR/green" 64-bit
# Once more without the sanitizers: ASan's stack layout can leave a buffer a function forgot to clear
# zero by luck (the NO_ADDRESS checks dirty the stack first, and only an unsanitized build sees it).
if "$CC" -w -std=c99 -include stdint.h -g -O0 -DDIGIID_UNFIXED=0 -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$BRIDGE_DIR" \
       "$SCRIPT_DIR/digiid_core_kat_main.c" "$BUILD_DIR/tu2.c" "$BRIDGE_DIR/slip13.c" "$BUILD_DIR"/core_plain/*.o -lm -lpthread \
       -o "$BUILD_DIR/green_plain"; then
    run_shipped "$BUILD_DIR/green_plain" "64-bit unsanitized"
else
    echo "GATE FAILURE: the unsanitized shipped arm did not compile (a build error is not evidence)."; FAIL=1
fi

# ---- the bridge it moved from --------------------------------------------------------------
BRIDGE_CORE=(BRWallet.c BRTransaction.c BRMerkleBlock.c BRWalletFilterElements.c BRDigiDollar.c BRDigiAsset.c BRSet.c)
BRIDGE_WALLET_C="$BRIDGE_DIR/jni_wallet.c"   # the GOLDEN leg's comparison arm builds once with a planted copy
build_bridge() {   # <out> <core objects dir> [extra flags]
    local out="$1" objs="$2" src; shift 2
    local extra=()
    for src in "${BRIDGE_CORE[@]}"; do extra+=("$CORE_DIR/$src"); done
    "$CC" -w -include stdint.h "$@" -I "$HOST_JNI" -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/digiid_core_bridge_main.c" "$BRIDGE_DIR/jni_wallet_sign.c" "$BRIDGE_DIR/jni_transaction_persist.c" \
        "$BRIDGE_WALLET_C" "$BRIDGE_DIR/coin_reservation.c" "$BRIDGE_DIR/slip13.c" "${extra[@]}" "$objs"/*.o \
        -lm -lpthread -o "$out"
}
run_bridge() {   # <binary> <label>
    local out rc
    echo "--- BRIDGE ($2): the bridge's signMessage, signIdentityMessage and identityAddress against the header ---"
    out="$(timeout 300 "$1" sweep 2>&1)"; rc=$?
    printf '%s\n' "$out" > "$BUILD_DIR/sweep_$2.txt"   # its SWEEP lines are the GOLDEN leg's
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT bridge pass$" <<<"$out"; then
        echo "  [$2] $(grep -E '^PASS: [0-9]+ answers compared' <<<"$out" | cut -c7-)"
        echo "  [$2] $(grep -c '^PASS: a [0-9]*-byte message' <<<"$out") long-message answers the bridge's"
    else
        echo "  [$2] GATE FAILURE (rc=$rc)"; grep -E "^FAIL:|$SAN_RE" <<<"$out" | cut -c1-240 | sed 's/^/      /'; FAIL=1
    fi
}

# ---- GOLDEN: the bridge held to the answers it gave in 4.1.5 -------------------------------
# bridge_lines <binary> <its sweep's output> <out file>: the bridge's ADDRESS and SIGN lines (print) and SWEEP lines
# (sweep), in bridge_golden.txt's order. A sweep that disagreed with the header still gave its lines.
bridge_lines() {
    local out rc
    out="$(timeout 300 "$1" print 2>&1)"; rc=$?
    if [ $rc -ne 0 ] || grep -Eq "$SAN_RE" <<<"$out"; then echo "print did not run clean (rc=$rc)"; return 1; fi
    if [ ! -f "$2" ] || grep -Eq "$SAN_RE" "$2"; then echo "the sweep did not run clean"; return 1; fi
    grep -E '^(ADDRESS|SIGN) ' <<<"$out" > "$3"
    grep -E '^SWEEP ' "$2" >> "$3"
    return 0
}
# golden_verdict <the bridge's lines> [<a golden file's lines>]: one "DIFFERS<tab><the line but its last field><tab><why>"
# line for each line that is not the same in both; returns 0 when the two are the same bytes.
golden_verdict() {
    local got="$1" golden="${2:-$MODEL_DIR/golden.txt}" line name other bad=0
    cmp -s "$golden" "$got" && return 0
    while IFS= read -r line; do
        grep -qFx -- "$line" "$got" && continue
        name="${line% *}"
        other="$(awk -v n="$name " 'substr($0, 1, length(n)) == n && index(substr($0, length(n) + 1), " ") == 0 { print; exit }' "$got")"
        if [ -n "$other" ]; then other="${other##* }"; else other="no such line"; fi
        printf 'DIFFERS\t%s\tfrozen as %s, the bridge answers %s\n' "$name" "${line##* }" "$other"; bad=1
    done < "$golden"
    while IFS= read -r line; do
        name="${line% *}"
        other="$(awk -v n="$name " 'substr($0, 1, length(n)) == n && index(substr($0, length(n) + 1), " ") == 0 { print; exit }' "$golden")"
        [ -n "$other" ] && continue
        printf 'DIFFERS\t%s\tthe bridge answers %s, and the file holds no such line\n' "$name" "${line##* }"; bad=1
    done < "$got"
    [ $bad -eq 0 ] && printf 'DIFFERS\tthe order\tthe same lines in another order\n'
    return 1
}
golden_note="FAILED"
run_golden() {   # <binary> <label>; returns 0 when the bridge's lines are the file's
    local lines="$BUILD_DIR/golden_$2.txt" out
    echo "--- GOLDEN ($2): the bridge's answers against bridge_golden.txt, frozen from the bridge of 4.1.5 ---"
    if [ "$(grep -c '^ADDRESS ' "$MODEL_DIR/golden.txt")" -lt 1 ] || [ "$(grep -c '^SIGN ' "$MODEL_DIR/golden.txt")" -lt 1 ] ||
       [ "$(grep -c '^SWEEP ' "$MODEL_DIR/golden.txt")" -lt 1 ]; then
        echo "  [$2] GATE FAILURE: bridge_golden.txt is gone, or holds no ADDRESS, SIGN or SWEEP line: nothing holds the bridge"; FAIL=1; return 1
    fi
    if ! out="$(bridge_lines "$1" "$BUILD_DIR/sweep_$2.txt" "$lines")"; then
        echo "  [$2] GATE FAILURE: $out"; FAIL=1; return 1
    fi
    if out="$(golden_verdict "$lines")"; then
        echo "  [$2] $(grep -c '' "$lines") lines the same, byte for byte: $(grep -c '^ADDRESS ' "$lines") addresses, $(grep -c '^SIGN ' "$lines") signed answers, $(grep -c '^SWEEP ' "$lines") sweeps"
        return 0
    fi
    echo "  [$2] GATE FAILURE: the bridge no longer answers as it did in 4.1.5. An identity moved, or a signature changed:"
    while IFS=$'\t' read -r _ name why; do echo "      $name: ${why:0:260}"; done <<<"$out"
    FAIL=1; return 1
}
# expect_golden <label> <the names that must differ, one per line> <the bridge's lines> [<a golden file's lines>]
expect_golden() {
    local label="$1" want got out
    want="$(LC_ALL=C sort <<<"$2" | tr '\n' '|')"
    out="$(golden_verdict "$3" ${4:+"$4"})"
    got="$(cut -f2 <<<"$out" | LC_ALL=C sort | tr '\n' '|')"
    if [ -n "$2" ] && [ "$got" = "$want" ]; then
        echo "  [golden $label] refused, as it must be: exactly its $(tr -cd '|' <<<"$want" | wc -c | tr -d ' ') lines, each by name, and no other"
    else
        echo "  [golden $label] GATE FAILURE: the comparison did not refuse exactly those lines"
        echo "      refused: $got"; echo "      wanted:  $want"; FAIL=1
    fi
}
if build_bridge "$BUILD_DIR/bridge" "$BUILD_DIR/core_san" "${SAN[@]}"; then
    run_bridge "$BUILD_DIR/bridge" 64-bit
    if run_golden "$BUILD_DIR/bridge" 64-bit; then
        golden_note="$(grep -c '' "$MODEL_DIR/golden.txt") lines the same"
        # COMPARISON ARMS. In temp copies of the file: one answer changed, and one line missing.
        before=$FAIL
        GOLDEN_ARM="$BUILD_DIR/golden_arm"
        mkdir -p "$GOLDEN_ARM"
        sed '/^SIGN site request /s/DPeEMYki/DPeEMYkj/' "$MODEL_DIR/golden.txt" > "$GOLDEN_ARM/changed.txt"
        grep -v '^SWEEP long messages: ' "$MODEL_DIR/golden.txt" > "$GOLDEN_ARM/missing.txt"
        if [ "$(grep -c 'DPeEMYkj' "$GOLDEN_ARM/changed.txt")" -ne 1 ] ||
           [ "$(grep -c '' "$GOLDEN_ARM/missing.txt")" -ne $(( $(grep -c '' "$MODEL_DIR/golden.txt") - 1 )) ]; then
            echo "  [golden] GATE FAILURE: a plant found no anchor in bridge_golden.txt -- the comparison arm is blind"; FAIL=1
        else
            expect_golden "one answer changed in the file" "SIGN site request" "$BUILD_DIR/golden_64-bit.txt" "$GOLDEN_ARM/changed.txt"
            expect_golden "one line missing from the file" "SWEEP long messages: 16 answers, sha256" "$BUILD_DIR/golden_64-bit.txt" "$GOLDEN_ARM/missing.txt"
        fi
        # And a bridge that moved: the per-site path begins m/14' in a temp copy of jni_wallet.c. Every per-site
        # line and every SWEEP line (per-site answers are in each) must be refused; the shared identity's must hold.
        sed 's/13 | BIP32_HARD,/14 | BIP32_HARD,/' "$BRIDGE_DIR/jni_wallet.c" > "$GOLDEN_ARM/jni_wallet.c"
        if [ "$(grep -c '13 | BIP32_HARD,' "$BRIDGE_DIR/jni_wallet.c")" -ne 1 ] || [ "$(grep -c '14 | BIP32_HARD,' "$GOLDEN_ARM/jni_wallet.c")" -ne 1 ]; then
            echo "  [golden] GATE FAILURE: the plant found no anchor in jni_wallet.c (seed_derive_identity_key's 13') -- the comparison arm is blind"; FAIL=1
        elif ! BRIDGE_WALLET_C="$GOLDEN_ARM/jni_wallet.c" build_bridge "$GOLDEN_ARM/bridge" "$BUILD_DIR/core_san" "${SAN[@]}"; then
            echo "GATE FAILURE: the moved bridge did not compile (a build error is not evidence)."; FAIL=1
        else
            timeout 300 "$GOLDEN_ARM/bridge" sweep > "$GOLDEN_ARM/sweep.txt" 2>&1
            if ! out="$(bridge_lines "$GOLDEN_ARM/bridge" "$GOLDEN_ARM/sweep.txt" "$GOLDEN_ARM/lines.txt")"; then
                echo "  [golden a bridge that moved] GATE FAILURE: $out"; FAIL=1
            else
                expect_golden "a bridge whose per-site path begins m/14'" \
                    "$(sed 's/ [^ ]*$//' "$MODEL_DIR/golden.txt" | grep -vE "$SHARED_NAME_RE")" "$GOLDEN_ARM/lines.txt"
            fi
        fi
        [ $FAIL -ne $before ] && golden_note="FAILED"
    fi
else
    echo "GATE FAILURE: the bridge comparison did not compile (a build error is not evidence)."; FAIL=1
fi

# ---- 32-bit --------------------------------------------------------------------------------
M32=(-m32 -g -fsanitize=address -fno-omit-frame-pointer)
bits_note="skipped: no 32-bit link here"
printf 'int main(void){return 0;}\n' > "$BUILD_DIR/probe32.c"
if "$CC" "${M32[@]}" "$BUILD_DIR/probe32.c" -o "$BUILD_DIR/probe32" >/dev/null 2>&1 && "$BUILD_DIR/probe32"; then
    bits_note="shipped arm and bridge comparison pass"
    if core_objs "$BUILD_DIR/core_32" "${M32[@]}" &&
       "$CC" -w -std=c99 -include stdint.h "${M32[@]}" -DDIGIID_UNFIXED=0 -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$BRIDGE_DIR" \
           "$SCRIPT_DIR/digiid_core_kat_main.c" "$BUILD_DIR/tu2.c" "$BRIDGE_DIR/slip13.c" "$BUILD_DIR"/core_32/*.o -lm -lpthread -o "$BUILD_DIR/green32" &&
       build_bridge "$BUILD_DIR/bridge32" "$BUILD_DIR/core_32" "${M32[@]}"; then
        # The note is the 32-bit legs' own result: FAIL may already be 1 from an earlier leg, so they are
        # counted from 0 and the earlier result is put back after.
        before=$FAIL; FAIL=0
        run_shipped "$BUILD_DIR/green32" 32-bit
        run_bridge "$BUILD_DIR/bridge32" 32-bit
        run_golden "$BUILD_DIR/bridge32" 32-bit || golden_note="FAILED"
        [ $FAIL -ne 0 ] && bits_note="FAILED"
        [ $before -ne 0 ] && FAIL=1
    else
        echo "GATE FAILURE: the 32-bit build failed though a 32-bit link works (a build error is not evidence)."; FAIL=1; bits_note="FAILED"
    fi
fi
echo "32-bit: $bits_note"

# ---- LEAK CHECK ----------------------------------------------------------------------------
leak_note="skipped: this platform's AddressSanitizer has no LeakSanitizer"
leak_probe="$(ASAN_OPTIONS="$ASAN_LEAK" "$BUILD_DIR/green" list 2>&1)"
if ! grep -q "detect_leaks is not supported" <<<"$leak_probe"; then
    leak_note="every case and the bridge comparison clean"
    echo "--- LEAK CHECK: every case on the shipped arm, and the bridge comparison, under detect_leaks=1 ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"
        out="$(ASAN_OPTIONS="$ASAN_LEAK" "$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
        if [ $rc -ne 0 ] || grep -Eq "$SAN_RE" <<<"$out" || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [leak $c] GATE FAILURE (rc=$rc)"; grep -E "$SAN_RE|^FAIL:" <<<"$out" | head -6 | sed 's/^/      /'; FAIL=1
            leak_note="FAILED"
        fi
    done
    out="$(ASAN_OPTIONS="$ASAN_LEAK" timeout 300 "$BUILD_DIR/bridge" sweep 2>&1)"; rc=$?
    if [ $rc -ne 0 ] || grep -Eq "$SAN_RE" <<<"$out" || ! grep -q "^RESULT bridge pass$" <<<"$out"; then
        echo "  [leak bridge] GATE FAILURE (rc=$rc)"; grep -E "$SAN_RE|^FAIL:" <<<"$out" | head -6 | sed 's/^/      /'; FAIL=1
        leak_note="FAILED"
    fi
fi
echo "leak check: $leak_note"

[ $FAIL -eq 0 ] && echo "PASS: digiid_core_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan and unsanitized; the bridge's own answers matched; 32-bit $bits_note; leak check $leak_note; one DigiScope list in BRDigiId.h and IdentityKeyPolicy.kt; the independent model: $model_note; bridge_golden.txt: $golden_note; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: digiid_core_kat"
exit $FAIL
