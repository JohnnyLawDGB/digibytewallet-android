/* Host KAT: waiting on a connect must work for ANY descriptor number.
 *
 * THE DEFECT. _BRPeerOpenSocket waited for an in-progress connect() with:
 *
 *     fd_set fds;
 *     FD_ZERO(&fds);
 *     FD_SET(ctx->socket, &fds);
 *     count = select(ctx->socket + 1, NULL, &fds, NULL, &tv);
 *
 * An fd_set is a FIXED-SIZE BITMAP indexed by descriptor number — 1024 bits, 128 bytes on the
 * stack. FD_SET on a descriptor >= FD_SETSIZE writes past the end of it. That is undefined
 * behaviour; Android's FORTIFY detects it and calls __fortify_fatal, so the wallet ABORTS.
 *
 * Observed twice on a Note 8, both tombstones identical in shape:
 *     2026-08-03 06:28 and 2026-08-04 07:54
 *     #00 abort  #01 __fortify_fatal  #02 __FD_SET_chk+100  #03 _BRPeerOpenSocket
 *
 * THE TRAP THAT MAKES IT NASTY: the abort fires at descriptor NUMBER 1024, while the process
 * rlimit on that device is 32768. So the wallet dies from descriptor pressure long before the OS
 * would ever return EMFILE — no warning, no errno, just SIGABRT. Anything that pushes the wallet
 * to ~1024 concurrent descriptors (a socket-lifetime leak under peer churn) reaches it.
 *
 * WHAT THIS PROVES. Occupy every descriptor below FD_SETSIZE, so the socket under test is
 * guaranteed to land above it, then wait on it.
 *   FIXED  (poll):   returns normally — poll() takes an explicit array and has no such ceiling.
 *   UNFIXED (-DFDSET_UNFIXED): ASan reports stack-buffer-overflow on the fd_set, which is exactly
 *                              the write Android's FORTIFY turns into an abort.
 *
 * This is a DETERMINISTIC red arm — no race, no timing. It fails 100% of the time when unfixed,
 * which is the standard the flakier gates in this suite should be held to.
 *
 * #includes BRPeer.c directly to reach the file-static _BRPeerWaitConnect, so BRPeer.c must NOT
 * also be passed as a separate compilation unit.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/resource.h>
#include <sys/socket.h>

#include "BRPeer.c"

/* Burn descriptors until the next one allocated is >= FD_SETSIZE. Returns the low-numbered
 * placeholders so they can be released; the caller keeps them open meanwhile. */
static int _occupyBelowFdSetSize(int **held, int *heldCount)
{
    struct rlimit rl;
    int cap = FD_SETSIZE + 64;

    if (getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur < (rlim_t)cap) {
        rl.rlim_cur = (rl.rlim_max < (rlim_t)cap) ? rl.rlim_max : (rlim_t)cap;
        if (setrlimit(RLIMIT_NOFILE, &rl) != 0) {
            printf("SKIP: cannot raise RLIMIT_NOFILE to %d (cur=%llu max=%llu)\n",
                   cap, (unsigned long long)rl.rlim_cur, (unsigned long long)rl.rlim_max);
            return -1;
        }
    }

    int *fds = calloc(FD_SETSIZE + 16, sizeof(int));
    int n = 0;
    while (n < FD_SETSIZE + 8) {
        int fd = open("/dev/null", O_RDONLY);
        if (fd < 0) break;
        fds[n++] = fd;
        if (fd >= FD_SETSIZE) break;   // we are now above the bitmap
    }

    *held = fds;
    *heldCount = n;
    return (n > 0 && fds[n - 1] >= FD_SETSIZE) ? fds[n - 1] : -1;
}

int main(void)
{
    int *held = NULL, heldCount = 0;

#ifdef FDSET_UNFIXED
    printf("ARM: UNFIXED (-DFDSET_UNFIXED) -- select()/FD_SET restored\n");
#else
    printf("ARM: FIXED (poll)\n");
#endif

    int highFd = _occupyBelowFdSetSize(&held, &heldCount);
    if (highFd < 0) {
        printf("SKIP: could not obtain a descriptor >= FD_SETSIZE (%d); held %d\n",
               FD_SETSIZE, heldCount);
        free(held);
        return 0;   /* environment limitation, not a failure of the code under test */
    }

    printf("FD_SETSIZE=%d  descriptor under test=%d  (%s)\n",
           FD_SETSIZE, highFd, highFd >= FD_SETSIZE ? "ABOVE the bitmap" : "below");

    /* A socketpair end, renumbered above FD_SETSIZE, is a real pollable descriptor that is
     * immediately writable — so the FIXED arm returns >0 promptly rather than waiting out the
     * timeout, and the UNFIXED arm still has to touch the bitmap to find that out. */
    int sp[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sp) != 0) {
        printf("SKIP: socketpair failed: %s\n", strerror(errno));
        free(held);
        return 0;
    }
    int testFd = fcntl(sp[0], F_DUPFD, FD_SETSIZE);
    if (testFd < 0) {
        printf("SKIP: could not dup above FD_SETSIZE: %s\n", strerror(errno));
        close(sp[0]); close(sp[1]); free(held);
        return 0;
    }
    printf("polling descriptor %d\n", testFd);

    /* UNFIXED: this call writes past the end of a 128-byte stack fd_set. */
    int r = _BRPeerWaitConnect(testFd, 0.25);

    printf("_BRPeerWaitConnect returned %d (errno=%d)\n", r, errno);

    close(testFd); close(sp[0]); close(sp[1]);
    for (int i = 0; i < heldCount; i++) close(held[i]);
    free(held);

    if (r < 0) {
        printf("ASSERTION FAILED: waiting on descriptor %d failed (%s) — a high descriptor "
               "number must not be an error\n", testFd, strerror(errno));
        printf("peer_fdset_overflow_kat: FAIL\n");
        return 1;
    }

    printf("peer_fdset_overflow_kat: PASS\n");
    return 0;
}
