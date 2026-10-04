#!/usr/bin/env python3
"""
Generates native/src/main/jni/bridge/jni_registry_gen.h — the RegisterNatives table that binds
NativeBridge's `external fun`s to their C bodies.

Why a table at all: JNI's default linkage finds a native method by an exported symbol whose name
spells out the Kotlin package (Java_io_digibyte_core_bridge_NativeBridge_foo). That ties the C
library to one package name forever and exports 131 symbols past -fvisibility=hidden. With
RegisterNatives the C names are package-free (NativeBridge_foo), nothing is exported but
JNI_OnLoad, and the class path lives in one overridable header (jni_class_paths.h).

What this script guarantees, so the table cannot drift from the code:
  - every `external fun` in core's NativeBridge.kt has exactly one C body;
  - its JNI descriptor, derived from the Kotlin types, agrees with the C parameter and return
    types, one by one;
  - C bodies the main class does not declare are allowed only if the androidTest copy of
    NativeBridge declares them (and then they must agree with it the same way);
  - the committed header is byte-identical to what this script produces (--check, run in CI).

Usage: scripts/gen-jni-registry.py           regenerate the header
       scripts/gen-jni-registry.py --check   exit 1 if the header is stale or anything disagrees
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BRIDGE = os.path.join(ROOT, "native/src/main/jni/bridge")
OUT = os.path.join(BRIDGE, "jni_registry_gen.h")
MAIN_KT = os.path.join(ROOT, "core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt")
TEST_KT = os.path.join(ROOT, "native/src/androidTest/java/io/digibyte/core/bridge/NativeBridge.kt")

# The callback interface is the one non-java/lang object type in any signature; its descriptor
# comes from the same header as the class path, so a package move is one edit.
CALLBACK_SIG = "DGB_JNI_NATIVECALLBACK_SIG"

# Kotlin type -> (JNI descriptor piece, C type)
TYPES = {
    "Unit": ("V", "void"),
    "Boolean": ("Z", "jboolean"),
    "Int": ("I", "jint"),
    "Long": ("J", "jlong"),
    "Float": ("F", "jfloat"),
    "Double": ("D", "jdouble"),
    "String": ("Ljava/lang/String;", "jstring"),
    "ByteArray": ("[B", "jbyteArray"),
    "IntArray": ("[I", "jintArray"),
    "LongArray": ("[J", "jlongArray"),
    "Array<String>": ("[Ljava/lang/String;", "jobjectArray"),
    "NativeCallback": (None, "jobject"),  # descriptor = CALLBACK_SIG macro
}

errors = []


def fail(msg):
    errors.append(msg)


def strip_kotlin_comments(src):
    """Removes // and /* */ comments without touching string literals."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            out.append(src[i:j + 1])
            i = j + 1
        elif src.startswith("//", i):
            while i < n and src[i] != "\n":
                i += 1
        elif src.startswith("/*", i):
            depth, i = 1, i + 2  # Kotlin block comments nest
            while i < n and depth:
                if src.startswith("/*", i):
                    depth, i = depth + 1, i + 2
                elif src.startswith("*/", i):
                    depth, i = depth - 1, i + 2
                else:
                    i += 1
            out.append(" ")
        else:
            out.append(c)
            i += 1
    return "".join(out)


def split_params(params):
    parts, depth, cur = [], 0, ""
    for ch in params:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    return [p.strip() for p in parts if p.strip()]


def kotlin_natives(path):
    src = strip_kotlin_comments(open(path).read())
    found = {}
    for m in re.finditer(r"\bexternal\s+fun\s+(\w+)\s*\(([^)]*)\)\s*(?::\s*([\w<>?]+))?", src):
        name, params, ret = m.group(1), m.group(2), (m.group(3) or "Unit")
        if name in found:
            fail(f"{os.path.relpath(path, ROOT)}: `{name}` is declared twice; JNI cannot overload it here")
        ptypes = []
        for p in split_params(params):
            if ":" not in p:
                fail(f"{name}: cannot read parameter `{p}`")
                continue
            ptypes.append(p.split(":", 1)[1].split("=", 1)[0].strip().rstrip("?"))
        found[name] = (ptypes, ret.rstrip("?"))
    return found


def descriptor_pieces(name, ptypes, ret):
    """The descriptor as a list of C string-literal pieces and macro names."""
    pieces = ['"("']
    for t in ptypes + [None, ret]:
        if t is None:
            pieces.append('")"')
            continue
        if t not in TYPES:
            fail(f"{name}: Kotlin type `{t}` has no JNI mapping in gen-jni-registry.py")
            continue
        sig = TYPES[t][0]
        pieces.append(CALLBACK_SIG if sig is None else f'"{sig}"')
    # Merge adjacent literals so the common case reads as one string.
    merged = []
    for p in pieces:
        if merged and p.startswith('"') and merged[-1].startswith('"'):
            merged[-1] = merged[-1][:-1] + p[1:]
        else:
            merged.append(p)
    return " ".join(merged)


