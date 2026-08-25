# Play Store readiness — what has to be true before we publish

**25 August 2026 · brief, not a plan.** Nothing here is scheduled; it exists so the
order of operations is known before someone starts.

## The headline

**CI does not build what Play accepts.** The release workflow produces
`app-mainnet-release.apk` and nothing else. Google has required an **Android App Bundle
(`.aab`)** for new apps since August 2021 — an APK cannot be uploaded for a new listing at
all. This is a blocker, not a detail, and it is the first thing to fix.

Everything else below is small by comparison.

---

## 1. There will be a THIRD signing fingerprint, and Brian needs it

This is the one that will bite silently if missed.

**Play re-signs the app.** Under Play App Signing you upload with an *upload key*; Google
strips that signature and re-signs with an *app signing key* it holds. So the certificate on
a Play-installed wallet is **not** either of the ones we ship today.

Our released APK already carries **two** certificates — the release key has been rotated, so
there is a v3 signing lineage:

```
BD:6A:E2:E5:6A:70:D2:8E:17:AB:D9:6B:34:CC:BB:DB:AB:F0:C9:F3:C4:3C:29:51:F4:F9:7C:05:5C:7F:19:32
9E:E6:F1:36:87:34:86:90:D1:16:A6:60:57:D4:EB:7E:F5:C6:5A:4B:A9:09:6F:63:CB:AF:58:43:30:11:88:38
```

Those cover sideloaded installs from `digiscope.me/downloads` and GitHub. A Play install needs
a third, from **Play Console → Setup → App integrity → App signing key certificate → SHA-256**.

**Why it matters and how it will look when it's wrong:** `assetlinks.json` on
`assets.digistamp.co` is what makes a digistamp link open the wallet instead of the browser.
Android verifies it against the *installed* app's certificate. With the Play fingerprint
missing, App Links keep working for everyone who sideloaded and silently fail for everyone who
installed from Play — and the obvious conclusion will be that Brian's file is broken. It is
not; it is incomplete.

Brian already made `ANDROID_APP_FINGERPRINTS` comma-separated for exactly this. Send all three
at once rather than amending later.

**Order matters:** the Play fingerprint does not exist until the app has been created in Play
Console and a bundle uploaded. So: create the listing → get the fingerprint → send to Brian →
*then* the Play build is link-complete.

## 2. R8 mapping must reach Play

Release ships obfuscated as of v4.0.56, and CI archives `mapping.txt` per release. Play wants
its own copy so crash reports in Console are deobfuscated — with an AAB this can be bundled at
upload. Without it, every Play crash report is unreadable, and unlike a local mapping it cannot
be reconstructed afterwards.

## 3. Things Play will ask about that this app actually does

Worth having answers ready rather than discovering them in review:

- **Financial features / crypto.** Play has a declaration for this. It is a non-custodial
  wallet: keys are generated and held on device, and there is no exchange, custody or fiat
  on-ramp. That is the honest and the favourable answer.
- **`QUERY_ALL_PACKAGES` or sensitive permissions** — verify what the manifest actually
  requests before filling in the data-safety form, not from memory.
- **Data safety.** The wallet's compact-filters-only design means the address set never leaves
  the device. `getAddressHoldings` was deliberately deleted in v4.0.57 rather than left unused
  precisely so this claim stays true. Digi-ID sends one identity address and a signature to a
  site the user chose; the Hub uses a session token. All of that is disclosable and defensible,
  but it needs stating accurately.
- **Target API level.** Currently `targetSdk 35`; Play enforces a moving minimum, so confirm
  against the requirement in force at submission.

## 4. versionCode is already monotonic

`40058` at the time of writing, incrementing by one per release, driven from
`app/build.gradle.kts`. Play requires strictly increasing values and never permits reuse — no
change needed, just do not reset it.

## 5. The sideload channel stays

`digiscope.me/downloads` and GitHub releases are not replaced by Play. A wallet whose users can
only obtain it from one gatekeeper is a worse wallet, and the sideload path is also the one
that proves reproducibility. Play becomes an additional channel, not the channel.

---

## Suggested order

1. Add an AAB to the release workflow (blocker — nothing else can proceed)
2. Create the Play Console listing, upload a bundle to an internal track
3. Take the app-signing SHA-256 from Console
4. Send all three fingerprints to Brian
5. Verify on a Play-installed build that a digistamp link opens the wallet

Steps 3–5 cannot happen before 1–2, which is the main reason this brief exists.
