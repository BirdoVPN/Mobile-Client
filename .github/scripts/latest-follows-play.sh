#!/usr/bin/env bash
# GitHub "Latest", and every full Android release, follows Google Play's 100% production build.
# Run by .github/workflows/github-latest-from-play.yml with the read-only Play probe's output.
#
# Owner rule 2026-10-01: GitHub Latest for Android is the newest build that Play's production track
# shows as `completed` (100% rollout). Owner 2026-11-18 (MR-2418): Latest moves only once Play is at
# 100%. From 2026-11-11 to 2026-11-16 android-v1.4.38, 1.4.39 and 1.4.40 were each published by hand as a
# full release while Play had them at 10% and Play's 100% build stayed 1.4.37; this job's old "Latest is
# newer than Play; leaving it" branch kept them Latest for about 4.4 days (docs/RELEASE-LOG.md). The
# F-Droid index and the in-app update banner read full releases too, so they moved with it.
#
# So, against Play's 100% build (P):
#   1. every full (published, non-prerelease) android-vX.Y.Z release NEWER than P is set back to a
#      prerelease, whether or not it is Latest; that is what keeps Latest, F-Droid and the banner behind Play;
#   2. P is made a full release and Latest, forward or back;
#   3. F-Droid is re-indexed when 1 or 2 changed anything (a GITHUB_TOKEN edit triggers no workflow);
#   4. a step-1 demotion fails the run (exit 1): the deviation is corrected AND shown, never silently.
# No completed production release on Play: nothing to compare against, nothing is changed (exit 0).
# A GitHub API failure fails the run (set -e), never "nothing to do".
#
# Usage: latest-follows-play.sh <probe-output-file>   (GITHUB_REPOSITORY and GH_TOKEN set; gh on PATH)
# Test:  bash .github/scripts/latest-follows-play-test.sh
set -euo pipefail

probe="${1:?usage: latest-follows-play.sh <probe-output-file>}"
repo="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"
[ -r "$probe" ] || { echo "::error::probe output $probe is not readable"; exit 1; }

# android-vX.Y.Z -> versionCode (major*10000 + minor*100 + patch, as app/build.gradle.kts); anything else -> empty
code_of() {
  printf '%s\n' "$1" | sed -nE 's/^android-v([0-9]+)\.([0-9]+)\.([0-9]+)$/\1 \2 \3/p' |
    awk '{ print $1 * 10000 + $2 * 100 + $3 }'
}

# Probe lines look like:
#   production   completed    versionCodes=['10430'] userFraction=-
#   production   inProgress   versionCodes=['10431'] userFraction=0.1
vc=$(awk '$1 == "production" && $2 == "completed"' "$probe" | grep -oE '[0-9]{5,}' | sort -n | tail -1 || true)
if [ -z "$vc" ]; then
  echo "No completed production release on Play; nothing to do."
  exit 0
fi
tag="android-v$((vc / 10000)).$(( (vc / 100) % 100 )).$((vc % 100))"
echo "Play production at 100%: versionCode $vc -> $tag"

changed=0
demoted=""

# 1. Full releases ahead of Play go back to prerelease. The list is newest first; 100 covers every
#    release this repository has (65 on 2026-11-20), and a premature one is always recent.
full=$(gh release list -R "$repo" --limit 100 --json tagName,isDraft,isPrerelease \
  --jq '.[] | select(.isDraft == false and .isPrerelease == false) | .tagName')
while IFS= read -r t; do
  [ -n "$t" ] || continue
  c=$(code_of "$t")
  [ -n "$c" ] || continue
  if [ "$c" -gt "$vc" ]; then
    echo "::error::$t is a full GitHub release, but Play production has only $tag at 100%; setting $t back to a prerelease (Latest waits for Play, MR-2418)"
    gh release edit "$t" -R "$repo" --prerelease=true
    demoted="$demoted $t"
    changed=1
  fi
done <<EOF
$full
EOF

# 2. Play's 100% build is a full release and Latest.
if gh release view "$tag" -R "$repo" >/dev/null 2>&1; then
  latest=$(gh api "repos/$repo/releases/latest" --jq .tag_name 2>/dev/null || echo none)
  if [ "$latest" = "$tag" ]; then
    echo "$tag is already Latest."
  else
    echo "Latest is $latest; moving it to $tag."
    gh release edit "$tag" -R "$repo" --draft=false --prerelease=false
    id=$(gh api "repos/$repo/releases/tags/$tag" --jq .id)
    gh api -X PATCH "repos/$repo/releases/$id" -f make_latest=true --jq '"marked \(.tag_name) Latest"'
    changed=1
  fi
else
  echo "::warning::$tag has no GitHub release; nothing to mark"
fi

# 3. F-Droid indexes full releases only, so it must be rebuilt after either change.
if [ "$changed" -eq 1 ]; then
  gh workflow run fdroid.yml -R "$repo" --ref main
  echo "Dispatched fdroid.yml."
fi

# 4. A demotion is a release-process deviation: corrected above, and the run goes red so it is seen.
if [ -n "$demoted" ]; then
  echo "::error::Set back to prerelease:$demoted. A full Android release must wait until Play has it at 100%; publish drafts as prereleases (MR-2418)."
  exit 1
fi
