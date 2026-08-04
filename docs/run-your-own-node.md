# Sync this wallet from your own DigiByte node

This wallet uses **BIP157/158 compact block filters**. It never sends its addresses to anyone —
instead it downloads a small filter per block and tests it locally. That is what makes it private,
and it is also why it is picky about peers: a node can only serve this wallet if it has the
**block filter index** built and is willing to serve it.

Almost no DigiByte node runs that today. That is a chicken-and-egg problem — nobody served filters
because no client asked, and no client could work because nobody served them. Two config lines on
a node you already run breaks it, for you and for everyone else.

## What you need

- **DigiByte Core 8.22 or newer.** 9.26.x is current. `digibyte-cli getnetworkinfo` reports yours.
- **An unpruned node.** The filter index is built by reading every block, so `prune=` and
  `blockfilterindex` are mutually exclusive. If you are pruned you will need a full sync to switch.
- **~5 GB of extra disk** for the index, and a few hours the first time it builds.
- **No `txindex` required.** It is unrelated; leave it however you have it.

## Configure

Add to `digibyte.conf` (`~/.digibyte/digibyte.conf` on Linux,
`%APPDATA%\DigiByte\digibyte.conf` on Windows, `~/Library/Application Support/DigiByte/` on macOS):

```conf
# Build the BIP158 basic filter index over every block.
blockfilterindex=1

# Serve those filters to peers that ask (BIP157 getcfilters / getcfheaders).
peerblockfilters=1
```

Restart the node. **Both lines are needed** — the first builds the index, the second is what makes
it available to your wallet. Setting only `blockfilterindex` is the common mistake: the index sits
there and the wallet still cannot use it.

> On a large node the restart itself can take a while — flushing chainstate and reloading is
> single-threaded and unrelated to these settings. RPC answers `error code: -28` until it is ready.
> That is normal; wait it out rather than restarting again.

## Confirm it is actually working

```console
$ digibyte-cli getindexinfo
{
  "basic block filter index": {
    "synced": true,
    "best_block_height": 23972517
  }
}
```

`"synced": true` and a `best_block_height` at the chain tip is the whole test. While the index is
still building, `synced` is `false` and the height climbs — the node is usable for everything else
in the meantime, just not yet for this wallet.

If `getindexinfo` shows nothing about block filters, `blockfilterindex` did not take. Check that
you edited the conf file the node actually reads (`digibyte-cli getrpcinfo` or the startup log
names it) and that it is not overridden by a command-line flag in your service unit.

## Point the wallet at it

The node must be reachable from your phone on the P2P port (**12024**). On the same LAN that means
allowing it through the host firewall:

```console
$ sudo ufw allow from 192.168.1.0/24 to any port 12024 proto tcp
```

Then, in the wallet: **Settings → Network Info → Own node**.

- Turn on **"Use my own node"** and enter `192.168.1.50:12024` (the port may be omitted; it
  defaults to 12024).
- **"Only my node (exclusive)"** makes the wallet talk to *nothing else*. That is the strongest
  privacy setting and the right one if your node is reliable and always on.

You can also pair by QR. The wallet accepts a `dgbnode://` URI:

```
dgbnode://192.168.1.50:12024?net=mainnet&label=Home%20node
```

`label` is cosmetic. `net` is `mainnet` or `testnet`. A bare `host:port` works too. Hostnames and
IPv4 are accepted; IPv6 and `.onion` are not yet.

### Before you turn on exclusive mode

Exclusive means **no fallback, by design**. If your node reboots, goes offline, or fills its
connection slots, the wallet has nowhere else to go and will simply stop syncing until the node is
back. That is the correct behaviour for a privacy setting, but it is worth knowing:

- Leave exclusive **off** while you are still confirming the node works.
- If your node is also serving other things, make sure it is not at its `maxconnections` ceiling.
  A node at its limit does not refuse cleanly — it accepts, completes the handshake, then sheds the
  connection, which looks like a flaky network rather than a full node.
  `digibyte-cli getconnectioncount` against `maxconnections` tells you.
- Consider reserving a slot for yourself with `whitelist=192.168.1.0/24`, which also exempts your
  own devices from being evicted when the node is busy.

## Serving the public (optional)

Everything above is about **your** wallet talking to **your** node over your own LAN. That needs no
inbound port forwarding and exposes nothing.

Serving filters to strangers is a separate decision. It genuinely helps — every public CF node makes
this wallet less dependent on any single operator's infrastructure — but understand what you are
taking on: inbound bandwidth, connection slots, and a node that is now a target. If you want to:

- Forward TCP **12024** and make sure `maxconnections` leaves real headroom.
- Keep the node patched. It is now reachable from the internet.
- Do not run it on the same box as anything whose uptime you care about.

## Troubleshooting

**The wallet finds the node but never syncs.** Check `peerblockfilters=1` specifically —
`blockfilterindex` alone builds the index without serving it.

**`getindexinfo` says `"synced": false` and the height is not moving.** The index build is I/O
bound and competes with normal block processing. Give it time; check the debug log for progress.

**It syncs, then stops.** Look at `getconnectioncount`. A saturated node accepts and then drops
connections, and under exclusive mode the wallet has no alternative to move to.

**Pruned node.** `blockfilterindex` cannot work without the blocks. There is no way around a full
resync for this.
