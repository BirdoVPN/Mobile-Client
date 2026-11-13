# macOS Resolution Center reply - Guideline 2.1(b) (draft)

Rejected: version 1.4.27 (10427), reviewed 2026-09-18. Apple listed one issue only:
Guideline 2.1(b), the In-App Purchase products were not submitted. Apple also asked for a new binary.

Post this in the Resolution Center thread, NOT in the "Notes for Review" field (a reply in review notes is
not read as a reply). Post it only after:

1. the new build has finished processing in App Store Connect,
2. the four subscriptions are attached to the version, each with its review screenshot (the version page
   lists all four), and
3. the sandbox window is open (APPLE_ALLOW_SANDBOX_ENTITLEMENTS_UNTIL, owner step, short UNTIL).

Replace `<NEW_VERSION>` and `<NEW_BUILD>` with the numbers ASC shows. Do not send this before the build
exists; do not name a build that has not been device-tested.

---

Thank you for the review.

**Guideline 2.1(b) - In-App Purchase products not submitted.**
All four of our auto-renewable subscriptions (Operative monthly, Operative yearly, Sovereign monthly,
Sovereign yearly, subscription group "Birdo VPN Plans") are now attached to this version, and each one has
an App Review screenshot of the in-app purchase screen. They are submitted together with this binary.

We have also uploaded a new binary, version `<NEW_VERSION>` (build `<NEW_BUILD>`), as you asked. It
replaces 1.4.27 (10427).

To reach the purchase screen: open the app, go to the **Profile** tab and open the subscription card (Plans). The products load from the App Store and are purchasable with a sandbox account.

For completeness, two earlier points that were raised on a previous version (1.4.23) and that this build
addresses:

- **Guideline 4 (design).** The app now has one window only (no File > New Window), and every window is
  pulled fully inside its screen's usable area, below the menu bar and above the Dock, on launch and each
  time a window becomes active, including when macOS restores a saved window position.
- **Guideline 5.1.1(v) (registration).** No account is needed to connect. On first launch the app shows a
  privacy disclosure (accept it or choose "Not now"), then the main screen with the **Connect** button.
  Pressing Connect creates an anonymous device identity automatically, with no form, e-mail or password.
  Sign in, including Sign in with Apple, is optional. Account deletion is in Profile and also revokes the
  Sign in with Apple token.

If anything here does not behave as described on your device, please tell us and we will fix it at once.

---

## Notes for the owner, not for Apple

- The anonymous-account mint cap was raised to 25 per device per 24 h on 2026-09-06 by a production env
  value (the code default in registration-gate.service.ts is still 5). Confirm the env value is still set before submitting: a reviewer who reinstalls several times must not hit a 429
  on Connect.
- Per-IP cap is 3 anonymous accounts per hour. A failed mint brings the registration form back, so avoid
  testing from a shared office IP just before submitting.
- The four subscriptions ride in ONE submission at a time. If iOS is still carrying them (state
  WAITING_FOR_REVIEW or IN_REVIEW), the Mac picker will not offer them. Check the state first
  (`macos-iap-submission-clickpath.md`, 2026-09-09 section). If they are already Approved, they are not
  re-attached and the reply wording above should say "approved" instead of "attached".
- Mac plan limits in the paywall are 1/3/5 devices (Mobile #536); the 36-territory allow-list applies to
  the one ASC app record, so check Pricing and Availability for macOS.
