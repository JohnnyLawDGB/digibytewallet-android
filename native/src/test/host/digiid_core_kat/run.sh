#!/usr/bin/env bash
# Host KAT runner for BRDigiId.h: the Digi-ID per-site identity (SLIP-0013) and login signing, moved from the
# Android bridge (bridge/slip13.c; seed_derive_identity_key in jni_wallet.c; sign_with_key, base64_encode,
# write_varint, message_utf8, signMessage, signIdentityMessage and identityAddress in jni_wallet_sign.c). The
# assertions of slip13_identity_kat and identity_sign_bytes_kat, the derivation inputs of DigiIdRequestTest
# and the request of DigiIdIdentityChoiceTest run here, with SLIP-0013's published vector, beside a
# line-for-line C model of the bridge that the header is compared with over fixed pseudo-random inputs. See
# digiid_core_kat_main.c. Then the REAL bridge, booted over a host JNI, is held to the header answer for
# answer (digiid_core_bridge_main.c).
#
#   comparison  one arm per rule, -DDIGIID_UNFIXED=<bit> (BRDigiId.h): 1 the account index is hashed
#               big-endian; 2 A..D are read big-endian; 4 A..D are not hardened; 8 a negative index is
#               taken as its uint32 bits; 16 the shared identity's address is given for any index; 32 a
#               message over 0xffff bytes gets the standard compact size; 64 the message is hashed once;
#               128 the key is not marked compressed; 256 address format 2 answers P2PKH; 512 the caller's
#               key is left unwiped after signing; 1024 a lone surrogate is written as three bytes; 2048
#               modified UTF-8; 4096 the shared identity is m/0'/1/0; 8192 unpadded base64; 16384 the URI's
#               surrogate pairs as two three-byte halves (the JNI specification's form, which ART does not
#               write); 32768 the URI written by the message's rules (U+0000 one byte, a lone surrogate '?');
#               65536 BRDigiIdAddressWithKey leaves the caller's key unwiped. Each arm fails exactly the cases
#               CASES lists for its bit and passes every other.
#   shipped     -DDIGIID_UNFIXED=0: every case passes, exit 0.
#   bridge      the shipped header against the bridge's own jni_wallet_sign.c / jni_wallet.c / slip13.c,
#               over identity_sign_bytes_kat's host_jni stand-ins: every answer and refusal the same. Its
#               host env's GetStringUTFChars writes what ART's does (a surrogate pair as four bytes), and the
#               header gets the URI through BRDigiIdUriBytesFromUtf16.
# All under ASan + UBSan, 64-bit; the shipped arm and the bridge comparison again 32-bit where a 32-bit link
# works (said so, and skipped, where it does not: no 32-bit link on an arm64 Mac). The arms run with
# detect_leaks=0; then every case runs once more on the shipped arm under LeakSanitizer where the platform
# has one (said so, and skipped, where it does not -- Apple clang on macOS).
#
# Gates first: the header must test the seam's VALUE (#if, never #ifdef) with an #ifndef default.
# Then:
#   PORTABILITY GATE  the header includes only libc and the three core headers whose functions it calls,
#                     reads no clock, does no I/O, allocates nothing and uses no rand(); builds clean under
#                     -Wall -Wextra -Wpedantic -Werror at c99/c11/c17 (two TUs, the header included twice in
#                     the second); for iphoneos/iphonesimulator, and through Swift's C importer (swiftc
#                     -import-objc-header) on macOS, where an Xcode SDK / swiftc is present (skipped, and
#                     said so, elsewhere).
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

# <case>:<the comparison arms (bits of DIGIID_UNFIXED) that must fail it, comma-separated; - for none>
CASES=(
    slip13_published_vectors:2
    slip13_identity_carried:2,4
    slip13_index_values:1,2
    slip13_edges:1,2
    site_key_derivation:1,2,4,8
    shared_key_derivation:4096
    message_prefix:32
    message_digest:64
    utf8_bytes:1024,2048
    uri_bytes:16384,32768
    uri_identity:2,4,64,8192,16384,32768
    sign_shared_login:64,256,4096,8192
    sign_site_request:2,4,8,64,2048,4096,8192
    request_golden_inputs:2,4
    address_rules:1,2,4,8,16,4096
    sign_with_key_wipes:128,512
    address_with_key_wipes:128,65536
    long_messages:2,4,32,64,4096,8192
    edges:1,2,4,8,16,64,256,4096,8192
    buffer_edges:512
    sweep_indexes:1,2
    sweep_keys:1,2,4,8,4096
    sweep_sign:1,2,4,8,16,64,256,4096,8192
    sweep_utf8:1024,2048
    sweep_uri_bytes:16384,32768
)
ARMS=(1:index-big-endian 2:words-big-endian 4:soft-path 8:negative-index 16:shared-any-index 32:compact-size
      64:single-sha 128:uncompressed 256:segwit-ignored 512:key-kept 1024:lone-surrogate-kept 2048:modified-utf8
      4096:shared-path 8192:base64-unpadded 16384:uri-spec-pairs 32768:uri-as-message 65536:address-key-kept)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before Digi-ID moved into the core."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRDigiIdSlip13Indexes BRDigiIdSiteKey BRDigiIdSharedKey BRDigiIdMessagePrefix BRDigiIdMessageDigest BRDigiIdUtf8FromUtf16 \
          BRDigiIdUriBytesFromUtf16 BRDigiIdAddressWithKey BRDigiIdSignWithKey BRDigiIdSignShared BRDigiIdSignSite BRDigiIdAddress; do
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
want='"BRBIP32Sequence.h" "BRCrypto.h" "BRKey.h" <stddef.h> <stdint.h> <string.h> '
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
    int ok;
    BRBIP39DeriveKey(seed, "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", NULL);
    ok = BRDigiIdAddress(addr, sizeof(addr), seed, sizeof(seed), 0, NULL, 0, 0, work, sizeof(work)) == BR_DIGIID_OK &&
         strcmp(addr, "DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn") == 0 &&
         BRDigiIdSignSite(out, sizeof(out), seed, sizeof(seed), (const uint8_t *)"m", 1, "https://example.com/callback", 28, 0, work,
                          sizeof(work)) == BR_DIGIID_OK && strncmp(out, "DPeEMYki92jeUSDgjkrfrTMVpvGWCHYd68|", 35) == 0 &&
         BRDigiIdUtf8FromUtf16(bytes, sizeof(bytes), &len, units, 3) == BR_DIGIID_OK && len == 5;
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
let signed: BRDigiIdStatus = BRDigiIdSignSite(&out, out.count, seed, seed.count, message, length, uriBytes, uriLength, 0,
                                              &work, workCount)
