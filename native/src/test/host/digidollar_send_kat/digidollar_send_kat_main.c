#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include "BRDigiDollar.h"
static int g=0; static void ck(int c,const char*d){printf(c?"PASS: %s\n":"FAIL: %s\n",d); if(!c)g++;}

int main(void){
    // real testnet TD golden vector
    const char *TD = "TD2z1nkvxPfrny6TNBnukvzrK1kGGens8Ds4NNLWUrFPc6H8ZXoC";
    uint8_t exp[32] = {
        0xdc,0xea,0x60,0x96,0x99,0x3f,0x47,0x81,0x40,0x2e,0x76,0x3c,0x9d,0x36,0x09,0x79,
        0xc3,0xcf,0x66,0xa4,0x38,0x18,0xc9,0x5b,0x90,0x87,0xf0,0x88,0xcf,0x62,0x63,0x1b };
    uint8_t key[32];
    ck(BRDigiDollarAddressDecode(key, TD, 1) == 1, "decode real TD address (testnet)");
    ck(memcmp(key, exp, 32) == 0, "decoded key == golden 32-byte key");
    // wrong network: TD is testnet, decoding as mainnet must fail (version mismatch)
    ck(BRDigiDollarAddressDecode(key, TD, 0) == 0, "TD rejected when isTestnet=0 (wrong version)");
    // corrupted checksum (flip last char) -> fail
    char bad[64]; strcpy(bad, TD); bad[strlen(bad)-1] = (bad[strlen(bad)-1]=='C'?'D':'C');
    ck(BRDigiDollarAddressDecode(key, bad, 1) == 0, "corrupted checksum -> fail");
    // a normal DGB address is not a DD address -> fail
    ck(BRDigiDollarAddressDecode(key, "dgb1q6hwtu62c3wmdmexdpgpwmcycc7htrhr0f5w62z", 1) == 0, "bech32 addr -> fail");
    // NULL-safe
    ck(BRDigiDollarAddressDecode(key, NULL, 1) == 0, "NULL addr -> fail");
    printf(g==0?"\nALL PASS\n":"\n%d FAIL\n",g); return g?1:0;
}
