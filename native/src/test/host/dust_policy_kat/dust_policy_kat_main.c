// Host KAT for BRDustPolicy.h -- the smallest output the network relays, by output script, and the
// DigiDollar token exemption, from Android's DustPolicy.kt.
//
// Every DustPolicyTest.kt case is carried over with its values, then the boundaries the Kotlin
// tests do not reach. Expected values are literals, worked out from DigiByte Core's rule
// (GetDustThreshold at 30,000 sat/kvB), never from the header under test. The three lines
// AssetDustPolicyTest.kt measured on a live node (P2PKH 5,460, P2WPKH 2,940, P2TR 3,300) are
// among them. Whether a transaction is a DigiDollar transaction is the core's own
// BRDigiDollarTxType, linked in, checked here against DustPolicyTest's version cases.
//
// Built four ways by run.sh:
//   -DDUST_POLICY_RED_RATE          Bitcoin's 3,000 sat/kvB: every line a tenth of Core's
//   -DDUST_POLICY_RED_NO_EXEMPTION  no DigiDollar exemption: every DigiDollar send refused
//   -DDUST_POLICY_RED_ANY_ZERO      any zero-value output exempt, in any transaction
//   (default)                       the shipped rule; every case passes
// run.sh names the cases each comparison arm must fail; every other case must pass there.

#include "BRDustPolicy.h"
#include "BRDigiDollar.h"
#include "BRTransaction.h"
#include <inttypes.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

static int g_failed = 0, g_ok = 1;
static const char *g_case = "";

static void begin(const char *name) { g_case = name; g_ok = 1; }

static void ck(int cond, const char *fmt, ...)
{
    va_list ap;

    if (cond) return;
    printf("FAIL: %s: ", g_case);
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    g_ok = 0;
}

static void end(void)
{
    printf("RESULT %s %s\n", g_case, g_ok ? "pass" : "fail");
    if (! g_ok) g_failed = 1;
}

// A script: hex prefix, then count copies of fill, then hex suffix (Kotlin's script(hex + "xx".repeat(n))).
typedef struct { uint8_t b[10001]; size_t n; } Script;

static void hex_into(Script *s, const char *hex)
{
    for (; hex && hex[0] && hex[1]; hex += 2) {
        unsigned v = 0;
        sscanf(hex, "%2x", &v);
        s->b[s->n++] = (uint8_t)v;
    }
}

static Script S(const char *prefix, uint8_t fill, size_t count, const char *suffix)
{
    Script s;

    memset(&s, 0, sizeof(s));
    hex_into(&s, prefix);
    memset(s.b + s.n, fill, count);
    s.n += count;
    hex_into(&s, suffix);
    return s;
}

static int64_t line(const Script *s) { return BRDustThreshold(s->b, s->n); }
static int dust(int64_t v, const Script *s, int dd) { return BRDustIsDust(v, s->b, s->n, dd); }
static int exempt(int64_t v, const Script *s, int dd) { return BRDustIsDigiDollarExempt(v, s->b, s->n, dd); }

// The standard scripts of DustPolicyTest.kt.
static Script p2pkh, p2sh, p2wpkh, p2wsh, p2tr, opReturn;

// DustPolicyTest.lineIs: the line is Core's; one satoshi below it is refused, at it is sent.
static void lineIs(const char *name, const Script *s, int64_t want, int64_t constant)
{
    begin(name);
    ck(line(s) == want, "the dust line is %" PRId64 ", not DigiByte Core's %" PRId64, line(s), want);
    ck(constant == want, "the header's constant is %" PRId64 ", not %" PRId64, constant, want);
    ck(dust(want - 1, s, 0), "%" PRId64 " sat is below the line and must be refused", want - 1);
    ck(! dust(want, s, 0), "%" PRId64 " sat is at the line and must be sent", want);
    ck(! dust(want + 1, s, 0), "%" PRId64 " sat is above the line and must be sent", want + 1);
    end();
}