let shared: BRDigiIdStatus = BRDigiIdSignShared(&out, out.count, seed, seed.count, message, length, 2, &work, workCount)
let address: BRDigiIdStatus = BRDigiIdAddress(&addr, addr.count, seed, seed.count, 0, nil, 0, 0, &work, workCount)
var idx = [UInt32](repeating: 0, count: 4)
let indexes: BRDigiIdStatus = BRDigiIdSlip13Indexes(&idx, uriBytes, uriLength, 0, &work, workCount)
var key = BRKey()
let siteKey: BRDigiIdStatus = BRDigiIdSiteKey(&key, seed, seed.count, uriBytes, uriLength, 0, &work, workCount)
let keyAddress: BRDigiIdStatus = BRDigiIdAddressWithKey(&addr, addr.count, &key)
print(uriStatus == BR_DIGIID_OK, signed == BR_DIGIID_OK, shared == BR_DIGIID_OK, address == BR_DIGIID_OK, indexes == BR_DIGIID_OK,
      utf8 == BR_DIGIID_OK, siteKey == BR_DIGIID_OK, keyAddress == BR_DIGIID_OK, DIGIID_UNFIXED, BR_DIGIID_MESSAGE_HEADER_LENGTH)
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
[ $strict_ok -eq 1 ] && echo "portability gate: libc + three core headers, no clock/I/O/allocation/rand; clean at c99/c11/c17 ($ios_note; $swift_note)"

# ---- the arms ------------------------------------------------------------------------------
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRDigiId.h"
#include "BRDigiId.h"
int digiid_core_kat_tu2(void);
int digiid_core_kat_tu2(void)
{
    uint32_t idx[4];
    uint8_t work[64];
    return BRDigiIdSlip13Indexes(idx, "http://bitid.bitcoin.blue/callback", 34, 0, work, sizeof(work)) == BR_DIGIID_OK && idx[0] == 0xbe553112u &&
           BRDigiIdDigestWorkSize(2) == 29;
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
build_bridge() {   # <out> <core objects dir> [extra flags]
    local out="$1" objs="$2" src; shift 2
    local extra=()
    for src in "${BRIDGE_CORE[@]}"; do extra+=("$CORE_DIR/$src"); done
    "$CC" -w -include stdint.h "$@" -I "$HOST_JNI" -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/digiid_core_bridge_main.c" "$BRIDGE_DIR/jni_wallet_sign.c" "$BRIDGE_DIR/jni_transaction_persist.c" \
        "$BRIDGE_DIR/jni_wallet.c" "$BRIDGE_DIR/coin_reservation.c" "$BRIDGE_DIR/slip13.c" "${extra[@]}" "$objs"/*.o \
        -lm -lpthread -o "$out"
}
run_bridge() {   # <binary> <label>
    local out rc
    echo "--- BRIDGE ($2): the bridge's signMessage, signIdentityMessage and identityAddress against the header ---"
    out="$(timeout 300 "$1" sweep 2>&1)"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT bridge pass$" <<<"$out"; then
        echo "  [$2] $(grep -E '^PASS: [0-9]+ answers compared' <<<"$out" | cut -c7-)"
        echo "  [$2] $(grep -c '^PASS: a [0-9]*-byte message' <<<"$out") long-message answers the bridge's"
    else
        echo "  [$2] GATE FAILURE (rc=$rc)"; grep -E "^FAIL:|$SAN_RE" <<<"$out" | cut -c1-240 | sed 's/^/      /'; FAIL=1
    fi
}
if build_bridge "$BUILD_DIR/bridge" "$BUILD_DIR/core_san" "${SAN[@]}"; then
    run_bridge "$BUILD_DIR/bridge" 64-bit
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
        before=$FAIL
        run_shipped "$BUILD_DIR/green32" 32-bit
        run_bridge "$BUILD_DIR/bridge32" 32-bit
        [ $FAIL -ne $before ] && bits_note="FAILED"
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

[ $FAIL -eq 0 ] && echo "PASS: digiid_core_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan and unsanitized; the bridge's own answers matched; 32-bit $bits_note; leak check $leak_note; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: digiid_core_kat"
exit $FAIL
