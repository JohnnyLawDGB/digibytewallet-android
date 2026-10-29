#!/usr/bin/env bash
# Host KAT runner for BRPaymentUri.h -- the digibyte: payment URI (untrusted input from QR
# codes and deep links), carrying Android's DigiByteUriTest.kt case for case.
#
# Header-only: includes BRPaymentUri.h (and BRAmountParse.h) and links nothing but libc.
#
#   RED    -DPAYMENT_URI_UNFIXED lets the last duplicate key win and degrades malformed asset
#          requests to plain payments (fail open). Every RED case MUST fail; every GUARD case
#          pass.
#   GREEN  the shipped rule: every case passes, ALL PASS, exit 0. Both arms under ASan + UBSan.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

RED_CASES=(asset_without_valid_quantity_rejected non_positive_quantity_rejected
           quantity_without_asset_rejected duplicate_key_first_wins encoded_key_is_the_same_key
           duplicate_asset_quantity_first_wins blank_asset_id_is_no_asset asset_quantity_integer_rules)
GUARD_CASES=(bare_address amount_dgb_to_sats amount_eight_decimals_exact encode_exact_and_round_trip
             one_satoshi_plain_decimal unrepresentable_amount_dropped label_message_percent_decoded
             foreign_scheme_rejected blank_rejected asset_request_parsed asset_request_round_trip
             plain_request_has_no_asset_fields percent_needs_two_hex_digits percent_escape_is_ascii_hex_only
             utf8_outside_bmp_survives decoded_nul_is_kept_by_length bounded_and_nul_free_input query_shapes
             scheme_case_and_slashes_as_before encode_bounds)

build() {
    local out="$1"; shift
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/payment_uri_kat_main.c" -o "$out"
}

if ! grep -q "PAYMENT_URI_UNFIXED" "$CORE_DIR/BRPaymentUri.h" 2>/dev/null; then
    echo "GATE FAILURE: $CORE_DIR/BRPaymentUri.h missing or has no RED seam; -D would be inert."
    exit 1
fi

FAIL=0
build "$BUILD_DIR/red" -DPAYMENT_URI_UNFIXED || { echo "GATE FAILURE: comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

echo "--- COMPARISON ARM (-DPAYMENT_URI_UNFIXED): every RED case MUST fail, every GUARD case pass ---"
out="$("$BUILD_DIR/red" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [red] GATE FAILURE: sanitizer report"; FAIL=1; }
[ $rc -eq 0 ] && { echo "  [red] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
for c in "${RED_CASES[@]}"; do
    if <<<"$out" grep -q "^RESULT $c fail$"; then echo "  [red $c] failed, as it must"
    else echo "  [red $c] GATE FAILURE: did not fail in the comparison arm"; FAIL=1; fi
done
for c in "${GUARD_CASES[@]}"; do
    if <<<"$out" grep -q "^RESULT $c pass$"; then echo "  [red $c] GUARD held"
    else echo "  [red $c] GATE FAILURE: GUARD case did not hold"; <<<"$out" grep "^FAIL: $c" | sed 's/^/      /'; FAIL=1; fi
done

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; FAIL=1; }
if [ $rc -ne 0 ] || ! <<<"$out" grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; <<<"$out" grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
    <<<"$out" grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; }
done

[ $FAIL -eq 0 ] && echo "payment_uri_kat: GATE HELD (RED let duplicates override and failed open; GREEN failed closed)"
exit $FAIL
