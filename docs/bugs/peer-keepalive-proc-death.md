# BUG: SyncService silent stall after background process death

**Status:** open · root cause identified, fix not yet shipped
**First reproduced:** v3.5.12 (2026-04-16)
**Device:** Samsung SM-N950U, Android 9 (API 28)
**Previously tracked as:** "peer-keepalive silent stall — root cause unproven"
(memory: `project_open_bugs_mitigated.md`). Unprovenness now closed.

## Symptom

Users returning to a backgrounded app see `peers=0` and the "No DigiByte
peers connected — can't broadcast right now" banner on Send/Receive. The
peer-keepalive loop never ticks to recover; only `force-stop + relaunch`
restores connectivity.

UI was still responsive during the stall (`WalletViewModel.pollNativeBalance`
emitted every 60s), but `SyncService` was entirely silent for ~10 minutes.

## Root cause

```
04-16 06:02:00.097 am_finish_activity: [io.digibyte/.MainActivity,
                    proc died without state saved]
04-16 06:02:00.097 ActivityManager: Scheduling restart of crashed service
                    io.digibyte/.service.SyncService in 1000ms
```

**The whole app process was killed**, not just the service. Likely culprit:
Android OOM-killer reaping the backgrounded app under memory pressure.
`SyncService` is `START_STICKY` and Android scheduled a restart ~1s later,
but:

1. Restart fires with `intent=null`; the new instance's `syncAlreadyLaunched`
   is false and the keepalive launch runs — but the process can die again
   under the same memory pressure before it stabilizes.
2. The restart loop is invisible because there's no active logger in the
   dead process.
3. If the user foregrounds the app before the service has re-attached, the
   UI sees `peers=0` and the keepalive never tick in the *current* process
   instance.

We drop the foreground-service notification after sync completes
(`SyncService.onSyncComplete`, ~line 380s), which is called out in the code
as an intentional UX choice but makes the app a weaker priority for the OOM
killer.

## Proposed fixes (impact-ordered)

1. **Keep a persistent foreground notification.** Downgrade to a minimal
   "Connected — X peers" notification post-sync instead of removing it.
   Oreo+ requires foreground services to have a notification; living within
   that constraint also makes the process stickier.
2. **UI-level watchdog.** `WalletViewModel.pollNativeBalance` runs every 5s
   in the UI process. If it observes `peers=0` for >60s, fire a broadcast
   that re-binds and re-starts the service. The UI process was still alive
   in the reproducer — only the service had died. This is defensive but
   effective and low-risk.
3. **WorkManager expedited catch-up.** The existing 15-min `SyncWorker`
   could be upgraded to `setExpedited` and triggered on return-to-foreground
   to catch service-death cases.
4. **Shrink idle memory footprint.** We keep large block/peer buffers
   around; could compress or evict when backgrounded so the OOM killer
   passes us over.

## Reproduction

1. Install v3.5.12 on an API 28 device with ~6 GB RAM.
2. Launch app, let it sync to tip, observe `peers > 0`.
3. Background the app; use other memory-heavy apps (browser with many tabs,
   video apps) for 15–30 min.
4. Return to DGB Wallet → Send screen.
5. Observe "No DigiByte peers connected" banner and no peer reconnection.

## Fix target

v3.5.13 or v3.6.0 — probably bundle (1) + (2) as the minimum viable fix.
