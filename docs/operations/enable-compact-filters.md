# Enable BIP 157/158 Compact Block Filters on Your DigiByte Node

**Who this is for:** anyone running a DigiByte Core **8.26** full node on
Linux, Windows, or macOS who wants to help mobile SPV wallets sync
privately. Five-minute config change, one restart, then a few hours of
passive index building. No downtime for your existing peers.

## TL;DR

Add these two lines to your `digibyte.conf` and restart the node:

```
blockfilterindex=basic
peerblockfilters=1
```

Wait a few hours for the filter index to build. You're done.

## Ports: no changes needed

Compact filter messages travel over the existing DigiByte P2P protocol
on the same TCP connection as block and transaction messages. **No new
ports, no firewall changes.** Mainnet stays on 12024, testnet on
12026. The only wire-visible difference after you enable the flags is
that your node's version handshake advertises an additional service
bit (`NODE_COMPACT_FILTERS`, 0x40) alongside `NODE_BLOOM` / `NODE_NETWORK`.
Mobile SPV clients check that bit and know they can request filters
from you.

If your node already accepts inbound connections on 12024, you're
already reachable for filter serving.

## Before you start — check three things

### 1. You're on DigiByte Core 8.26 or newer

```bash
digibyte-cli getnetworkinfo | grep subversion
```
Expect something like `"subversion": "/Satoshi:8.26.0/"`. Older than
8.26 doesn't support this — upgrade first.

### 2. You're not running a pruned node

```bash
digibyte-cli getblockchaininfo | grep pruned
```
Must be `"pruned": false`. Compact filters require the full block chain
on disk; a pruned node can't build the index. (You'd also get an init
error on next restart if you tried.)

### 3. You have room for ~6–12 GB more disk

The filter index sits alongside the block data. Check free space:

- Linux / macOS: `df -h ~/.digibyte` (or wherever your data directory lives)
- Windows: open `%APPDATA%\DigiByte` in File Explorer and check the drive's free space

If you're under ~15 GB of headroom, make space before continuing.

---

## Linux — systemd service (most common)

### 1. Find your config file

Typically one of:
```
~/.digibyte/digibyte.conf          # if you run as your login user
/root/.digibyte/digibyte.conf      # if you run as root
/home/digibyte/.digibyte/digibyte.conf   # dedicated user
```

### 2. Add the two lines

```bash
sudo nano ~/.digibyte/digibyte.conf
```
(adjust the path for your user). At the end of the file, add:
```
blockfilterindex=basic
peerblockfilters=1
```
Save and exit (Ctrl+X, Y, Enter in nano).

### 3. Restart the daemon

```bash
sudo systemctl restart digibyted
```
If your unit is named differently (`digibyte`, `dgbd`, etc.), use that.

### 4. Watch the index build

```bash
sudo journalctl -u digibyted -f
```
Look for `Initializing block filter index` and periodic
`BlockFilterIndex is enabled` + progress lines. Ctrl+C to stop
tailing — the build continues in the background.

To check progress from the RPC:
```bash
digibyte-cli getindexinfo
```
You'll see:
```json
{
  "blockfilterindex": {
    "synced": false,
    "best_block_height": 14500000
  }
}
```
`synced: false` with a climbing `best_block_height` means it's working.
Expect 4–12 hours depending on disk speed (SSD ~4h, slow spinning rust
can run all day).

## Linux — running the binary directly (`./digibyted`)

If you don't use systemd and just launch `digibyted` manually:

```bash
# 1. Edit the config (same as above)
nano ~/.digibyte/digibyte.conf

# 2. Stop the running daemon gracefully
digibyte-cli stop

# 3. Wait until it's fully stopped (until you get "Connection refused")
while digibyte-cli getblockchaininfo 2>/dev/null; do sleep 2; done

# 4. Start it back up
./digibyted -daemon

# 5. Watch the log
tail -f ~/.digibyte/debug.log | grep -i filter
```

---

## Windows

### 1. Find your config file

Open File Explorer and paste this into the address bar:
```
%APPDATA%\DigiByte
```
You'll see `digibyte.conf` there. If it doesn't exist, create it — a
plain text file, no `.txt` extension.

### 2. Add the two lines

Right-click `digibyte.conf` → **Open with** → **Notepad**. At the end,
add:
```
blockfilterindex=basic
peerblockfilters=1
```
Save.

### 3. Restart the node

**If you run DigiByte Core as a desktop app (digibyte-qt.exe):**
- File → Exit.
- Wait for the window to fully close (can take 30–60 s as it flushes).
- Relaunch the app from the Start menu.

