#!/usr/bin/env bash
# Test for latest-follows-play.sh against a stub `gh` that keeps release state in files.
# Run: bash .github/scripts/latest-follows-play-test.sh   (exit 0 = all passed)
# LFP_SCRIPT=<path> runs the same cases against another implementation (used to show fail-before).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="${LFP_SCRIPT:-$here/latest-follows-play.sh}"
workflow="$here/../workflows/github-latest-from-play.yml"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fail=0
pass=0

# --- stub gh ---------------------------------------------------------------------------------------
# State: $GH_STUB_STATE/releases has one line per release, "<tag> <draft 0|1> <prerelease 0|1>";
# latest holds the Latest tag (empty = none); calls logs every call; dispatched one line per workflow
# run; a file named fail_list makes `gh release list` fail like an API error.
mkdir -p "$work/bin"
cat >"$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
set -u
S="$GH_STUB_STATE"
echo "gh $*" >>"$S/calls"
code() { printf '%s\n' "$1" | sed -nE 's/^android-v([0-9]+)\.([0-9]+)\.([0-9]+)$/\1 \2 \3/p' | awk '{ print $1*10000+$2*100+$3 }'; }
set_field() { # tag field value
  awk -v t="$1" -v f="$2" -v v="$3" '$1 == t { $f = v } 1' "$S/releases" >"$S/releases.new" && mv "$S/releases.new" "$S/releases"
}
auto_latest() { # what GitHub does when Latest stops being a full release: the newest full release
  best=""; bc=0
  while read -r t d p; do
    [ "$d" = 0 ] && [ "$p" = 0 ] || continue
    c=$(code "$t"); [ -n "$c" ] || continue
    if [ "$c" -gt "$bc" ]; then best="$t"; bc="$c"; fi
  done <"$S/releases"
  printf '%s\n' "$best" >"$S/latest"
}
if [ "$1" = release ] && [ "$2" = list ]; then
  [ -e "$S/fail_list" ] && { echo "HTTP 502: Bad Gateway" >&2; exit 1; }
  awk '$2 == 0 && $3 == 0 { print $1 }' "$S/releases"; exit 0
fi
if [ "$1" = release ] && [ "$2" = view ]; then
  grep -q "^$3 " "$S/releases"; exit $?
fi
if [ "$1" = release ] && [ "$2" = edit ]; then
  t="$3"; shift 3
  grep -q "^$t " "$S/releases" || { echo "release not found" >&2; exit 1; }
  for a in "$@"; do
    case "$a" in
      --draft=false) set_field "$t" 2 0 ;;
      --prerelease=false) set_field "$t" 3 0 ;;
      --prerelease=true) set_field "$t" 3 1; [ "$(cat "$S/latest")" = "$t" ] && auto_latest ;;
    esac
  done
  exit 0
fi
if [ "$1" = workflow ] && [ "$2" = run ]; then
  echo "$3" >>"$S/dispatched"; exit 0
fi
if [ "$1" = api ] && [ "$2" = -X ]; then # api -X PATCH repos/R/releases/<id> -f make_latest=true --jq ...
  id="${4##*/}"; t="${id#id-}"; printf '%s\n' "$t" >"$S/latest"; echo "marked $t Latest"; exit 0
fi
if [ "$1" = api ]; then
  case "$2" in
    */releases/latest) l=$(cat "$S/latest"); [ -n "$l" ] || { echo "gh: Not Found (HTTP 404)" >&2; exit 1; }; echo "$l"; exit 0 ;;
    */releases/tags/*) echo "id-${2##*/}"; exit 0 ;;
  esac
fi
echo "stub gh: unhandled call: $*" >&2
exit 2
STUB
chmod +x "$work/bin/gh"

# --- harness ---------------------------------------------------------------------------------------
n=0
setup() { # releases(one per line) latest probe
  n=$((n + 1))
  S="$work/case$n"; mkdir -p "$S"
  printf '%s\n' "$1" | sed '/^$/d' >"$S/releases"
  printf '%s\n' "$2" >"$S/latest"
  printf '%s\n' "$3" >"$S/probe"
  : >"$S/calls"; : >"$S/dispatched"
}
run() {
  rc=0
  PATH="$work/bin:$PATH" GH_STUB_STATE="$S" GITHUB_REPOSITORY=o/r bash "$script" "$S/probe" >"$S/out" 2>&1 || rc=$?
}
check() { # name condition-command...
  local name="$1"; shift
  if "$@"; then pass=$((pass + 1)); echo "ok:   $name"
  else fail=$((fail + 1)); echo "FAIL: $name"; sed 's/^/    out: /' "$S/out"; sed 's/^/    state: /' "$S/releases"; echo "    latest: $(cat "$S/latest")"
  fi
}
full_tags() { awk '$2 == 0 && $3 == 0 && $1 ~ /^android-v/ { print $1 }' "$S/releases" | sort | tr '\n' ' ' | sed 's/ $//'; }
latest_is() { [ "$(cat "$S/latest")" = "$1" ]; }
full_is() { [ "$(full_tags)" = "$1" ]; }
rc_is() { [ "$rc" -eq "$1" ]; }
rc_nonzero() { [ "$rc" -ne 0 ]; }
dispatched() { [ "$(wc -l <"$S/dispatched" | tr -d ' ')" -eq "$1" ]; }
no_edits() { ! grep -qE '^gh (release edit|api -X)' "$S/calls"; }

