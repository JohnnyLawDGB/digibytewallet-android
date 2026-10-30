// Host KAT: the replay harness (the core's replay/BRReplay.h, design in its docs/replay-harness.md).
//
// WHAT IT PROVES
//   Replay      a recorded sync, served to the real core by a fake peer on 127.0.0.1, gives exactly the
//               snapshot committed beside the recording (balance, the tx list with heights, the UTXO set,
//               the last block's height and hash). The same recordings and snapshots are what the iOS
//               test replays, so a build whose wallet state differs from this one fails one side or the
//               other: the divergence check the harness exists for.
//   Real data   both recordings are real DigiByte mainnet data from a public peer (replay/tools/
//               fetch_window.py); the snapshots were checked against a public explorer when recorded
//               (the core's docs/replay-harness.md, "Fixtures"), not derived from the code under test.
//   No network  the run reaches nothing but the server: it pins it as an exclusive own node, and the
//               server answers from the recording, never relays, and counts any tx, inv or dandeliontx
//               the wallet sends (must be 0; relay_attempts_counted shows the count works).
//   Test seed   only a public test seed replays (unknown_wallet_refused).
//   Recording   the core's message tap (BRPeerSetMessageTap, compiled in with -DBR_PEER_MESSAGE_TAP, which
//               run.sh sets and no app build does) records a session's messages in both directions; that
//               recording replays to the same snapshot. run.sh also checks that a core built without the
//               flag has no tap symbol, and that the flag with NDEBUG does not compile.
//
// THE FIXTURES (core replay/fixtures/, wallet = the public BIP39 test vector "abandon" x11 "about")
//   test-abandon-24460800  resume from 300 saved headers to 24,460,600, scan from 24,460,500 to 24,460,800:
//                          a 2 DGB receive (24,460,708) and a self-send that spends it (24,460,713).
//   test-abandon-23924600  resume to 23,924,400, scan 23,924,300 .. 23,924,600: a Taproot (BIP86) output
//                          of a DigiDollar-style transfer, a BIP84 receive, and the two spends.
//
// One case per process: argv[1] names it, argv[2] is the fixture directory, argv[3] a scratch directory.
// Prints "RESULT <case> PASS|FAIL". run.sh owns which arm each case must fail in.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include "BRReplay.h"
#include "BRWalletOpen.h"
#include "BRBIP39Mnemonic.h"
#include "BRNetwork.h"
#include "BRCrypto.h"
#include "BRChainParams.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", d); if (! c) g_fail++; }

static char *readText(const char *path)
{
    FILE *f = fopen(path, "rb");
    char *buf = NULL;
    long len;

    if (! f) return NULL;
    if (fseek(f, 0, SEEK_END) == 0 && (len = ftell(f)) >= 0 && fseek(f, 0, SEEK_SET) == 0 &&
        (buf = calloc(1, (size_t)len + 1)) && fread(buf, 1, (size_t)len, f) != (size_t)len) {
        free(buf);
        buf = NULL;
    }
    fclose(f);
    return buf;
}

static char g_snapshot[1 << 16];

// Replays dir/name.rec and compares with dir/name.snapshot.
static void replayMatches(const char *dir, const char *name)
{
    char rec[1024], snap[1024];
    char *expected;
    int ok;

    snprintf(rec, sizeof(rec), "%s/%s.rec", dir, name);
    snprintf(snap, sizeof(snap), "%s/%s.snapshot", dir, name);
    expected = readText(snap);
    check(expected != NULL, "the expected snapshot is readable");
    ok = BRReplayRunFile(rec, 120, g_snapshot, sizeof(g_snapshot));
    printf("--- snapshot ---\n%s----------------\n", g_snapshot);
    check(ok, "the replay finished: header tip and scan at the recording's tip, nothing unserved, "
              "no block missing, no relay attempt");
    check(expected && strcmp(expected, g_snapshot) == 0, "the snapshot equals the committed one, byte for byte");
    free(expected);
}

