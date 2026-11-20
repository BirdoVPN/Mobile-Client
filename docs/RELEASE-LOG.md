# Release log: process deviations

This log records times a release reached a channel ahead of the release rules. These are not
personal-data breaches; those go to the breach register (birdo-infra `deploy/INCIDENT-RESPONSE.md` §8).

The Android rule (2026-10-01): GitHub "Latest" is the newest build that Google Play's production track
shows at 100%. Until then a GitHub release stays a prerelease, so GitHub Latest, the F-Droid repo and the
in-app update banner (which all read full releases only) stay on the build every Play user can get.
`.github/workflows/github-latest-from-play.yml` enforces it.

## 2026-11-13: android-v1.4.40 was GitHub Latest three days before Play reached 100%

- **What happened.** android.yml run 43332989208 uploaded 1.4.40 to Play at a 10% staged rollout
  (2026-11-13 01:02Z) and left its GitHub release as a draft prerelease, as designed. At 01:17:42Z the
  draft was published as a full release from the maintainer account, outside the workflow, so it became
  GitHub Latest. The F-Droid repo listed it after a dispatched run at 02:29Z. Play reached 100% on 2026-11-16 at 06:32Z.
- **Impact.** For about three days, people on the GitHub or F-Droid channel could install 1.4.40 while Play
  offered it to 10% of users. It was the same signed build Play was serving. No personal data was involved.
- **Why it was not corrected.** github-latest-from-play.yml only ever moved Latest forward: when Latest was
  newer than Play's 100% build it logged "leaving it" and changed nothing.
- **Decision (2026-11-18).** A release-log entry, not a breach-register entry (MR-2418).
- **Fix (MR-2418).** `.github/scripts/latest-follows-play.sh`, run by github-latest-from-play.yml every 6 h
  and whenever an Android release is published or edited by hand, sets any full android-v release that is
  ahead of Play's 100% build back to a prerelease, keeps Latest on Play's build, re-indexes F-Droid and fails
  the run so the deviation is seen. The draft release text now says to publish as a pre-release.