**If you run `digibyted.exe` as a Windows service:**
- Press `Win+R`, type `services.msc`, Enter.
- Find "DigiByte Core" (or similar), right-click → **Restart**.

### 4. Watch the index build

Open a Command Prompt:
```cmd
cd %APPDATA%\DigiByte
digibyte-cli.exe getindexinfo
```
Same meaning as on Linux — `synced: false` with increasing height means
it's working. Re-run every 10 minutes to watch progress.

---

## macOS

Config file at `~/Library/Application Support/DigiByte/digibyte.conf`.
Same two lines. Restart via:
```bash
digibyte-cli stop
# wait for it to stop, then:
open -a DigiByte-Qt
# or if running headless:
./digibyted -daemon
```

---

## Verify it's working

Once `getindexinfo` shows `synced: true`, run these three checks:

### 1. Index is ready to serve

```bash
digibyte-cli getindexinfo
```
```json
{
  "blockfilterindex": {
    "synced": true,
    "best_block_height": 23310XXX
  }
}
```

### 2. You can produce a filter for any block

```bash
digibyte-cli getblockfilter $(digibyte-cli getbestblockhash)
```
Expect a JSON blob with `"filter": "<hex string>"` and
`"header": "<hex string>"`. Any error here means the index isn't
actually serving — re-check config.

### 3. You're advertising `NODE_COMPACT_FILTERS` to peers

On the node itself:
```bash
digibyte-cli getnetworkinfo | grep localservices
```
Look for `"localservicesnames"` including `"COMPACT_FILTERS"`.

You can also verify from a second node connected to yours:
```bash
digibyte-cli getpeerinfo | grep -A5 "<your-node-ip>"
```
The peer entry should show `"services"` with bit 6 set (the hex
`services` value, ANDed with `0x40`, will be non-zero — `409` or higher
depending on other bits).

---

## What changes for your node

**Disk:** ~6–12 GB added over the full 23 M-block chain. Grows slowly
with each new block (tiny, a few KB per block).

**CPU during initial build:** noticeable for the first 4–12 hours.
Single-threaded. After that, negligible — just updating on each new
block as they arrive.

**Memory:** negligible. A few MB of cache.

**Bandwidth:** slight increase — you'll serve filter data to light
clients that ask. A new cfheaders request is ~65 KB, a new cfilters
request up to ~50 KB. If mobile wallets adopt this, you might see an
extra few MB per day of outbound. Not significant on any broadband
connection.

**Regular block serving:** unaffected. Your node keeps doing everything
it was doing; filter serving is additive.

---

## Troubleshooting

**"Error: Prune mode is incompatible with -blockfilterindex."**
You have `prune=<n>` set. Either remove that line (and wait for a full
re-sync — lots of disk), or don't enable filters on this node.

**"Permission denied" opening digibyte.conf**
On Linux, the file is owned by whichever user runs the daemon. Use
`sudo` to edit, or `sudo chown` it to yourself temporarily.

**`getblockfilter` returns "Index is not enabled for filtertype basic"**
The daemon restarted but the index is still building. Wait for
`getindexinfo` to report `synced: true`.

**The node seems to be doing nothing after restart**
Look at the debug log (`~/.digibyte/debug.log` on Linux/macOS,
`%APPDATA%\DigiByte\debug.log` on Windows) — filter build messages
include `BlockFilterIndex`. Tail the log; if you see no activity at
all, the daemon may not have restarted cleanly. `digibyte-cli
getnetworkinfo` will refuse-connect if the daemon is actually down.

**Index build is taking way longer than 12 hours**
On very slow storage (USB drives, network shares) this is normal. SSDs
do it in hours; an external USB 2.0 drive can take a day or two.

---

## Reporting back

Once your node is serving filters, please let the operator of
[digiscope.me](https://digiscope.me) know — they're building a mobile
wallet (DigiByte Wallet for Android) that uses BIP 157/158 to sync
without revealing user addresses to any third party. Every additional
filter-serving node diversifies the peer set users can bootstrap from.

Questions / issues with the setup: open an issue at
[github.com/JohnnyLawDGB/digibytewallet-android](https://github.com/JohnnyLawDGB/digibytewallet-android/issues)
or reach out directly to the node operators who shared this guide.

---

## Further reading

- [BIP 157 — Client Side Block Filtering](https://github.com/bitcoin/bips/blob/master/bip-0157.mediawiki)
- [BIP 158 — Compact Block Filters for Light Clients](https://github.com/bitcoin/bips/blob/master/bip-0158.mediawiki)
- [DigiByte Core 8.26 release notes](https://github.com/DigiByte-Core/digibyte/releases/tag/v8.26.0)