def c_definitions():
    defs = {}
    pat = re.compile(r"(?m)^(\w+)\s+JNICALL\s+NativeBridge_(\w+)\s*\(([^)]*)\)\s*\{")
    for path in sorted(glob.glob(os.path.join(BRIDGE, "*.c"))):
        src = open(path).read()
        for m in pat.finditer(src):
            ret, name = m.group(1), m.group(2)
            params = [re.sub(r"\s+", " ", p).strip() for p in m.group(3).split(",")]
            if name in defs:
                fail(f"NativeBridge_{name} is defined twice ({defs[name][2]} and {os.path.basename(path)})")
            defs[name] = (ret, params, os.path.basename(path))
        if re.search(r"\bJava_\w+\s*\(", src):
            fail(f"{os.path.basename(path)} still defines a Java_* symbol — name-based linkage is gone; "
                 f"name it NativeBridge_<method> and let the table register it")
    return defs


def c_param_type(param):
    p = param.replace("*", " * ")
    p = re.sub(r"\bconst\b", " ", p)
    toks = p.split()
    # "JNIEnv * env" / "jobject thiz" / "jint index" -> drop the trailing identifier
    return " ".join(toks[:-1]) if len(toks) > 1 else toks[0]


def check_pair(name, kt, cdef, where):
    ptypes, ret = kt
    cret, cparams, cfile = cdef
    if TYPES.get(ret, (None, None))[1] != cret:
        fail(f"{name} ({cfile}): Kotlin returns {ret}, C returns {cret} [{where}]")
    if len(cparams) != len(ptypes) + 2:
        fail(f"{name} ({cfile}): Kotlin has {len(ptypes)} parameters, C has {len(cparams) - 2} after env/thiz [{where}]")
        return
    if c_param_type(cparams[0]) != "JNIEnv *":
        fail(f"{name} ({cfile}): first C parameter is `{cparams[0]}`, expected JNIEnv *")
    if c_param_type(cparams[1]) != "jobject":
        fail(f"{name} ({cfile}): second C parameter is `{cparams[1]}`, expected jobject (NativeBridge is an object, "
             f"its natives are instance methods)")
    for i, (kt_t, cp) in enumerate(zip(ptypes, cparams[2:])):
        want = TYPES.get(kt_t, (None, None))[1]
        if c_param_type(cp) != want:
            fail(f"{name} ({cfile}): parameter {i + 1} is Kotlin {kt_t} but C `{cp}` (want {want}) [{where}]")


def render(entries, defs):
    lines = [
        "/* GENERATED by scripts/gen-jni-registry.py — do not edit by hand.",
        " * Regenerate after adding, removing or changing a NativeBridge `external fun` or its C body;",
        " * CI runs the script with --check and fails if this file is stale. */",
        "#ifndef DGB_JNI_REGISTRY_GEN_H",
        "#define DGB_JNI_REGISTRY_GEN_H",
        "",
        "#include <jni.h>",
        '#include "jni_class_paths.h"',
        "",
    ]
    for name, _ in entries:
        ret, params, cfile = defs[name]
        lines.append(f"{ret} JNICALL NativeBridge_{name}({', '.join(params)});  /* {cfile} */")
    lines += ["", "#define DGB_JNI_NATIVEBRIDGE_METHODS \\"]
    for name, desc in entries:
        lines.append(f'    {{ "{name}", {desc}, (void *) NativeBridge_{name} }}, \\')
    lines += ["", f"#define DGB_JNI_NATIVEBRIDGE_METHOD_COUNT {len(entries)}", "", "#endif", ""]
    return "\n".join(lines)


def main():
    check = "--check" in sys.argv[1:]
    main_kt = kotlin_natives(MAIN_KT)
    test_kt = kotlin_natives(TEST_KT)
    defs = c_definitions()

    entries = []
    for name in sorted(defs):
        if name in main_kt:
            check_pair(name, main_kt[name], defs[name], "core NativeBridge.kt")
            if name in test_kt:
                if test_kt[name] != main_kt[name]:
                    fail(f"{name}: the androidTest NativeBridge copy declares a different signature than core's")
            entries.append((name, descriptor_pieces(name, *main_kt[name])))
        elif name in test_kt:
            check_pair(name, test_kt[name], defs[name], "androidTest NativeBridge.kt")
            entries.append((name, descriptor_pieces(name, *test_kt[name])))
        else:
            fail(f"NativeBridge_{name} ({defs[name][2]}) has no `external fun` in either NativeBridge — dead code, "
                 f"or a declaration was renamed without its body")
    for name in sorted(set(main_kt) | set(test_kt)):
        if name not in defs:
            fail(f"`external fun {name}` has no C body named NativeBridge_{name} — it would throw "
                 f"UnsatisfiedLinkError on first call (and now fails JNI_OnLoad)")

    if errors:
        print("gen-jni-registry: FAIL", file=sys.stderr)
        for e in errors:
            print("  - " + e, file=sys.stderr)
        return 1

    text = render(entries, defs)
    rel = os.path.relpath(OUT, ROOT)
    if check:
        current = open(OUT).read() if os.path.exists(OUT) else ""
        if current != text:
            print(f"gen-jni-registry: {rel} is stale — run scripts/gen-jni-registry.py and commit the result",
                  file=sys.stderr)
            return 1
        print(f"gen-jni-registry: ok — {len(entries)} natives, table current "
              f"({len(main_kt)} in core NativeBridge, {len(test_kt)} in the androidTest copy)")
        return 0
    with open(OUT, "w") as f:
        f.write(text)
    print(f"gen-jni-registry: wrote {rel} — {len(entries)} natives")
    return 0


if __name__ == "__main__":
    sys.exit(main())
