# Verifying HoldFast Wallet

This is the one copy of the instructions for checking that the wallet you have is the one we
published. README, BUG-BOUNTY, RELEASING and docs/SIGNING link here instead of repeating them.

Every build of the app, from Google Play or from a sideload download, is signed by one release
key. Its certificate's SHA-256 is public:

```
7E:36:DD:B2:85:61:B7:85:6A:D0:B6:27:BF:15:65:39:F8:B5:7F:97:24:21:48:D5:65:09:0C:B0:AA:8E:DF:87
```

`apksigner` prints it in lower case without colons:
`7e36ddb28561b7856ad0b627bf156539f8b57f97242148d565090cb0aa8edf87`. The same value is in
[docs/SIGNING.md](docs/SIGNING.md) and in `scripts/build-release.sh`, which refuses to keep a
release build signed by anything else.

The two ways to install the app are checked differently:

| You installed from | What you check | Compare it with |
|---|---|---|
| A sideload APK (releases repository or digiscope.me) | the file's SHA-256, **and** its signing certificate | the release's `SHA256SUMS` (or the SHA-256 in its release notes), and the certificate above |
| Google Play | the signing certificate **only** | the certificate above |

**A Play install cannot be checked against `SHA256SUMS`.** Play does not install the APK we
publish. It generates split APKs from the app bundle (`.aab`) for your phone's CPU, screen
density and languages, so the files on your phone have different contents, and therefore
different hashes, from the universal sideload APK, and from another phone's. A hash mismatch on a
Play install is expected and tells you nothing. What does not change is the signer: Play signs
every split with the same release key (Play App Signing is set to "use your own key", see
[docs/SIGNING.md](docs/SIGNING.md)). That is also why a Play install and a sideload APK can update
each other.

## Sideload APK

Download `aroundtheblock-wallet-vX.Y.Z.apk` from the
[releases repository](https://github.com/JohnnyLawDGB/aroundtheblock-wallet-releases/releases/latest)
(digiscope.me links the same file). Before installing it:

1. **Hash.** Compare the file's SHA-256 with the one published for that release, in its
   `SHA256SUMS` file or its release notes:

   ```bash
   sha256sum aroundtheblock-wallet-vX.Y.Z.apk
   # or, with the release's SHA256SUMS downloaded into the same folder:
   sha256sum -c --ignore-missing SHA256SUMS
   ```

   `SHA256SUMS` also lists the Play bundle (`app-mainnet-play-release.aab`), which is not offered
   for download; `--ignore-missing` skips it. The line that matters is
   `aroundtheblock-wallet-vX.Y.Z.apk: OK`.

2. **Signer.** The hash proves you have the file the release page describes. The certificate
   proves who signed it, independently of that page:

   ```bash
   apksigner verify --print-certs aroundtheblock-wallet-vX.Y.Z.apk
   ```

   It must print exactly one signer, whose `certificate SHA-256 digest` is
   `7e36ddb28561b7856ad0b627bf156539f8b57f97242148d565090cb0aa8edf87`, and no error. `apksigner`
   ships with the Android SDK build-tools.

If either check fails, do not install the file, and tell us (see [SECURITY.md](SECURITY.md)).

## Google Play install

Check the signing certificate of the installed app. With USB debugging on and the Android SDK
installed:

```bash
adb shell pm path app.aroundtheblock.wallet      # lists base.apk and the split APKs
adb pull <the base.apk path printed above> base.apk
apksigner verify --print-certs base.apk
```

The `certificate SHA-256 digest` must be
`7e36ddb28561b7856ad0b627bf156539f8b57f97242148d565090cb0aa8edf87`. Every split is signed by the
same key, and you can pull and check each one the same way. Do not compare `base.apk`, or any
split, with `SHA256SUMS`: as explained above, those hashes never match on a Play install.

Without a computer, Android enforces the same thing from the other side: it installs an update
only when it is signed by the same key as the installed app. If Android accepts a verified
sideload APK as an update over your Play install, both are signed by the same key.

## Building from source

Releases are built and signed on the publisher's machine, never in CI
([RELEASING.md](RELEASING.md)). You can build the same source yourself:

```bash
git clone --recursive https://github.com/JohnnyLawDGB/digibytewallet-android.git
cd digibytewallet-android
git checkout vX.Y.Z
git submodule update --init --recursive
docker build -t dgb-wallet-build docker/
docker run --rm -v "$(pwd)":/build dgb-wallet-build \
  bash -c "./gradlew :app:assembleMainnetSideloadRelease"
```

Without the release key this produces an unsigned APK. Its hash can never equal the published
APK's, because the published file carries a signature; independent builders can compare their
unsigned builds with each other. We do not currently publish third-party build attestations or
GPG signatures for releases, so for a published release the checks that apply are the two above:
the hash (sideload only) and the signing certificate (every install).
