// The two spend gates, the bridge's (bridge/spend_guard.h, bridge/signed_spend_gate.h) and the core's (BRSpendGate.h),
// compiled in one unit so both are reached through plain functions. run.sh compiles this unit with
//   -Dcoin_reservation_claim=kat_bridge_claim -DBRCoinReservationClaim=kat_core_claim
// so the claim each gate makes passes through a recorder in coin_reservation_core_kat_main.c, which calls the real
// claim and then, when a scenario asks, registers a conflicting send in the wallet (a send that registered between the
// gate's first check and its claim). The comparison arms (-DBR_*_UNFIXED, -DBR_SIGNED_SPEND_GATE_RED_*) reach only the
// core's header; the bridge's side is always the shipped code.
#include "signed_spend_gate.h"   // includes spend_guard.h and coin_reservation.h
#include "BRSpendGate.h"
#include "BRSpendGate.h"         // the include guard

int kat_bridge_guard(BRWallet *w, const BRTransaction *tx) { return spend_guard_inputs_unspent(w, tx); }
int kat_core_guard(BRWallet *w, const BRTransaction *tx)   { return BRSpendGuardInputsUnspent(w, tx); }
int kat_bridge_gate(BRWallet *w, BRTransaction *tx, double now) { return signed_spend_gate(w, tx, now); }
int kat_core_gate(BRWallet *w, BRTransaction *tx, double now)   { return BRSignedSpendGate(w, tx, now); }
