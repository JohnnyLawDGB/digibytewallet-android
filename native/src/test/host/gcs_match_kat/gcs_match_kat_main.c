// KAT: BIP158 GCS match against a REAL DigiByte block filter.
// Block 23828952 (mainnet): filter 031d7c28902538d848 (N=3), two P2PKH output
// scriptPubKeys that are provably in the block. A correct BIP158 basic-filter
// matcher MUST report hit for both. Tests both block-hash byte orders to locate
// the bug (our on-device wallet reported hit=0 for this exact 9-byte filter).
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include "BRGCSFilter.h"
#include "BRInt.h"

static int hb(char c){ if(c>='0'&&c<='9')return c-'0'; if(c>='a'&&c<='f')return c-'a'+10; if(c>='A'&&c<='F')return c-'A'+10; return 0; }
static size_t unhex(const char*s, uint8_t*o, size_t m){ size_t n=strlen(s)/2, i; if(n>m)n=m; for(i=0;i<n;i++) o[i]=(uint8_t)((hb(s[i*2])<<4)|hb(s[i*2+1])); return n; }

int main(void){
    const char *filterHex = "031d7c28902538d848";
    const char *bhDisplay = "b25bdf50d8543afd03279f6207dd4b8d135263695b98912c02346c3b88092ae7";
    const char *spk1 = "76a91416d3d7f528ba1af0a4e8b4b6d5d40991abbaa7be88ac";
    const char *spk2 = "76a9140628c8a924f46d91712c857cd1d0fbb5c44bc2f488ac";

    uint8_t filter[64]; size_t flen = unhex(filterHex, filter, sizeof(filter));
    uint8_t disp[32]; unhex(bhDisplay, disp, 32);
    uint8_t s1[64]; size_t l1 = unhex(spk1, s1, sizeof(s1));
    uint8_t s2[64]; size_t l2 = unhex(spk2, s2, sizeof(s2));

    UInt256 hDisp; memcpy(hDisp.u8, disp, 32);          // RPC/display byte order
    UInt256 hWire; for (int i=0;i<32;i++) hWire.u8[i]=disp[31-i];  // internal/wire (reversed)

    BRGCSFilter *fD = BRGCSFilterBasicParse(filter, flen, hDisp);
    BRGCSFilter *fW = BRGCSFilterBasicParse(filter, flen, hWire);
    printf("filter %s parsed (display=%s wire=%s)\n", filterHex, fD?"ok":"NULL", fW?"ok":"NULL");

    int dW1 = fW?BRGCSFilterMatch(fW,s1,l1):-1, dW2 = fW?BRGCSFilterMatch(fW,s2,l2):-1;
    int dD1 = fD?BRGCSFilterMatch(fD,s1,l1):-1, dD2 = fD?BRGCSFilterMatch(fD,s2,l2):-1;
    printf("WIRE-order    blockhash (what BRGCSFilterBasicParse assumes): spk1=%d spk2=%d\n", dW1, dW2);
    printf("DISPLAY-order blockhash:                                      spk1=%d spk2=%d\n", dD1, dD2);

    int ok = (dW1==1 && dW2==1);
    printf(ok ? "\nPASS: wire-order matches (matcher is correct)\n"
              : "\nFAIL: block's own scripts do NOT match under wire-order (matcher/keying bug)\n");
    if (!ok && (dD1==1 || dD2==1)) printf("  -> but DISPLAY-order matches: block-hash byte order is inverted\n");
    return ok ? 0 : 1;
}
