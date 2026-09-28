# In-app update looks frozen at 100%, and the install needs two taps (2026-09-27)

**Report (user on v4.0.8):** the in-app update to v4.0.83 "reaches 100% then freezes"; after cancelling, the wallet re-syncs the whole chain.

## Findings (reproduced on an API 35 emulator with Chrome)

- **Not a signing or SDK problem.** v4.0.8 and v4.0.83/84 share package `io.digibyte`, the same signing certificate and minSdk 26; the update installs in place once it reaches the installer.
- **The updater has no installer.** "Download Update" opens the release's `.apk` URL in the browser (`ACTION_VIEW`) and closes the dialog (`UpdateDialog.kt`, at v4.0.8 and on `develop`). The wallet cannot see progress or failures after that.
- **Chrome's finish looks like a freeze.** "File might be harmful" → *Download anyway* → a "File downloaded (72 MB) … Open" bar for a few seconds → then a blank github.com page with no progress and no install prompt ([1](1-blank-page-after-download.png)). Missing that bar looks exactly like a download stuck at 100%.
- **The install needs two taps of the file.** Tapping the APK in Downloads hits Android's "install unknown apps" block ([2](2-unknown-sources-block.png)); after *Allow from this source* ([3](3-allow-from-this-source.png)) and Back, Android returns to the Downloads list, not the installer ([4](4-back-to-downloads-list.png)) — the file has to be tapped again.
- **The full re-sync is the old version's own behaviour.** Builds before the 2026-08-19 recreate fix rebuilt the chain from the wallet's birth checkpoint; the remaining pull-to-refresh case is fixed in #98. Updating removes it.

## Done
- Install steps added at the top of the v4.0.84 GitHub release notes. Every in-app update dialog, back to v4.0.8, shows the latest release's notes, so users stuck on old versions see them now. **Keep this block at the top of every release body:**

  > **Updating from inside the app?** The download opens in your browser. When it finishes, open **Downloads** and tap `digibyte-wallet-vX.Y.Z.apk`. If Android asks, allow installs from your browser, then go back and **tap the file again** to install. Trouble? Use [digiscope.me/downloads](https://digiscope.me/downloads/).

## To do
1. Update dialog: a visible "Having trouble? digiscope.me/downloads" link, plus one line explaining the browser's download bar and the tap-again step.
2. A real in-app updater: download in the app with progress, check the published SHA-256, open the package installer directly, deep-link to the "allow installs from this app" switch and return to the installer, fall back to the website link on any failure. Needs a short design pass (`REQUEST_INSTALL_PACKAGES`; interaction with Google's developer verification for sideloaded installs).
