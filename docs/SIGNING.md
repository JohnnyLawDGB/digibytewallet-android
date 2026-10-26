# Signing app.aroundtheblock.wallet

Every build of this app, from Google Play or from the digiscope.me download, is signed by one key. Android installs an update
only when it is signed by the same key as the installed app, so this key *is* the app's identity. Lose it, and no existing
install can ever be updated again. Leak it, and anyone can ship an "update" that empties wallets.

## Why there is a new app and a new key

The wallet used to be `io.digibyte`. Google Play holds the signing key for `io.digibyte` (fingerprint `11:65:11:B1…8D:7F`), and
that key is not ours; it belongs to the original DigiByte wallet. Our own releases were signed with a different key (`BD:6A:E2…19:32`)
and could only be sideloaded, never published on Play under that name.

The fix is a new app with its own name and a key that its publisher controls from day one:
- applicationId `app.aroundtheblock.wallet`;
- publisher Around The Block LLC.

Users move across by restoring their recovery phrase. Nothing moves on chain.

## The key

| | |
|---|---|
| Created | 2026-10-04, offline, with `scripts/create-signing-key.sh` |
| Type | RSA 4096, PKCS12, valid until 2054-02-19 |
| Alias | `aroundtheblock-wallet` |
| Subject | `CN=Around The Block LLC, O=Around The Block LLC, L=Tulsa, ST=Oklahoma, C=US` |
| SHA-256 (public) | `7E:36:DD:B2:85:61:B7:85:6A:D0:B6:27:BF:15:65:39:F8:B5:7F:97:24:21:48:D5:65:09:0C:B0:AA:8E:DF:87` |

The fingerprint and the certificate (`aroundtheblock-wallet-cert.pem`) are public. Paste them anywhere: Play's developer
verification, `assetlinks.json`, issue trackers.

The `.p12` file and its password are not public. The rules:
- The file lives on the owner's machine and on **two offline backups kept in different places**.
- The password lives in a password manager, **never next to a copy of the file**.
- Prove each backup works: `keytool -list -keystore <copy> -alias aroundtheblock-wallet` must ask for the password and print
  the SHA-256 above.
- Never email it, never put it in a chat, a cloud drive, CI secrets or a repository.
  - `.gitignore` excludes `*.p12`, `*.jks`, `*.keystore`, `*.pfx`, `*.pepk` and `keystore.properties`.
  - CI runs `scripts/check-no-keystore-tracked.sh`, which also catches a key that has been renamed.

**CI never signs** (owner decision 2026-10-05). The same key signs the Play app, so a stolen CI secret would sign updates for
every user. CI builds and tests; releases are built and signed on the owner's machine.

## Play App Signing: "use your own key"

Play offers two modes:

1. **Google generates and keeps the app-signing key.** You sign uploads with a separate upload key. This is the easiest mode,
   but then only Google holds the key that installed copies trust. A sideloaded APK signed by us would have a *different*
   signature, and a sideloaded install could never update from Play, or the other way round.
2. **You upload your own app-signing key** (encrypted with Google's PEPK tool). Play signs installs with *our* key, the same one
   that signs the digiscope.me APK. A Play install and a sideloaded install are then the same app, and either can update the
   other.

We use **mode 2**, because the wallet is also distributed outside Play.

## Building a release

1. Point the build at the key. Use either environment variables for one shell:

   ```sh
   export ATB_SIGNING_STORE_FILE=~/aroundtheblock-wallet-keys/aroundtheblock-wallet.p12
   read -rs ATB_SIGNING_STORE_PASSWORD && export ATB_SIGNING_STORE_PASSWORD   # typed, not in history
   ```

   or `keystore.properties` at the repo root. It is gitignored, and should be readable only by you (`chmod 600`):

   ```properties
   storeFile=/home/<you>/aroundtheblock-wallet-keys/aroundtheblock-wallet.p12
   storePassword=<the password>
   # keyAlias defaults to aroundtheblock-wallet; keyPassword defaults to storePassword
   ```

   Without either, every debug and test build works as usual. A release build stops with instructions; it never falls back
   to the debug key.

2. From a clean checkout of the commit you are releasing, run:

   ```sh
   scripts/build-release.sh
   ```

   The script:
   - runs the release gates (security cycle, no tracked keys, JNI table);
   - builds the Play bundle and the sideload APK;
   - refuses to keep either one unless it is signed by exactly the key above.

   It writes the following to `dist/v<version>/`:
   - `app-mainnet-play-release.aab`, for Play;
   - `aroundtheblock-wallet-v<version>.apk`, for the sideload release;
   - both R8 mappings, which you **keep with the release**, because a crash report is unreadable without them;
   - `SHA256SUMS`.

The `play` flavor contains no self-updater, because Play forbids an app it distributes from updating itself any other way. The
`sideload` flavor checks `JohnnyLawDGB/aroundtheblock-wallet-releases` for new versions. That is deliberately not this source
repository: `io.digibyte` installs read this repository's releases with no tag filter.

## First upload to Play: PEPK (once)

At the first release, Play Console asks how to sign the app. Choose **"Use your own key" / "Export and upload a key from Java
keystore"**. Do **not** let Google generate one. Then:

1. Download `pepk.jar` and Google's encryption public key from that page.
2. Run the command Play shows, on your machine. It looks like this; use Play's exact flags if they differ:

   ```sh
   java -jar pepk.jar \
     --keystore=$HOME/aroundtheblock-wallet-keys/aroundtheblock-wallet.p12 \
     --alias=aroundtheblock-wallet \
     --output=$HOME/aroundtheblock-wallet-keys/pepk-out.zip \
     --include-cert \
     --rsa-aes-encryption \
     --encryption-key-path=$HOME/Downloads/encryption_public_key.pem
   ```

   PEPK asks for the keystore and key passwords. The zip contains the key encrypted to Google, so only Google can read it.
3. Upload the zip. Then upload `app-mainnet-play-release.aab` from `scripts/build-release.sh`. The same key is the upload key
   until you decide to register a separate one.
4. Delete `pepk-out.zip`. It is useless to anyone but Google, but there is no reason to keep more copies of anything derived
   from the key.

Play Console → App integrity should then show the app-signing certificate SHA-256 above. If it shows anything else, stop: Play
generated its own key, and sideloaded installs will not be able to update from Play.

## Checking a signature

```sh
apksigner verify --print-certs <file>.apk        # "certificate SHA-256 digest" must be 7e36ddb2…aa8edf87
keytool -printcert -jarfile <file>.aab            # "SHA256:" must be 7E:36:DD:…:DF:87
```

Users can check a sideloaded APK the same way before installing it.

## If the key leaks, or must be replaced

Treat a leak as an incident, not a chore. Anyone holding the key can sign an update that installed copies will accept.

1. Generate a new key with `scripts/create-signing-key.sh` into a new directory.
2. Rotate with APK Signature Scheme v3:
   - `apksigner rotate --out lineage.bin --old-signer --ks <old.p12> --new-signer --ks <new.p12>`;
   - then sign releases with the new key plus `--lineage lineage.bin`.
   Android 9+ installs then accept the new key and can be told to stop trusting the old one.
3. In Play Console → App integrity, request an app-signing key upgrade with the same proof of rotation.
4. Publish a release explaining what happened. Rotation limits what the old key can do from then on; it cannot recall updates
   that the old key already signed.

Do not "fix" a lost key by publishing under the same name with a new key. Android refuses the update, and that is how
`io.digibyte` ended up stranded.
