# Publishing Lost Island on Google Play

What it actually takes, as of mid-2026.

## Account

- **$25 one-time** Google Play developer registration.
- A **personal** account (created after Nov 2023) cannot publish straight to
  production: you must first run a **closed test with ≥ 12 testers opted in
  continuously for 14 days**, then apply for production access from the
  Console dashboard. "Opted in" = accepted the invite *and* installed the
  app with that Google account — invites alone don't count.
- An **organization** account (needs a D-U-N-S number) skips the testing
  gate entirely.

## Technical requirements

- **AAB, not APK** — new apps upload an Android App Bundle
  (`gradle bundleRelease`), and **Play App Signing is mandatory**: Google
  holds the release key, you keep an upload key. The committed
  `sideload.keystore` is fine as an upload key, but treat Play builds as a
  separate signing config.
- **Target API level**: from **Aug 31 2026 new apps must target API 36
  (Android 16)**; existing apps need API 35 to stay visible. This project
  currently targets 34 for sideload friendliness — a Play build needs
  `targetSdk = 36` and a re-test of overlay + foreground-service behavior
  (both get stricter with each major).
- **Sensitive access must be justified in review**:
  - `SYSTEM_ALERT_WINDOW` (the overlay) — explain it *is* the product.
  - Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`) — declare
    what is read (media sessions, notification titles for the island) and
    that nothing leaves the device.
- **Privacy policy URL** is required because of the notification access —
  a one-page "everything stays on device, no data collected, lyrics
  fetched anonymously from lrclib.net" hosted anywhere public (the GitHub
  Pages site works).
- **Data safety form**: declare "no data collected, no data shared";
  mention the lrclib.net lookups (song title/artist sent, nothing personal).
- **Content rating questionnaire**: trivial (utility, no UGC).

## Store listing assets

- App icon 512×512 PNG (render from `data/lost-island.svg`)
- Feature graphic 1024×500
- ≥ 2 phone screenshots (island over home screen, expanded card, settings)
- Short (80 chars) + full (4000 chars) description — reuse the README copy.

## Realistic path from here

1. Create the developer account, pay the $25.
2. `gradle bundleRelease` with `targetSdk = 36`, upload to a **closed
   testing** track.
3. Recruit 12+ testers (friends, or the r/AndroidClosedTesting /
   tester-exchange communities exist exactly for this), keep them opted in
   14 days, keep shipping small updates meanwhile.
4. Apply for production access, answer the readiness questions, submit.

Total cost: $25 and roughly two-three weeks of waiting, most of it the
tester clock.
