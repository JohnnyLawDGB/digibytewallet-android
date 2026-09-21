// orphan_set_limits_kat -- companion unit. It #includes BRPeer.c (run.sh compiles this file
// IN PLACE OF BRPeer.c), so the rig can hand its peer a version message through the real,
// file-static handler. That is the one way a peer's announced best height is ever set, and
// BRPeerLastBlock() returns exactly that value.
#include <string.h>
#include <time.h>

#include "BRPeer.c"

// Delivers a well-formed version message that announces `bestHeight`. Returns the handler's
// result (1 = accepted). The rig's peer has no socket, so the reply the handler sends goes
// nowhere, as with every other message the rig's peer sends.
int kat_peer_accept_version(BRPeer *peer, uint32_t bestHeight)
{
    uint8_t msg[85];   // the shortest well-formed version message: an empty user agent
    size_t off = 0;

    memset(msg, 0, sizeof(msg));
    UInt32SetLE(&msg[off], PROTOCOL_VERSION);        off += sizeof(uint32_t);
    UInt64SetLE(&msg[off], 0);                       off += sizeof(uint64_t);   // services
    UInt64SetLE(&msg[off], (uint64_t)time(NULL));    off += sizeof(uint64_t);   // timestamp
    off += sizeof(uint64_t) + sizeof(UInt128) + sizeof(uint16_t);               // receiving side
    off += sizeof(uint64_t) + sizeof(UInt128) + sizeof(uint16_t);               // sending side
    UInt64SetLE(&msg[off], 0x0123456789abcdefULL);   off += sizeof(uint64_t);   // nonce
    msg[off++] = 0;                                                             // user agent length
    UInt32SetLE(&msg[off], bestHeight);              off += sizeof(uint32_t);

    return (off == sizeof(msg)) ? _BRPeerAcceptVersionMessage(peer, msg, sizeof(msg)) : 0;
}
