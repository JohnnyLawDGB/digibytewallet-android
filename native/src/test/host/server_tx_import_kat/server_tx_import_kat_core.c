// The core side of server_tx_import_kat: BRServerTxImport.h compiled in a translation unit of its own, so a
// comparison arm can change one call inside the header's procedure and nothing else in the suite.
//
// run.sh builds this file with -DBRPeerManagerApplyServerHeight=kat_apply, as it builds jni_transaction.c, so both
// sides reach the manager through the same recorder (server_tx_import_kat_main.c).
//
// Comparison arms (never defined in a production build; they exist only in this file):
//   KAT_RED_KEEP_UNPROVABLE  the import does not remove a tx it registered when the height's answer is UNPROVABLE
//   KAT_RED_NO_DUP_CHECK     the import and the confirm do not look up the wallet's existing record of the hash
//   KAT_RED_NO_FREE_REFUSED  a tx the wallet refused is not freed: BRWalletRegisterOutcomeHeld, the header's only
//                            reading of who owns the parsed tx after its registration (REGISTER-RESULT), always answers
//                            that the wallet holds it, so the free is skipped. Seen only by the leak checker.
//   KAT_RED_FREE_HELD        the opposite (DEVMERGE-KAT-NITS): BRWalletRegisterOutcomeHeld always answers no, so a tx
//                            the wallet kept is freed under it. Seen by AddressSanitizer as a use-after-free.
#include "BRWallet.h"
#include "BRPeerManager.h"

#ifdef KAT_RED_KEEP_UNPROVABLE
#define BRWalletRemoveTransaction(wallet, hash) ((void)(wallet), (void)(hash))
#endif
#ifdef KAT_RED_NO_DUP_CHECK
#define BRWalletCopyTransactionForHash(wallet, hash) ((void)(wallet), (void)(hash), (BRTransaction *)NULL)
#endif

#ifdef KAT_RED_NO_FREE_REFUSED
#define BRWalletRegisterOutcomeHeld(outcome) ((void)(outcome), 1)
#endif
#ifdef KAT_RED_FREE_HELD
#define BRWalletRegisterOutcomeHeld(outcome) ((void)(outcome), 0)
#endif

#include "BRServerTxImport.h"
#include "BRServerTxImport.h"   // twice: the include guard holds

BRServerTxImportResult kat_core_import(BRWallet *wallet, BRPeerManager *manager, const uint8_t *buf, size_t len,
                                       int64_t blockHeight, int64_t blockTimestamp, BRServerTxUnprovableNote *note,
                                       BRServerTxHeightLog *log)
{
    return BRServerTxImport(wallet, manager, buf, len, blockHeight, blockTimestamp, note, log);
}

int kat_core_confirm(BRWallet *wallet, BRPeerManager *manager, UInt256 txHash, int64_t blockHeight,
                     int64_t blockTimestamp, BRServerTxUnprovableNote *note, BRServerTxHeightLog *log)
{
    return BRServerTxConfirm(wallet, manager, txHash, blockHeight, blockTimestamp, note, log);
}

uint32_t kat_core_take(BRServerTxUnprovableNote *note) { return BRServerTxUnprovableNoteTake(note); }
