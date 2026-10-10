#!/usr/bin/env bash
# The release workflow's downloads-page rewrite must handle a FOUR-part version.
#
# "Deploy to VPS" in .github/workflows/release.yml rewrites five anchored elements of
# /var/www/digiscope-downloads/index.html with sed, all driven by one regex, VER_RE. A patch
# release of a final version carries a fourth part (v4.0.87.1), and three parts was enough to
# publish such a tag -- the page read "v4.0.87" and the whole tag replaced it -- but not to
# publish the one AFTER it. VER_RE then stopped at "v4.0.87" inside the page's "v4.0.87.1", so
# the version element became "v4.0.87.2.1" while the anchored filename pattern matched nothing
# at all: the download button would have stayed on the old APK beside the new SHA-256. The
# workflow's own comments call that the worst of its three historical failures, and it is not
# observable until a tag is pushed, which is the one moment it cannot be tried out.
#
# So this runs the workflow's REAL commands -- VER_RE and every sed line are extracted from
# release.yml, not copied here -- over a fixture in the shape of the live page, for each
# version-shape transition the line goes through.
#
# ARMS:
#   green  the regex as release.yml carries it: every case MUST pass
#   red    RELEASE_PAGE_TEST_THREE_PART=1 substitutes the earlier three-part regex:
#          the four-to-four case MUST fail, or this test is not watching anything
#
# Usage: scripts/test-helpers/release-page-version-test.sh      (exit 0 = pass)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/release.yml"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

THREE_PART_RE='v[0-9]\+\.[0-9]\+\.[0-9]\+\(-[A-Za-z0-9.]\+\)*'
OLD_HASH="f57ee3808dfc0117830d0c8d2d951d538c108690f193aaae89fbbac485b256e9"
NEW_HASH="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

fail=0
note() { echo "  $*"; }

# ---- the real regex and the real commands -------------------------------------
VER_RE="$(sed -n "s/^[[:space:]]*VER_RE='\(.*\)'[[:space:]]*$/\1/p" "$WORKFLOW")"
if [ "$(printf '%s\n' "$VER_RE" | grep -c .)" -ne 1 ]; then
    echo "GATE FAILURE: expected exactly one VER_RE assignment in $WORKFLOW"; exit 1
fi
mapfile -t SEDS < <(sed -n 's/^[[:space:]]*\(sed -i .*index\.html\)[[:space:]]*$/\1/p' "$WORKFLOW")
if [ "${#SEDS[@]}" -ne 6 ]; then
    echo "GATE FAILURE: expected 6 'sed -i ... index.html' lines in $WORKFLOW, found ${#SEDS[@]}"
    echo "              (5 version elements + the SHA-256 line; the rewrite block changed shape)"
    exit 1
fi
if [ "${RELEASE_PAGE_TEST_THREE_PART:-0}" = "1" ]; then
    VER_RE="$THREE_PART_RE"
    echo "RED ARM: VER_RE forced to the earlier three-part regex"
fi
echo "VER_RE: $VER_RE"
echo "sed lines: ${#SEDS[@]} (from $WORKFLOW)"
echo

# The elements the workflow's patterns are anchored to, as the live page carries them
# (digiscope.me/downloads, fetched 2026-10-10).
write_page() {   # $1 = the version the page currently advertises
    local v="$1"
    cat > "$WORK/index.html" <<PAGE
<!doctype html><html><body>
    <div class="ver">${v} &mdash; August 2026</div>
    <a class="btn" href="digibyte-wallet-${v}.apk">Download APK (${v})</a>
    <div class="hash">SHA-256: <code>${OLD_HASH}</code></div>
    <h2>What's New in ${v}</h2>
    <a href="https://github.com/JohnnyLawDGB/digibytewallet-android/releases/tag/${v}">this release on GitHub</a>
    <p class="p">Upgrading from v3.4.0 keeps your wallet.</p>
    <p class="p">To verify a download: <code>sha256sum digibyte-wallet-${v}.apk</code> and compare it with the checksum above.</p>
</body></html>
PAGE
}

# one case: <label> <page version> <tag>
run_case() {
    local label="$1" from="$2" tag="$3"
    local filename="digibyte-wallet-${tag}.apk"
    local bad=0

    write_page "$from"
    ( cd "$WORK" && TAG="$tag" FILENAME="$filename" HASH="$NEW_HASH" VER_RE="$VER_RE" \
      bash -c "$(printf '%s\n' "${SEDS[@]}")" ) || { echo "  [$label] GATE FAILURE: a sed command errored"; fail=1; return; }

    local page; page="$(cat "$WORK/index.html")"

    # Every element now names the new tag...
    local want=(
        "<div class=\"ver\">${tag} &mdash;"
        "href=\"${filename}\""
        "Download APK (${tag})"
        "<code>${NEW_HASH}</code>"
        "What's New in ${tag}"
        "/releases/tag/${tag}\""
        "sha256sum ${filename}"
    )
    local w
    for w in "${want[@]}"; do
        grep -qF -- "$w" <<<"$page" || { note "[$label] missing: $w"; bad=1; }
    done

    # ...and no WHOLE version token other than the tag survives, apart from the deliberate
    # body-text mention the workflow's anchoring exists to protect. Whole tokens, not
    # substrings: after v4.0.87 -> v4.0.87.1 the page of course still contains "v4.0.87",
    # as a prefix of the new tag, and that is correct.
    local stray
    stray="$(grep -oE 'v[0-9]+(\.[0-9]+)+(-[A-Za-z0-9.]+)*' <<<"$page" | sort -u | grep -vxF "$tag" | grep -vxF 'v3.4.0')"
    if [ -n "$stray" ]; then
        note "[$label] stray version string(s): $(tr '\n' ' ' <<<"$stray")"
        bad=1
    fi

    if [ "$bad" -eq 0 ]; then
        echo "  [$label] ok: $from -> $tag"
    else
        echo "  [$label] FAIL: $from -> $tag"
        fail=1
    fi
    return "$bad"
}

echo "--- the version shapes this line goes through ---"
run_case three_to_four    v4.0.87        v4.0.87.1 ; R1=$?
run_case four_to_four     v4.0.87.1      v4.0.87.2 ; R2=$?
run_case four_to_three    v4.0.87.2      v4.0.88   ; R3=$?
run_case three_to_three   v4.0.86        v4.0.87   ; R4=$?
run_case mangled_cleanup  v4.0.32-beta-beta v4.0.33 ; R5=$?

echo
if [ "${RELEASE_PAGE_TEST_THREE_PART:-0}" = "1" ]; then
    # The red arm's whole point: the four-part transition must be the one that breaks.
    if [ "$R2" -ne 0 ]; then
        echo "PASS: red arm -- the three-part regex fails four_to_four, as it must"
        exit 0
    fi
    echo "GATE FAILURE: red arm -- four_to_four passed with the three-part regex, so this test"
    echo "              does not watch the defect it was written for."
    exit 1
fi

if [ "$fail" -eq 0 ]; then
    echo "PASS: release-page-version-test (5 transitions, the workflow's own VER_RE and sed lines)"
    exit 0
fi
echo "FAIL: release-page-version-test"
exit 1