static void case_fixture_24460800_matches(const char *dir, const char *tmp)
{
    (void)tmp;
    replayMatches(dir, "test-abandon-24460800");
}

static void case_fixture_23924600_matches(const char *dir, const char *tmp)
{
    (void)tmp;
    replayMatches(dir, "test-abandon-23924600");
}

// Record a replayed session with the core's tap, then replay that recording.
static void case_recording_round_trip(const char *dir, const char *tmp)
{
    const char *name = "test-abandon-24460800";
    char rec[1024], snap[1024], out[1024];
    char *expected;
    BRReplayRecording *r, *tapped;
    BRWallet *w;
    uint8_t seed[64];
    BRReplayRunOptions opt;
    BRReplayRunResult res;
    size_t in = 0, outCount = 0, peers = 0;
    int ordered = 1;
    uint64_t last = 0;

    snprintf(rec, sizeof(rec), "%s/%s.rec", dir, name);
    snprintf(snap, sizeof(snap), "%s/%s.snapshot", dir, name);
    snprintf(out, sizeof(out), "%s/session.rec", tmp);
    expected = readText(snap);
    r = BRReplayRecordingRead(rec);
    check(expected && r, "the fixture and its snapshot are readable");
    if (! expected || ! r) { free(expected); BRReplayRecordingFree(r); return; }

    BRBIP39DeriveKey(seed, BRReplayTestSeedPhrase(BRReplayRecordingSession(r, "wallet")), NULL);
    w = BRWalletOpenFromSeed(seed, sizeof(seed), NULL, 0);
    memset(&opt, 0, sizeof(opt));
    opt.recordPath = out;
    res = BRReplayRun(r, w, opt, g_snapshot, sizeof(g_snapshot));
    check(res.done && strcmp(expected, g_snapshot) == 0, "the recorded session reaches the committed snapshot");
    BRWalletFree(w);

    tapped = BRReplayRecordingRead(out);
    check(tapped != NULL, "the session's recording parses");
    for (size_t i = 0; tapped && i < BRReplayRecordingCount(tapped); i++) {
        const BRReplayRecord *x = BRReplayRecordingAt(tapped, i);

        if (x->dir == BR_REPLAY_DIR_IN) in++;
        if (x->dir == BR_REPLAY_DIR_OUT) outCount++;
        if (x->dir == BR_REPLAY_DIR_META && strcmp(x->type, "peer") == 0) peers++;
        if (x->timeUs < last) ordered = 0;
        last = x->timeUs;
    }
    printf("  recorded: %zu in, %zu out, %zu peer\n", in, outCount, peers);
    check(in > 0 && outCount > 0 && peers == 1, "it holds both directions of one peer's messages");
    check(ordered, "in the order they happened (times never go back)");

    if (tapped) {
        w = BRWalletOpenFromSeed(seed, sizeof(seed), NULL, 0);
        memset(&opt, 0, sizeof(opt));
        res = BRReplayRun(tapped, w, opt, g_snapshot, sizeof(g_snapshot));
        check(res.done && res.server.unserved == 0 && res.server.missingBlocks == 0 && strcmp(expected, g_snapshot) == 0,
              "replaying the session's own recording gives the same snapshot");
        BRWalletFree(w);
    }
    memset(seed, 0, sizeof(seed));
    BRReplayRecordingFree(tapped);
    BRReplayRecordingFree(r);
    free(expected);
}

