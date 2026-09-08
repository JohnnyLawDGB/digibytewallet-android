/*
 * jni_asset_quantity.c
 *
 * JNI accessors for BRAssetQuantity.h -- how many DigiAsset token units land on
 * one transaction output: what the instructions assign, what the implicit
 * remainder adds, and the fail-closed decision to keep an outpoint out of the
 * spendable plain-DGB set.
 *
 * Test-support only; production Kotlin does not route through here. See
 * jni_cf_recovery_policy.c for the rationale: AssetTxQuantity.kt keeps its
 * mirror because its suite runs on the host JVM, and AssetQuantityParityTest
 * binds the mirror to this C. The directions that matter are range crediting
 * (a mirror that drops it under-counts a real holding) and the overflow guard
 * (a mirror without it credits units that do not exist).
 *
 * Touches no wallet state, takes no lock.
 */

#include "jni_bridge.h"
#include "BRAssetQuantity.h"

/* Instructions cross the boundary as a flat long[], six longs per row, in the
 * SAME field order as BRAssetTransferInstruction so a reader can check the two
 * against each other by eye:
 *
 *     { skip, range, percent, isBurn, outputIndex, amount }
 *
 * A flat array rather than a parallel-array-per-field call: six arrays is six
 * chances to pass them in the wrong order, and the compiler catches none of it.
 */
#define ASSET_INST_ROW_LONGS 6

/* Decode `rows` into a freshly malloc'd instruction array.
 *
 * Returns 1 on success (*out may be NULL when the count is 0, which is a valid
 * empty instruction set), 0 when the array is malformed or allocation failed.
 * On success the caller owns *out and must free it. */
static int decode_instructions(JNIEnv *env, jlongArray rows,
                               BRAssetTransferInstruction **out, size_t *outCount)
{
    jsize len;
    jlong *raw;
    size_t n, i;
    BRAssetTransferInstruction *insts;

    *out = NULL;
    *outCount = 0;
    if (! rows) return 1;                                  /* no instructions */

    len = (*env)->GetArrayLength(env, rows);
    if (len < 0 || (len % ASSET_INST_ROW_LONGS) != 0) return 0;
    n = (size_t)len / ASSET_INST_ROW_LONGS;
    if (n == 0) return 1;

    raw = (*env)->GetLongArrayElements(env, rows, NULL);
    if (! raw) return 0;

    insts = (BRAssetTransferInstruction *)calloc(n, sizeof(*insts));
    if (! insts) {
        (*env)->ReleaseLongArrayElements(env, rows, raw, JNI_ABORT);
        return 0;
    }

    for (i = 0; i < n; i++) {
        const jlong *r = raw + i * ASSET_INST_ROW_LONGS;
        insts[i].skip        = r[0] ? 1 : 0;
        insts[i].range       = r[1] ? 1 : 0;
        insts[i].percent     = r[2] ? 1 : 0;
        insts[i].isBurn      = r[3] ? 1 : 0;
        /* outputIndex is int32_t in the struct; a Kotlin test passing something
         * outside that range is asking about a malformed header, and the
         * clamp-free cast would silently make it a different index. Park it at
         * -1, which every decision here treats as UNSOUND. */
        insts[i].outputIndex = (r[4] >= INT32_MIN && r[4] <= INT32_MAX) ? (int32_t)r[4] : -1;
        insts[i].amount      = (int64_t)r[5];
    }

    (*env)->ReleaseLongArrayElements(env, rows, raw, JNI_ABORT);
    *out = insts;
    *outCount = n;
    return 1;
}

/* Pack { status, units } as a long[2]. Status is a BRAssetQuantityStatus:
 * 0 OK, 1 UNKNOWN, 2 UNSOUND. Kotlin decodes 1 and 2 back into its `null`. */
static jlongArray pack(JNIEnv *env, BRAssetQuantityStatus st, int64_t units)
{
    jlong out[2];
    jlongArray arr;

    out[0] = (jlong)st;
    out[1] = (jlong)units;
    arr = (*env)->NewLongArray(env, 2);
    if (! arr) return NULL;
    (*env)->SetLongArrayRegion(env, arr, 0, 2, out);
    return arr;
}

