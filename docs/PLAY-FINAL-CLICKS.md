# Play launch — the final Console clicks (everything else is automated)

State as of 2026-07-07: org account live, app transferred, App Signing active,
`assetlinks.json` live, screenshots + graphics + listing copy in-repo, AAB
build/upload automated on tag, **store listing publishable via the
"Play Store Listing" workflow**. What remains is ONLY what Google exposes no
API for — about 20–30 minutes of Console UI:

## 0. One-time sanity (2 min)
Run **Actions → Play Store Listing → mode=probe**. It must print the tracks and
`service account OK`. If it 401/403s: Play Console → **Users and permissions →
Invite user** → add the service-account email (from the JSON) with *Release
manager* on `app.birdo.vpn`, wait ~15 min, re-run. (App transfers to the org
account do not carry API grants over.)

## 1. Publish the listing (1 min, automated)
Actions → **Play Store Listing → mode=apply**. This pushes title, short/full
description, icon, feature graphic and the 5 phone screenshots from
`store-assets/`. Re-run any time the assets change.

## 2. Data safety (Console → App content → Data safety)
Answer exactly per `docs/PLAY-LAUNCH-PLAN.md` **§4b** (email + device IDs +
crash logs collected, nothing shared, no activity/location/payment; in-transit
encryption yes; deletion URL `https://birdo.app/delete-account`).

## 3. Content rating (Console → App content → Content rating)
Questionnaire as **Utility**; the unrestricted-internet question lands it at
Teen/PEGI 12. Per §4c.

## 4. App access (Console → App content → App access)
"All or some functionality is restricted" → add one instruction set:
- Anonymous ID: `838568571611737459262143` (Anonymous tab on the login screen)
- Password: re-run `_local/artifacts/make-reviewer.js` on prod to (re)set it,
  then paste. Account has an active SOVEREIGN plan.
- Note: "Subscriptions are purchased on our website; the app is login-only.
  The test account above has an active plan."

## 5. Remaining declarations (Console → App content)
Privacy policy `https://birdo.app/privacy` • Ads: **No** • News: No •
Government app: No • Financial features: No • Target audience: 18+.

## 6. Ship it
1. The internal-track AAB is already uploaded by CI on every `android-v*` tag
   (v1.3.40 = the current main).
2. Console → Test and release → **Internal testing** → verify the build is
   there → **Promote release → Production**.
3. Choose a **staged rollout** (10% → 50% → 100%).
4. **Submit for review** (the API cannot do this for this app —
   `changesNotSentForReview`). VPN review typically takes 1–7 days.

That's the whole launch. Everything on this page except the four questionnaire
/credential screens is one workflow-dispatch or one tag away.