// The parser refuses what is not a recording, and never reads past the buffer.
static void case_recording_rejects_malformed(const char *dir, const char *tmp)
{
    char rec[1024];
    BRReplayRecording *r;
    FILE *f;
    uint8_t *buf;
    long len;

    (void)tmp;
    snprintf(rec, sizeof(rec), "%s/test-abandon-24460800.rec", dir);
    f = fopen(rec, "rb");
    check(f != NULL, "the fixture opens");
    if (! f) return;
    fseek(f, 0, SEEK_END); len = ftell(f); fseek(f, 0, SEEK_SET);
    buf = malloc((size_t)len);
    if (! buf || fread(buf, 1, (size_t)len, f) != (size_t)len) { check(0, "the fixture reads"); fclose(f); free(buf); return; }
    fclose(f);

    r = BRReplayRecordingParse(buf, (size_t)len);
    check(r && BRReplayRecordingCount(r) > 300, "the fixture parses");
    check(r && BRReplayRecordingSessionU32(r, "tip", 0) == 24460800, "its session names its tip");
    BRReplayRecordingFree(r);
    check(BRReplayRecordingParse(buf, 7) == NULL, "a buffer shorter than the magic is refused");
    buf[0] ^= 1;
    check(BRReplayRecordingParse(buf, (size_t)len) == NULL, "a bad magic is refused");
    buf[0] ^= 1;
    check(BRReplayRecordingParse(buf, (size_t)len - 1) == NULL, "a record cut short is refused");
    check(BRReplayRecordingParse(buf, 8 + 10) == NULL, "a record header cut short is refused");
    buf[8] = 'X';
    check(BRReplayRecordingParse(buf, (size_t)len) == NULL, "an unknown direction is refused");
    free(buf);
    check(BRReplayTestSeedPhrase("bip39-test-abandon-about") != NULL && BRReplayTestSeedPhrase("anything else") == NULL,
          "only the named public test seed replays");
}

// The fake peer relays nothing: a tx, an inv and a dandeliontx sent to it are counted as relay attempts
// (what makes a replay that tried to broadcast fail) and answered with nothing.
static void sendFrame(int fd, const char *type, const uint8_t *payload, size_t len)
{
    uint8_t h[24];
    UInt256 hash;

    memset(h, 0, sizeof(h));
    UInt32SetLE(&h[0], BRMainNetParams.magicNumber);
    strncpy((char *)&h[4], type, 12);
    UInt32SetLE(&h[16], (uint32_t)len);
    BRSHA256_2(&hash, payload, len);
    memcpy(&h[20], hash.u8, 4);
    if (send(fd, h, sizeof(h), 0) != (ssize_t)sizeof(h) || (len && send(fd, payload, len, 0) != (ssize_t)len)) {
        check(0, "a frame went out");
    }
}

