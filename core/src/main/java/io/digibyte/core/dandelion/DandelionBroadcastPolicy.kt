package io.digibyte.core.dandelion

/** Min/max embargo window (ms). After a stem-submit the wallet waits a random
 *  delay in [MIN, MAX]; if the tx hasn't been relayed back by then it self-fluffs.
 *  ~10–30s ≈ expected network stem→fluff time plus margin. */
const val EMBARGO_MIN_MS = 10_000L
const val EMBARGO_MAX_MS = 30_000L

/** Stem only when the user has Dandelion on AND a capable peer is connected;
 *  otherwise flood (today's behavior). */
fun shouldStem(enabled: Boolean, hasDandelionPeer: Boolean): Boolean =
    enabled && hasDandelionPeer

/** After the embargo, fluff iff no peer has relayed the tx back — i.e. the stem
 *  node dropped it / it didn't propagate. relayCount>0 means it's already spreading. */
fun shouldFluffAfterEmbargo(relayCount: Int): Boolean = relayCount == 0

/** Whether the stranded-send sweep may flood a recorded, unconfirmed send: not while this
 *  process's embargo for it is still running. The embargo owns a fresh stem, and a flood then
 *  would announce it from this wallet to every peer. Once the embargo has run (or after a
 *  restart, when there is none), an unconfirmed send is republished as before.
 *
 *  Deliberately NOT keyed on the relay count: the core counts the stem peer's own getdata as a
 *  relay, so a stem that dies at the stem peer reads as propagated (Note 8, 2026-09-25: relay
 *  count 1 within 4 s, never reached the network). Trusting it here stranded the send. */
fun shouldSweepRepublish(embargoPending: Boolean): Boolean = !embargoPending

/** Map a uniform [0,1] CSPRNG draw to the embargo window. Caller supplies the
 *  random (SecureRandom) so this stays pure/testable. */
fun embargoDelayMs(rng01: Double): Long {
    val clamped = rng01.coerceIn(0.0, 1.0)
    return EMBARGO_MIN_MS + (clamped * (EMBARGO_MAX_MS - EMBARGO_MIN_MS)).toLong()
}