P_40="production   completed    versionCodes=['10440'] userFraction=-"
P_39="production   completed    versionCodes=['10439'] userFraction=-"
P_40_41="$P_40
production   inProgress   versionCodes=['10441'] userFraction=0.1"

# 1. Play has no completed production release: nothing is changed.
setup "android-v1.4.40 0 1" "" "production   inProgress   versionCodes=['10440'] userFraction=0.1"
run
check "no completed Play release: exit 0" rc_is 0
check "no completed Play release: no edits" no_edits
check "no completed Play release: no dispatch" dispatched 0

# 2. The normal path: Play reaches 100% on a prerelease; it becomes full and Latest.
setup "android-v1.4.39 0 0
android-v1.4.40 0 1" "android-v1.4.39" "$P_40"
run
check "Play 100% build is marked Latest" latest_is android-v1.4.40
check "Play 100% build is a full release" full_is "android-v1.4.39 android-v1.4.40"
check "forward move: exit 0" rc_is 0
check "forward move: F-Droid dispatched once" dispatched 1

# 3. Already in step: nothing to do.
setup "android-v1.4.39 0 0
android-v1.4.40 0 0
android-v1.4.41 0 1" "android-v1.4.40" "$P_40_41"
run
check "in step: exit 0" rc_is 0
check "in step: no edits" no_edits
check "in step: no dispatch" dispatched 0
check "in step: the 10% prerelease stays a prerelease" full_is "android-v1.4.39 android-v1.4.40"

# 4. The 2026-11-13 incident: 1.4.40 published by hand as full + Latest while Play had 1.4.39 at 100%.
setup "android-v1.4.38 0 0
android-v1.4.39 0 0
android-v1.4.40 0 0" "android-v1.4.40" "$P_39
production   inProgress   versionCodes=['10440'] userFraction=0.1"
run
check "incident: 1.4.40 set back to prerelease" full_is "android-v1.4.38 android-v1.4.39"
check "incident: Latest back on Play's 100% build" latest_is android-v1.4.39
check "incident: run fails so the deviation is seen" rc_is 1
check "incident: F-Droid re-indexed" dispatched 1
check "incident: the error names the demoted tag" grep -q '::error::.*android-v1.4.40' "$S/out"

# 5. Published full but with "Set as latest" unticked: not Latest, still served by F-Droid and the banner.
setup "android-v1.4.40 0 0
android-v1.4.41 0 0" "android-v1.4.40" "$P_40_41"
run
check "full-not-Latest ahead of Play: set back to prerelease" full_is "android-v1.4.40"
check "full-not-Latest ahead of Play: Latest unchanged" latest_is android-v1.4.40
check "full-not-Latest ahead of Play: run fails" rc_is 1
check "full-not-Latest ahead of Play: F-Droid re-indexed" dispatched 1

# 6. Play's 100% build has no GitHub release, and a newer one was published full: still demoted.
setup "android-v1.4.39 0 0
android-v1.4.41 0 0" "android-v1.4.41" "$P_40_41"
run
check "no release for Play's build: the newer full release is demoted" full_is "android-v1.4.39"
check "no release for Play's build: run fails" rc_is 1
check "no release for Play's build: warning names it" grep -q '::warning::android-v1.4.40 has no GitHub release' "$S/out"

# 7. Only android-vX.Y.Z tags are judged; other releases are left alone.
setup "android-v1.4.40 0 0
ios-v9.9.9 0 0
xray-source-android-v1.4.41 0 1
android-v1.4.41-rc1 0 0" "android-v1.4.40" "$P_40"
run
check "other tags: exit 0" rc_is 0
check "other tags: no edits" no_edits
check "other tags: ios and suffixed tags still full" grep -q '^ios-v9.9.9 0 0$' "$S/releases"

# 8. A Latest that is OLDER than Play still moves forward (unchanged behaviour).
setup "android-v1.4.30 0 0
android-v1.4.40 0 1" "android-v1.4.30" "$P_40"
run
check "older Latest moves forward" latest_is android-v1.4.40

# 9. The release list cannot be read: the run fails, never "nothing to do".
setup "android-v1.4.40 0 0
android-v1.4.41 0 0" "android-v1.4.41" "$P_40"
: >"$S/fail_list"
run
check "release list error: run fails" rc_nonzero

# 10. The workflow runs this script, also on a hand-published release, and the old branch is gone.
S="$work/wiring"; mkdir -p "$S"; : >"$S/out"; : >"$S/releases"; : >"$S/latest"
check "workflow calls latest-follows-play.sh" grep -q 'bash .github/scripts/latest-follows-play.sh "$RUNNER_TEMP/out.txt"' "$workflow"
check "workflow also runs on a published/edited release" grep -qE '^ +types: \[published, released, edited\]' "$workflow"
check "workflow no longer leaves a Latest that is ahead of Play" bash -c '! grep -qF "newer than \$tag; leaving it" "$1"' _ "$workflow"

echo "passed $pass, failed $fail"
[ "$fail" -eq 0 ] && echo "all passed"
[ "$fail" -eq 0 ]