static void case_relay_attempts_counted(const char *dir, const char *tmp)
{
    char rec[1024];
    BRReplayRecording *r;
    BRReplayServer *server;
    uint16_t port = 0;
    struct sockaddr_in sa;
    struct timeval tv = { 10, 0 };
    int fd;
    uint8_t tx[10] = { 1, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, inv[37] = { 1, 1 }, ping[8] = { 1, 2, 3, 4, 5, 6, 7, 8 };
    uint8_t reply[24 + 8];
    size_t got = 0;
    BRReplayServerStats st;

    (void)tmp;
    snprintf(rec, sizeof(rec), "%s/test-abandon-24460800.rec", dir);
    r = BRReplayRecordingRead(rec);
    server = r ? BRReplayServerStart(r, &port) : NULL;
    check(server != NULL, "the replay server starts on the fixture");
    if (! server) { BRReplayRecordingFree(r); return; }

    fd = socket(AF_INET, SOCK_STREAM, 0);
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    sa.sin_port = htons(port);
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    check(connect(fd, (struct sockaddr *)&sa, sizeof(sa)) == 0, "a client connects");
    sendFrame(fd, "tx", tx, sizeof(tx));
    sendFrame(fd, "inv", inv, sizeof(inv));
    sendFrame(fd, "dandeliontx", tx, sizeof(tx));
    sendFrame(fd, "ping", ping, sizeof(ping));   // served in order: its pong means the three were handled
    while (got < sizeof(reply)) {
        ssize_t n = recv(fd, &reply[got], sizeof(reply) - got, 0);

        if (n <= 0) break;
        got += (size_t)n;
    }
    check(got == sizeof(reply) && strncmp((const char *)&reply[4], "pong", 12) == 0 && memcmp(&reply[24], ping, 8) == 0,
          "the first and only answer is the pong (nothing was relayed or answered for tx, inv, dandeliontx)");
    st = BRReplayServerGetStats(server);
    printf("  stats: %zu requests, %zu served, %zu relay attempts\n", st.requests, st.served, st.relayAttempts);
    check(st.relayAttempts == 3, "tx, inv and dandeliontx are three relay attempts");
    check(st.served == 1, "the server sent one message: the pong");
    close(fd);
    BRReplayServerStop(server);
    BRReplayRecordingFree(r);
}

// BRReplayRunFile replays only into a public test seed it knows: a recording naming any other wallet is
// refused before anything runs.
static void case_unknown_wallet_refused(const char *dir, const char *tmp)
{
    char rec[1024], out[1024];
    static const char session[] = "wallet=not-a-test-seed\n";
    FILE *in, *f;
    uint8_t *buf;
    long len;
    uint8_t h[26];
    int ok;

    snprintf(rec, sizeof(rec), "%s/test-abandon-24460800.rec", dir);
    snprintf(out, sizeof(out), "%s/other-wallet.rec", tmp);
    in = fopen(rec, "rb");
    check(in != NULL, "the fixture opens");
    if (! in) return;
    fseek(in, 0, SEEK_END); len = ftell(in); fseek(in, 0, SEEK_SET);
    buf = malloc((size_t)len);
    if (! buf || fread(buf, 1, (size_t)len, in) != (size_t)len) { check(0, "the fixture reads"); fclose(in); free(buf); return; }
    fclose(in);

    // the magic, then a session record naming another wallet ahead of the fixture's own (the first wins)
    memset(h, 0, sizeof(h));
    h[0] = BR_REPLAY_DIR_META;
    strncpy((char *)&h[10], "session", 12);
    UInt32SetLE(&h[22], (uint32_t)strlen(session));
    f = fopen(out, "wb");
    fwrite(buf, 1, 8, f);
    fwrite(h, 1, sizeof(h), f);
    fwrite(session, 1, strlen(session), f);
    fwrite(&buf[8], 1, (size_t)len - 8, f);
    fclose(f);
    free(buf);

    ok = BRReplayRunFile(out, 30, g_snapshot, sizeof(g_snapshot));
    check(ok == 0, "a recording naming another wallet does not replay");
    check(g_snapshot[0] == '\0', "and produces no snapshot");
}

int main(int argc, char **argv)
{
    static const struct { const char *name; void (*fn)(const char *, const char *); } cases[] = {
        { "fixture_24460800_matches", case_fixture_24460800_matches },
        { "fixture_23924600_matches", case_fixture_23924600_matches },
        { "recording_round_trip", case_recording_round_trip },
        { "recording_rejects_malformed", case_recording_rejects_malformed },
        { "relay_attempts_counted", case_relay_attempts_counted },
        { "unknown_wallet_refused", case_unknown_wallet_refused },
    };

    if (argc < 2) {
        for (size_t i = 0; i < sizeof(cases)/sizeof(*cases); i++) printf("%s\n", cases[i].name);
        return 0;
    }
    if (argc < 4) { fprintf(stderr, "usage: %s <case> <fixture dir> <scratch dir>\n", argv[0]); return 2; }
    BRSetNetwork(0);   // mainnet, as the apps run
    for (size_t i = 0; i < sizeof(cases)/sizeof(*cases); i++) {
        if (strcmp(argv[1], cases[i].name) != 0) continue;
        cases[i].fn(argv[2], argv[3]);
        printf("RESULT %s %s\n", argv[1], g_fail ? "FAIL" : "PASS");
        return g_fail ? 1 : 0;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
