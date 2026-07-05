// Golden vector: BRDigiDollar.c decodes a REAL testnet26 DigiDollar TRANSFER.
// Source: DigiByte testnet26, block 83946, txid
//   3d8797cb87f6903bceeea28e6366093faec34af629e051dfda7b3a9616c5a346
// (captured 2026-07-05 from a v9.26.3 node). version 0x02000770; OP_RETURN
//   6a 02 4444 01 02 02c409 024c1d  => "DD" type2 amounts [2500, 7500] cents.
// vout0/vout1 zero-value P2TR (the two DD tokens), vout2 is 2.88 DGB change
// (non-zero, must be skipped), vout3 the OP_RETURN.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include "BRTransaction.h"
#include "BRDigiDollar.h"
static int g=0; static void ck(int c,const char*d){printf(c?"PASS: %s\n":"FAIL: %s\n",d); if(!c)g++;}
static const char *HEX = "70070002000102c831f28e22826503174ad58ad774e8210b4148c09879c48a68280a308a3c61ea0100000000fffffffff18057367c04eacb4f4cc762e9a1808d3732e473cdac1621cef3447b1055dca50000000000ffffffff040000000000000000225120effc01464e19f45d27d3fd670e62f4b21a441e894bc9cd1726b99ca8c02000cd000000000000000022512055e1ba29a8289e01ba58d4101fa8985650f4d2d2f20193a6bd5da99d7d3064a300882a110000000022512055e1ba29a8289e01ba58d4101fa8985650f4d2d2f20193a6bd5da99d7d3064a300000000000000000c6a024444010202c409024c1d0140254bd6882c3308f50d10f435c71df63bdad5c44bb56168a2ca031b5d45cf742114b53d4c51fac01cf29a3c5567861cd2ef85395b0188c0b0b438af9a813c281b0140d74bfc3623042619dc236ed0144b69c0484410175f02bd9592573428e4b483024215e194215f43ba7aefcffb54832bfde53cd44e0ff9af802bb8029922df228400000000";
int main(void){
    size_t n=strlen(HEX)/2; uint8_t *b=malloc(n);
    for(size_t i=0;i<n;i++){unsigned x;sscanf(HEX+2*i,"%2x",&x);b[i]=(uint8_t)x;}
    BRTransaction *tx=BRTransactionParse(b,n);
    ck(tx!=NULL,"parse real testnet26 DD transfer"); if(!tx){printf("\nFATAL\n");return 1;}
    ck(BRDigiDollarTxType(tx)==2,"type == TRANSFER(2)");
    int64_t amt[8]; int c=BRDigiDollarDecodeAmounts(tx,amt,8);
    ck(c==2 && amt[0]==2500 && amt[1]==7500,"amounts == [2500,7500] cents");
    ck(BRDigiDollarOutputAmount(tx,0)==2500,"vout0 (0-val P2TR) -> ordinal0 2500");
    ck(BRDigiDollarOutputAmount(tx,1)==7500,"vout1 (0-val P2TR) -> ordinal1 7500");
    ck(BRDigiDollarOutputAmount(tx,2)==-1,"vout2 (2.88 DGB change) -> not DD");
    ck(BRDigiDollarOutputAmount(tx,3)==-1,"vout3 (OP_RETURN) -> not DD");
    BRTransactionFree(tx); free(b);
    printf(g==0?"\nALL PASS (real on-chain bytes)\n":"\n%d FAIL\n",g); return g?1:0;
}