/* A malformed instruction array is not "no instructions": answering as if the
 * transfer assigned nothing would credit the whole input balance as change. */
static jlongArray packMalformed(JNIEnv *env)
{
    return pack(env, BRAssetQuantityUnsound, 0);
}

JNIEXPORT jlongArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_assetQuantityForOutput(JNIEnv *env, jobject thiz,
        jint op, jlong totalQuantity, jint vout, jint firstNonOpReturnVout, jlongArray rows)
{
    (void)thiz;
    BRAssetTransferInstruction *insts;
    size_t n;
    BRAssetQuantityStatus st;
    int64_t units = 0;
    jlongArray res;

    if (! decode_instructions(env, rows, &insts, &n)) return packMalformed(env);
    st = BRAssetQuantityForOutput((BRAssetOperation)op, (int64_t)totalQuantity,
                                  (int32_t)vout, (int32_t)firstNonOpReturnVout,
                                  insts, n, &units);
    free(insts);
    res = pack(env, st, units);
    return res;
}

JNIEXPORT jlongArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_assetImplicitChange(JNIEnv *env, jobject thiz,
        jint op, jboolean hasInputUnits, jlong inputUnits, jlongArray rows)
{
    (void)thiz;
    BRAssetTransferInstruction *insts;
    size_t n;
    BRAssetQuantityStatus st;
    int64_t units = 0;
    jlongArray res;

    if (! decode_instructions(env, rows, &insts, &n)) return packMalformed(env);
    st = BRAssetImplicitChange((BRAssetOperation)op, hasInputUnits ? 1 : 0,
                               (int64_t)inputUnits, insts, n, &units);
    free(insts);
    res = pack(env, st, units);
    return res;
}

JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_assetImplicitChangeVout(JNIEnv *env, jobject thiz,
        jint outputCount)
{
    (void)env; (void)thiz;
    return (jint)BRAssetImplicitChangeVout((int32_t)outputCount);
}

JNIEXPORT jlongArray JNICALL
Java_io_digibyte_core_bridge_NativeBridge_assetQuantityForOutputTotal(JNIEnv *env, jobject thiz,
        jint op, jlong totalQuantity, jint vout, jint firstNonOpReturnVout,
        jboolean hasInputUnits, jlong inputUnits, jint outputCount, jlongArray rows)
{
    (void)thiz;
    BRAssetTransferInstruction *insts;
    size_t n;
    BRAssetQuantityStatus st;
    int64_t units = 0;
    jlongArray res;

    if (! decode_instructions(env, rows, &insts, &n)) return packMalformed(env);
    st = BRAssetQuantityForOutputTotal((BRAssetOperation)op, (int64_t)totalQuantity,
                                       (int32_t)vout, (int32_t)firstNonOpReturnVout,
                                       hasInputUnits ? 1 : 0, (int64_t)inputUnits,
                                       (int32_t)outputCount, insts, n, &units);
    free(insts);
    res = pack(env, st, units);
    return res;
}

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_assetOutpointMustBeExcluded(JNIEnv *env, jobject thiz,
        jint op, jint vout, jint outputCount, jboolean hasInputUnits, jlong inputUnits,
        jlongArray rows)
{
    (void)thiz;
    BRAssetTransferInstruction *insts;
    size_t n;
    int excluded;

    /* Fail-closed all the way out: an array this bridge cannot even parse is
     * the last thing that should release an outpoint into a plain-DGB spend. */
    if (! decode_instructions(env, rows, &insts, &n)) return JNI_TRUE;
    excluded = BRAssetOutpointMustBeExcluded((BRAssetOperation)op, (int32_t)vout,
                                             (int32_t)outputCount, hasInputUnits ? 1 : 0,
                                             (int64_t)inputUnits, insts, n);
    free(insts);
    return excluded ? JNI_TRUE : JNI_FALSE;
}
