#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
clang -w -include stdint.h -I "$CORE_DIR" \
  "$SCRIPT_DIR/cf_checkpoint_lookup_kat_main.c" \
  -o "$BUILD_DIR/cf_checkpoint_lookup_kat"
"$BUILD_DIR/cf_checkpoint_lookup_kat"
