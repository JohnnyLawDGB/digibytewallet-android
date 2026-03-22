# Reproducible Build Verification

## Prerequisites
- Docker installed
- git installed

## Steps

1. Clone the repository:
   ```bash
   git clone --recursive https://github.com/JohnnyLawDGB/digibytewallet-android.git
   cd digibytewallet-android
   git checkout <TAG>
   git submodule update --init --recursive
   ```

2. Build the Docker image:
   ```bash
   docker build -t dgb-wallet-build docker/
   ```

3. Build the APK inside Docker:
   ```bash
   docker run --rm -v "$(pwd)":/build dgb-wallet-build \
     bash -c "./gradlew :app:assembleMainnetRelease && \
     sha256sum app/build/outputs/apk/mainnet/release/app-mainnet-release-unsigned.apk"
   ```

4. Record the SHA-256 hash.

5. Have independent builders repeat steps 1-4. All hashes must match.

6. Sign the verified APK with your GPG key:
   ```bash
   gpg --armor --detach-sign app/build/outputs/apk/mainnet/release/app-mainnet-release-unsigned.apk
   ```

## Verification for Existing Releases

Each GitHub release includes:
- The unsigned APK
- SHA-256 hash file
- GPG signatures from multiple independent builders
- The signed APK published to Google Play

To verify a release:
```bash
# Download the unsigned APK and hash file from the GitHub release
sha256sum -c digibyte-wallet-v*.sha256
# Verify GPG signatures
gpg --verify digibyte-wallet-v*.apk.asc
```