static int ddType(uint32_t version)
{
    BRTransaction *tx = BRTransactionNew();
    int t;

    if (! tx) return -1;
    tx->version = version;
    t = BRDigiDollarTxType(tx);
    BRTransactionFree(tx);
    return t;
}

int main(void)
{
    p2pkh = S("76a914", 0x22, 20, "88ac");                 // 25 bytes
    p2sh = S("a914", 0x33, 20, "87");                      // 23 bytes
    p2wpkh = S("0014", 0x44, 20, NULL);                    // 22 bytes
    p2wsh = S("0020", 0x55, 32, NULL);                     // 34 bytes
    p2tr = S("5120", 0x66, 32, NULL);                      // 34 bytes
    opReturn = S("6a024444010202c409024c1d", 0, 0, NULL);

    // ---- the header's constants: the values DustPolicy.kt carries ----
    begin("constants");
    ck(BR_DUST_RELAY_FEE_PER_KB == 30000, "dust relay fee %d sat/kvB, not 30,000", BR_DUST_RELAY_FEE_PER_KB);
    ck(BR_DUST_MAX_SCRIPT_SIZE == 10000, "max script size %d, not 10,000", BR_DUST_MAX_SCRIPT_SIZE);
    ck(BR_DUST_SPEND_SIZE == 148, "spend size %d, not 148", BR_DUST_SPEND_SIZE);
    ck(BR_DUST_WITNESS_SPEND_SIZE == 67, "witness spend size %d, not 67", BR_DUST_WITNESS_SPEND_SIZE);
    ck(BR_DUST_DD_TOKEN_SCRIPT_SIZE == 34, "DigiDollar token script %d bytes, not 34", BR_DUST_DD_TOKEN_SCRIPT_SIZE);
    end();

    // ---- DustPolicyTest.kt, case for case ----
    lineIs("p2pkh_line", &p2pkh, 5460, BR_DUST_P2PKH_SATS);
    lineIs("p2sh_line", &p2sh, 5400, BR_DUST_P2SH_SATS);
    lineIs("p2wpkh_line", &p2wpkh, 2940, BR_DUST_P2WPKH_SATS);
    lineIs("p2tr_line", &p2tr, 3300, BR_DUST_P2TR_SATS);
    lineIs("p2wsh_line", &p2wsh, 3300, BR_DUST_P2WSH_SATS);

    begin("unspendable_has_no_line");
    {
        Script big = S(NULL, 0x51, 10001, NULL);
        ck(line(&opReturn) == 0, "a data output's line is %" PRId64 ", not 0", line(&opReturn));
        ck(! dust(0, &opReturn, 0), "a zero-value data output is refused");
        ck(line(&big) == 0, "a script over Core's 10,000-byte limit is unspendable: line %" PRId64, line(&big));
    }
    end();

    begin("witness_program_by_shape");
    {
        // Version 16 with a 40-byte program: a witness program. 41 bytes of program: not one.
        Script v40 = S("6028", 0x77, 40, NULL), v41 = S("6029", 0x77, 41, NULL);
        ck(line(&v40) == 3540, "v16/40: line %" PRId64 ", not 30 x (8 + 1 + 42 + 67) = 3,540", line(&v40));
        ck(line(&v41) == 6000, "v16/41: line %" PRId64 ", not 30 x (8 + 1 + 43 + 148) = 6,000", line(&v41));
    }
    end();

    begin("dd_token_exempt_in_dd_tx");
    ck(exempt(0, &p2tr, 1), "a zero-value 34-byte OP_1 output of a DigiDollar transaction is not exempt");
    ck(! dust(0, &p2tr, 1), "a DigiDollar token output was refused as dust; every DigiDollar send would be");
    end();

    begin("dd_exemption_is_exact");
    ck(dust(0, &p2tr, 0), "outside a DigiDollar transaction a zero-value Taproot output is dust");
    ck(dust(1, &p2tr, 1), "one satoshi is not zero");
    ck(dust(0, &p2wsh, 1), "a P2WSH script is 34 bytes but not OP_1");
    ck(dust(0, &p2wpkh, 1), "a P2WPKH script is not 34 bytes");
    ck(dust(3299, &p2tr, 1), "ordinary DGB outputs of a DigiDollar transaction keep the line");
    end();

    // DustPolicy.isDigiDollarTransaction's cases, against the core's own classifier.
    begin("dd_tx_is_marker_and_known_type");
    ck(ddType(0x01000770u) == DD_TYPE_MINT, "0x01000770 (mint): type %d", ddType(0x01000770u));
    ck(ddType(0x02000770u) == DD_TYPE_TRANSFER, "0x02000770 (transfer): type %d", ddType(0x02000770u));
    ck(ddType(0x03000770u) == DD_TYPE_REDEEM, "0x03000770 (redeem): type %d", ddType(0x03000770u));
    ck(ddType(0x02FF0770u) != 0, "0x02FF0770: flag bits do not matter");
    ck(ddType(0x00000770u) == 0, "0x00000770: type 0 is no DigiDollar transaction");
    ck(ddType(0x04000770u) == 0, "0x04000770: types from 4 are not");
    ck(ddType(2) == 0, "version 2: no marker");
    ck(ddType(0x02000771u) == 0, "0x02000771: not the marker");
    end();

    // A real DigiDollar transfer as it is on chain: DigiByte testnet26, block 83946, txid
    // 3d8797cb87f6903bceeea28e6366093faec34af629e051dfda7b3a9616c5a346 (digidollar_realtx_kat's
    // golden vector). Version 0x02000770; two zero-value Taproot token outputs, 2.88 DGB of
    // Taproot change, and the OP_RETURN. Checked as a platform checks what it is about to sign:
    // every output of the transaction, with BRDigiDollarTxType deciding the transaction.
    begin("real_dd_transfer_has_no_dust");
    {
        static const struct { uint64_t v; const char *hex; } outs[] = {
            { 0, "5120effc01464e19f45d27d3fd670e62f4b21a441e894bc9cd1726b99ca8c02000cd" },
            { 0, "512055e1ba29a8289e01ba58d4101fa8985650f4d2d2f20193a6bd5da99d7d3064a3" },
            { 288000000, "512055e1ba29a8289e01ba58d4101fa8985650f4d2d2f20193a6bd5da99d7d3064a3" },
            { 0, "6a024444010202c409024c1d" },
        };
        BRTransaction *tx = BRTransactionNew();
        size_t i;
        int dd;

        ck(tx != NULL, "no transaction");
        if (tx) {
            tx->version = 0x02000770u;
            for (i = 0; i < sizeof(outs) / sizeof(outs[0]); i++) {
                Script s = S(outs[i].hex, 0, 0, NULL);
                BRTransactionAddOutput(tx, outs[i].v, s.b, s.n);
            }
            dd = BRDigiDollarTxType(tx) != 0;
            ck(dd, "the transfer is not read as a DigiDollar transaction");
            for (i = 0; i < tx->outCount; i++) {
                ck(! BRDustIsDust((int64_t)tx->outputs[i].amount, tx->outputs[i].script, tx->outputs[i].scriptLen, dd),
                   "output %zu (%" PRIu64 " sat) of a real DigiDollar transfer is refused as dust", i, tx->outputs[i].amount);
            }
            // And the exemption is what lets the token outputs through.
            ck(BRDustIsDust(0, tx->outputs[0].script, tx->outputs[0].scriptLen, 0),
               "outside a DigiDollar transaction the token output is not dust");
            BRTransactionFree(tx);
        }
    }
    end();

    // ---- the boundaries ----

    // Amounts are Kotlin's Long: below zero is below every line, and only exactly zero is exempt.
    begin("amount_bounds");
    ck(dust(-1, &p2wpkh, 0), "-1 sat is not dust");
    ck(dust(INT64_MIN, &p2pkh, 0), "INT64_MIN sat is not dust");
    ck(dust(-1, &opReturn, 0), "-1 sat is below a data output's line of 0 (as Kotlin's Long reads it)");
    ck(! dust(INT64_MAX, &p2pkh, 0), "INT64_MAX sat is dust");
    ck(! dust(INT64_MAX, &p2tr, 1), "INT64_MAX sat in a DigiDollar transaction is dust");
    ck(! exempt(-1, &p2tr, 1), "a negative amount is exempt; only zero is");
    ck(dust(-1, &p2tr, 1), "a negative token-shaped output in a DigiDollar transaction is not dust");
    end();

    // CompactSize: a script of 252 bytes takes one length byte, 253 takes three.
    begin("compact_size_boundary");
    {
        Script a = S(NULL, 0x51, 252, NULL), b = S(NULL, 0x51, 253, NULL);
        ck(line(&a) == 12270, "252 bytes: line %" PRId64 ", not 30 x (8 + 1 + 252 + 148) = 12,270", line(&a));
        ck(line(&b) == 12360, "253 bytes: line %" PRId64 ", not 30 x (8 + 3 + 253 + 148) = 12,360", line(&b));
    }
    end();

    begin("script_size_limit");
    {
        Script max = S(NULL, 0x51, 10000, NULL), ret1 = S("6a", 0, 0, NULL), late = S("516a", 0, 0, NULL);
        Script empty = S(NULL, 0, 0, NULL);
        ck(line(&max) == 304770, "10,000 bytes: line %" PRId64 ", not 30 x (8 + 3 + 10000 + 148) = 304,770", line(&max));
        ck(line(&ret1) == 0, "a lone OP_RETURN: line %" PRId64 ", not 0", line(&ret1));
        ck(line(&late) == 4770, "OP_RETURN after the first byte does not make a script unspendable: line %" PRId64, line(&late));
        ck(line(&empty) == 4710, "an empty script: line %" PRId64 ", not 30 x (8 + 1 + 0 + 148) = 4,710", line(&empty));
        ck(BRDustThreshold(NULL, 5) == 4710, "a NULL script is read as empty: line %" PRId64, BRDustThreshold(NULL, 5));
        ck(! BRDustScriptIsUnspendable(NULL, 5) && ! BRDustScriptIsWitnessProgram(NULL, 5), "a NULL script is classified");
        ck(BRDustIsDust(4709, NULL, 5, 0) && ! BRDustIsDust(4710, NULL, 5, 0), "a NULL script is not held to the empty line");
    }
    end();

    // A witness program is 4 to 42 bytes.
    begin("witness_length_bounds");
    {
        Script l3 = S("0001", 0x77, 1, NULL), l4 = S("0002", 0x77, 2, NULL);
        Script l42 = S("0028", 0x77, 40, NULL), l43 = S("0029", 0x77, 41, NULL);
        ck(line(&l3) == 4800, "3 bytes is no witness program: line %" PRId64 ", not 4,800", line(&l3));
        ck(line(&l4) == 2400, "4 bytes is one: line %" PRId64 ", not 2,400", line(&l4));
        ck(line(&l42) == 3540, "42 bytes is one: line %" PRId64 ", not 3,540", line(&l42));
        ck(line(&l43) == 6000, "43 bytes is none: line %" PRId64 ", not 6,000", line(&l43));
    }
    end();

    // The version byte is OP_0 or OP_1..OP_16, and the push must be the whole rest.
    begin("witness_version_opcodes");
    {
        static const uint8_t notVersions[] = { 0x01, 0x4f, 0x50, 0x61, 0xff };
        char hex[5];
        unsigned i;

        for (i = 0; i <= 16; i++) {                         // OP_0, then OP_1 (0x51) through OP_16 (0x60)
            unsigned v = i ? 0x50 + i : 0x00;
            snprintf(hex, sizeof(hex), "%02x14", v);
            Script s = S(hex, 0x44, 20, NULL);
            ck(line(&s) == 2940, "version 0x%02x: line %" PRId64 ", not 2,940", v, line(&s));
        }
        for (i = 0; i < sizeof(notVersions); i++) {
            snprintf(hex, sizeof(hex), "%02x14", notVersions[i]);
            Script s = S(hex, 0x44, 20, NULL);
            ck(line(&s) == 5370, "0x%02x is no version: line %" PRId64 ", not 30 x (8 + 1 + 22 + 148) = 5,370",
               notVersions[i], line(&s));
        }
        {
            Script s = S("0015", 0x44, 20, NULL);           // declares 21 bytes, carries 20
            ck(line(&s) == 5370, "a push that is not the whole rest: line %" PRId64 ", not 5,370", line(&s));
        }
        {
            // Declares 20 bytes, carries 21: a push SHORTER than the rest is no witness program either
            // (Core: script[1] + 2 == size, never <=).
            Script s = S("0014", 0x44, 21, NULL);
            ck(! BRDustScriptIsWitnessProgram(s.b, s.n), "a 20-byte push followed by one more byte is a witness program");
            ck(line(&s) == 5400, "a push shorter than the rest: line %" PRId64 ", not 30 x (8 + 1 + 23 + 148) = 5,400", line(&s));
        }
    }
    end();

    // The exemption's shape, exactly the Kotlin's: zero, 34 bytes, OP_1 first, in a DigiDollar
    // transaction. The push byte is not part of it.
    begin("dd_exempt_shape_bounds");
    {
        Script op1_34 = S("5121", 0x66, 32, NULL);          // 34 bytes, OP_1, not a witness program
        Script op1_33 = S("511f", 0x66, 31, NULL), op1_35 = S("5121", 0x66, 33, NULL);
        ck(exempt(0, &op1_34, 1) && ! dust(0, &op1_34, 1), "a 34-byte OP_1 script is exempt whatever its second byte");
        ck(! exempt(0, &op1_33, 1) && dust(0, &op1_33, 1), "a 33-byte OP_1 script is exempt");
        ck(! exempt(0, &op1_35, 1) && dust(0, &op1_35, 1), "a 35-byte OP_1 script is exempt");
        ck(! exempt(0, &op1_34, 0) && dust(0, &op1_34, 0), "a 34-byte OP_1 script outside a DigiDollar transaction is exempt");
        ck(! BRDustIsDigiDollarExempt(0, NULL, 34, 1) && BRDustIsDust(0, NULL, 34, 1), "a NULL script is exempt");
        // OP_1 exactly: a 34-byte zero-value output whose first opcode is above OP_1 is not a token.
        {
            Script op2_34 = S("5220", 0x66, 32, NULL), op16_34 = S("6020", 0x66, 32, NULL);
            Script ret_34 = S("6a20", 0x66, 32, NULL);
            ck(! exempt(0, &op2_34, 1) && dust(0, &op2_34, 1), "a 34-byte OP_2 script in a DigiDollar transaction is exempt");
            ck(! exempt(0, &op16_34, 1) && dust(0, &op16_34, 1), "a 34-byte OP_16 script in a DigiDollar transaction is exempt");
            ck(! exempt(0, &ret_34, 1), "a 34-byte OP_RETURN script in a DigiDollar transaction is exempt");
        }
    }
    end();

    // One line per value for run.sh to compare across builds.
    printf("LINES %" PRId64 " %" PRId64 " %" PRId64 " %" PRId64 " %" PRId64 " %" PRId64 "\n",
           line(&p2pkh), line(&p2sh), line(&p2wpkh), line(&p2wsh), line(&p2tr), line(&opReturn));

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
