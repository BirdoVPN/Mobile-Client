# Release log: process deviations

This log records times a release reached a channel ahead of the release rules. These are not
personal-data breaches; those go to the breach register (birdo-infra `deploy/INCIDENT-RESPONSE.md` §8).

The Android rule (2026-10-01): GitHub "Latest" is the newest build that Google Play's production track
shows at 100%. Until then a GitHub release stays a prerelease, so GitHub Latest, the F-Droid repo and the
in-app update banner (which all read full releases only) stay on the build every Play user can get.
`.github/workflows/github-latest-from-play.yml` enforces it.

## 2026-11-11 to 2026-11-16: 1.4.38, 1.4.39 and 1.4.40 were each GitHub Latest while Play's 100% build was 1.4.37

- **What happened.** Play's 100% production build was 1.4.37 (versionCode 10437) from 2026-11-09 until
  2026-11-16 06:32Z: every scheduled github-latest-from-play.yml probe in between shows
  `production completed versionCodes=['10437']`. Three releases went past it. For each, android.yml uploaded
  the build to Play at a 10% staged rollout and left its GitHub release as a draft prerelease, as designed.
  Minutes later the draft was published by hand as a full release, outside the workflow, so it became
  GitHub Latest and the F-Droid repo listed it:

  | Release | Play upload (10%) | GitHub full + Latest | F-Droid run |
  |---|---|---|---|
  | android-v1.4.38 | 2026-11-11 21:58Z | 2026-11-11 22:00:07Z | 43107964528 (release event), success |
  | android-v1.4.39 | 2026-11-12 19:23Z | 2026-11-12 19:24:25Z | 43289837553 (release event), success |
  | android-v1.4.40 | 2026-11-13 01:02Z (run 43332989208) | 2026-11-13 01:17:42Z | 43343821856 (release event) failed; dispatched run 43352491043 at 02:29Z succeeded |

  1.4.38 and 1.4.39 never reached 100% on Play: each was replaced by the next build while still at 10%
  (probes 43173724054 and 43295123082 show 10438, then 10439, `inProgress` at 0.1 with 10437 `completed`).
  1.4.40 was first seen at 100% by probe 43817446756 on 2026-11-16 at 06:32Z; the probe before it
  (43749180285, 2026-11-15 18:52Z) still showed it at 10%.
- **Impact.** From 2026-11-11 22:00Z to 2026-11-16 06:32Z, about 4.4 days, GitHub Latest, the F-Droid repo
  and the in-app update banner offered builds that Play served to at most 10% of its users; Play's 100% build
  stayed 1.4.37 throughout. No personal data was involved.
- **Why it was not corrected.** github-latest-from-play.yml only ever moved Latest forward: when Latest was
  newer than Play's 100% build it logged "Latest is android-v1.4.38 (later 1.4.39, then 1.4.40), newer than
  android-v1.4.37; leaving it" and changed nothing.
- **Decision (2026-11-18).** A release-log entry, not a breach-register entry (MR-2418).
- **Fix (MR-2418).** `.github/scripts/latest-follows-play.sh`, run by github-latest-from-play.yml every 6 h
  and whenever an Android release is published or edited by hand, sets any full android-v release that is
  ahead of Play's 100% build back to a prerelease, keeps Latest on Play's build, re-indexes F-Droid and fails
  the run so the deviation is seen. The draft release text now says to publish as a pre-release.
