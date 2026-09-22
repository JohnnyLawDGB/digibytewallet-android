// base_builder.c -- the DigiDollar transfer builder EXACTLY as it stands in the base
// core commit 5b45756, with only its function name changed to
// BRWalletCreateDigiDollarTransfer_base. It is #included by dd_coin_selection_kat_main.c
// AFTER the live BRWallet.c, so it sees the same struct/statics/macros and links beside
// the current builder. run.sh regenerates this file from the base commit and fails if it
// has drifted, so the differential is always against the real base.
//
// DO NOT EDIT BY HAND -- it is a mechanical copy of the base function.
BRTransaction *BRWalletCreateDigiDollarTransfer_base(BRWallet *wallet, const uint8_t recipientKey32[32],
                                                uint64_t cents)
{
    assert(wallet != NULL); assert(recipientKey32 != NULL);
    if (! wallet->hasTaprootKey) return NULL;
    if ((int64_t)cents < DD_MIN_OUTPUT_CENTS || (int64_t)cents > DD_MAX_OUTPUT_CENTS) return NULL;

    struct _ddSel  { UInt256 hash; uint32_t n; int64_t c; uint8_t script[42]; size_t scriptLen; };
    struct _feeSel { UInt256 hash; uint32_t n; uint64_t amt; uint8_t script[42]; size_t scriptLen; };

    pthread_mutex_lock(&wallet->lock);

    // --- snapshot our DD UTXOs (hash, n, cents, scriptPubKey bytes), sort smallest-first ---
    size_t ddN = array_count(wallet->ddUtxos);
    struct _ddSel ddsel[ddN > 0 ? ddN : 1];
    size_t m = 0;
    for (size_t i = 0; i < ddN; i++) {
        BRTransaction *dt = BRSetGet(wallet->allTx, &wallet->ddUtxos[i].hash);
        if (! dt) continue;
        uint32_t n = wallet->ddUtxos[i].n;
        if (n >= dt->outCount || dt->outputs[n].scriptLen > sizeof(ddsel[m].script)) continue;
        int64_t c = BRDigiDollarOutputAmount(dt, n);
        if (c <= 0) continue;
        ddsel[m].hash = wallet->ddUtxos[i].hash; ddsel[m].n = n; ddsel[m].c = c;
        ddsel[m].scriptLen = dt->outputs[n].scriptLen;
        memcpy(ddsel[m].script, dt->outputs[n].script, ddsel[m].scriptLen);
        m++;
    }
    for (size_t i = 1; i < m; i++) { // insertion sort ascending by cents
        struct _ddSel k = ddsel[i]; size_t j = i;
        while (j > 0 && ddsel[j-1].c > k.c) { ddsel[j] = ddsel[j-1]; j--; }
        ddsel[j] = k;
    }
    uint64_t selDD = 0; size_t ddIn = 0;
    for (size_t i = 0; i < m && selDD < cents; i++) { selDD += (uint64_t)ddsel[i].c; ddIn++; }
    if (selDD < cents) { pthread_mutex_unlock(&wallet->lock); return NULL; }        // insufficient DD
    uint64_t ddChange = selDD - cents;
    if (ddChange != 0 && ((int64_t)ddChange < DD_MIN_OUTPUT_CENTS ||
                          (int64_t)ddChange > DD_MAX_OUTPUT_CENTS)) {
        pthread_mutex_unlock(&wallet->lock); return NULL;   // sub-$1 dust or >$100k change -- fail closed
    }

    // --- snapshot DGB fee UTXOs; DD_MIN_FEE floor dominates the size-based estimate ---
    size_t feeN = array_count(wallet->utxos);
    struct _feeSel feesel[feeN > 0 ? feeN : 1];
    size_t fm = 0; uint64_t dgbIn = 0, fee = DD_MIN_FEE, feePerKb = wallet->feePerKb;
    for (size_t i = 0; i < feeN; i++) {
        BRUTXO *o = &wallet->utxos[i];
        BRTransaction *ut = BRSetGet(wallet->allTx, o);
        if (! ut || o->n >= ut->outCount || ut->outputs[o->n].scriptLen > sizeof(feesel[fm].script)) continue;
        feesel[fm].hash = ut->txHash; feesel[fm].n = o->n; feesel[fm].amt = ut->outputs[o->n].amount;
        feesel[fm].scriptLen = ut->outputs[o->n].scriptLen;
        memcpy(feesel[fm].script, ut->outputs[o->n].script, feesel[fm].scriptLen);
        dgbIn += feesel[fm].amt; fm++;
        size_t est = 10 + ddIn*57 + fm*68 + 3*TX_OUTPUT_SIZE + 32; // DD in + fee in + ~3 outs + OP_RETURN
        fee = _txFee(feePerKb, est); if (fee < DD_MIN_FEE) fee = DD_MIN_FEE;
        if (dgbIn >= fee) break;
    }
    if (dgbIn < fee) { pthread_mutex_unlock(&wallet->lock); return NULL; }          // insufficient DGB for fee
    uint64_t dgbChange = dgbIn - fee;

    pthread_mutex_unlock(&wallet->lock);

    // --- build the tx UNLOCKED (these helpers take wallet->lock internally) ---
    uint64_t dust = BRWalletMinOutputAmount(wallet);
    BRAddress ddCa = BR_ADDRESS_NONE, dgbCa = BR_ADDRESS_NONE;
    if (ddChange > 0) {
        BRWalletUnusedAddrs(wallet, &ddCa, 1, 1, 2);                 // internal taproot change (we own it)
        if (ddCa.s[0] == '\0') return NULL;                          // change addr must resolve -- fail closed
    }
    int emitDgb = (dgbChange >= dust);
    if (emitDgb) { BRWalletUnusedAddrs(wallet, &dgbCa, 1, 1, 1); emitDgb = (dgbCa.s[0] != '\0'); }

    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;

    uint8_t rspk[34] = { 0x51, 0x20 }; memcpy(rspk + 2, recipientKey32, 32);
    BRTransactionAddOutput(tx, 0, rspk, 34);                         // vout0 recipient (verbatim, no re-tweak)
    if (ddChange > 0) {
        uint8_t cspk[42]; size_t cl = BRAddressScriptPubKey(cspk, sizeof(cspk), ddCa.s);
        BRTransactionAddOutput(tx, 0, cspk, cl);                     // vout1 DD change, value 0
    }
    if (emitDgb) {
        uint8_t dspk[42]; size_t dl = BRAddressScriptPubKey(dspk, sizeof(dspk), dgbCa.s);
        BRTransactionAddOutput(tx, dgbChange, dspk, dl);            // DGB change
    }
    uint8_t orr[32]; size_t ol = 0;                                  // OP_RETURN LAST
    orr[ol++]=0x6a; orr[ol++]=0x02; orr[ol++]=0x44; orr[ol++]=0x44; orr[ol++]=0x01; orr[ol++]=0x02;
    uint8_t enc[9]; size_t el = BRDigiDollarWriteScriptNum((int64_t)cents, enc);
    orr[ol++] = (uint8_t)el; memcpy(orr + ol, enc, el); ol += el;
    if (ddChange > 0) { el = BRDigiDollarWriteScriptNum((int64_t)ddChange, enc);
                        orr[ol++] = (uint8_t)el; memcpy(orr + ol, enc, el); ol += el; }
    BRTransactionAddOutput(tx, 0, orr, ol);

    for (size_t i = 0; i < ddIn; i++)                                // DD inputs at value 0
        BRTransactionAddInput(tx, ddsel[i].hash, ddsel[i].n, 0, ddsel[i].script, ddsel[i].scriptLen,
                              NULL, 0, NULL, 0, TXIN_SEQUENCE);
    for (size_t i = 0; i < fm; i++)                                  // DGB fee inputs at real value
        BRTransactionAddInput(tx, feesel[i].hash, feesel[i].n, feesel[i].amt, feesel[i].script,
                              feesel[i].scriptLen, NULL, 0, NULL, 0, TXIN_SEQUENCE);

    return tx;   // NO shuffle (output order is consensus-significant)
}
