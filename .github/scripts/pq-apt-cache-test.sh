#!/usr/bin/env bash
# ============================================================================
# Self-test for .github/scripts/pq-apt-cache.sh (the QEMU job's .deb archive
# cache, MR-2347), run by that job before it stages anything. apt reuses a
# cached archive on its name and size alone, so the stage step's hash check is
# the whole of the protection of apt's archive directory; prove it refuses
# what it must, with fake archives in temp directories (no apt, no root):
#
#   stage    an archive matching the plan        -> staged, byte-identical
#            same size, different bytes           -> refused (the case apt's
#                                                    own size check accepts)
#            a symlink to a matching archive      -> refused
#            planned but not in the cache         -> left for apt
#            in the cache but not in the plan     -> ignored
#            no restored directory / no plan      -> stages nothing, exit 0
#   parse    the SHA256 print-uris line           -> plan line
#            MD5Sum / SHA512 / no hash            -> exit 1 (never trusted)
#            a file name with a slash or ..       -> exit 1
#            no lines at all                      -> exit 1
#   collect  every planned archive present        -> complete=true
#            one missing or altered               -> complete=false
#            an archive apt holds but the plan
#            does not name (an older version)     -> not collected
#   packages the list read from the action        -> plan refuses a bad name
# ============================================================================
set -euo pipefail

here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cache_sh="$here/pq-apt-cache.sh"
[ -f "$cache_sh" ] || { echo "::error::script missing: $cache_sh"; exit 1; }

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/pq-apt-cache-selftest.XXXXXX")"
trap 'rm -rf "$work"' EXIT

fail=0
check() { # check <description> <command...>
  local d="$1"
  shift
  if "$@"; then
    echo "ok    $d"
  else
    echo "::error::pq-apt-cache self-test: $d"
    fail=1
  fi
}
sha() { sha256sum "$1" | cut -d' ' -f1; }

restored="$work/restored"
dest="$work/archives"
mkdir -p "$restored" "$dest"
printf 'good archive bytes\n' >"$restored/good_1.0_amd64.deb"
printf 'what the index says\n' >"$work/expected-bad"
printf 'what the cache says\n' >"$restored/bad_2%3a1.0_amd64.deb" # same length, other bytes
printf 'linked archive\n' >"$work/link-target"
ln -s "$work/link-target" "$restored/link_1.0_amd64.deb"
printf 'not planned\n' >"$restored/extra_1.0_amd64.deb"

plan="$work/plan.txt"
{
  echo "good_1.0_amd64.deb 19 $(sha "$restored/good_1.0_amd64.deb")"
  echo "bad_2%3a1.0_amd64.deb 20 $(sha "$work/expected-bad")"
  echo "link_1.0_amd64.deb 15 $(sha "$work/link-target")"
  echo "absent_1.0_amd64.deb 7 $(printf 'absent\n' | sha256sum | cut -d' ' -f1)"
} >"$plan"

run() { APT_PLAN="$plan" APT_ARCHIVES="$dest" RUNNER_TEMP="$work" GITHUB_OUTPUT="$work/output" bash "$cache_sh" "$@"; }

# -- stage ---------------------------------------------------------------------
log=$(run stage "$restored")
echo "$log"
check "the matching archive is staged" test -f "$dest/good_1.0_amd64.deb"
check "the staged archive is byte-identical" cmp -s "$restored/good_1.0_amd64.deb" "$dest/good_1.0_amd64.deb"
check "same size, other bytes: refused" test ! -e "$dest/bad_2%3a1.0_amd64.deb"
check "a symlink: refused" test ! -e "$dest/link_1.0_amd64.deb"
check "an archive the plan does not name: ignored" test ! -e "$dest/extra_1.0_amd64.deb"
check "no verification copy left behind" test -z "$(find "$dest" -name '.birdo-verify.*')"
check "the summary counts 1 staged, 1 rejected, 2 not in the cache" grep -qF "1 verified and staged, 1 rejected, 2 not in the cache" <<<"$log"
check "no restored directory: exit 0, nothing staged" run stage "$work/does-not-exist"
check "no plan: exit 0, nothing staged" env APT_PLAN="$work/no-plan" APT_ARCHIVES="$dest" RUNNER_TEMP="$work" bash "$cache_sh" stage "$restored"

# -- parse ---------------------------------------------------------------------
h=$(printf 'x' | sha256sum | cut -d' ' -f1)
good_line="'http://azure.archive.ubuntu.com/ubuntu/pool/main/q/qemu/qemu-user_1%3a8.2.2+ds-0ubuntu1.10_amd64.deb' qemu-user_1%3a8.2.2+ds-0ubuntu1.10_amd64.deb 104544 SHA256:$h"
parsed=$(run parse <<<"$good_line")
check "parse: a SHA256 line becomes a plan line (epoch's %3a kept)" test "$parsed" = "qemu-user_1%3a8.2.2+ds-0ubuntu1.10_amd64.deb 104544 $h"
refuse() { ! run parse <<<"$1" >/dev/null 2>&1; }
check "parse: MD5Sum refused" refuse "'http://x/a.deb' a_1_amd64.deb 5 MD5Sum:0123456789abcdef0123456789abcdef"
check "parse: SHA512 refused" refuse "'http://x/a.deb' a_1_amd64.deb 5 SHA512:$h$h"
check "parse: no hash refused" refuse "'http://x/a.deb' a_1_amd64.deb 5 "
check "parse: a short SHA256 refused" refuse "'http://x/a.deb' a_1_amd64.deb 5 SHA256:${h:0:63}"
check "parse: a path refused" refuse "'http://x/a.deb' ../a_1_amd64.deb 5 SHA256:$h"
check "parse: a slash refused" refuse "'http://x/a.deb' sub/a_1_amd64.deb 5 SHA256:$h"
check "parse: a leading dot refused" refuse "'http://x/a.deb' .._amd64.deb 5 SHA256:$h"
check "parse: one bad line fails the whole parse" refuse "$good_line"$'\n'"'http://x/b.deb' b_1_amd64.deb 5 MD5Sum:0123456789abcdef0123456789abcdef"
check "parse: no lines refused" refuse ""

# -- collect -------------------------------------------------------------------
cplan="$work/collect-plan.txt"
held="$work/held"
mkdir -p "$held"
printf 'one\n' >"$held/one_1_amd64.deb"
printf 'two\n' >"$held/two_1_amd64.deb"
printf 'old\n' >"$held/one_0_amd64.deb" # an older version apt still holds
{
  echo "one_1_amd64.deb 4 $(sha "$held/one_1_amd64.deb")"
  echo "two_1_amd64.deb 4 $(sha "$held/two_1_amd64.deb")"
} >"$cplan"
collect() { APT_PLAN="$cplan" APT_ARCHIVES="$held" RUNNER_TEMP="$work" GITHUB_OUTPUT="$work/collect-output" bash "$cache_sh" collect "$work/out"; }
: >"$work/collect-output"
collect >/dev/null
check "collect: all present -> complete=true" grep -qxF "complete=true" "$work/collect-output"
check "collect: the archives are in the cache directory" cmp -s "$held/two_1_amd64.deb" "$work/out/two_1_amd64.deb"
check "collect: only the planned archives (the entry never grows)" test "$(find "$work/out" -type f | wc -l)" -eq 2
printf 'TWO\n' >"$held/two_1_amd64.deb"
: >"$work/collect-output"
collect >/dev/null
check "collect: an altered archive -> complete=false" grep -qxF "complete=false" "$work/collect-output"
check "collect: the altered archive is not kept" test ! -e "$work/out/two_1_amd64.deb"
rm -f "$held/two_1_amd64.deb"
: >"$work/collect-output"
collect >/dev/null
check "collect: a missing archive -> complete=false" grep -qxF "complete=false" "$work/collect-output"

# -- plan: the package list ------------------------------------------------------
# plan reads the action's list before it touches apt; a name that is not a
# package name turns the cache off (key empty) instead of reaching apt-get.
# sudo and apt-get are stubs here that only record a call: none of these cases
# may get as far as apt.
stubs="$work/stubs"
mkdir -p "$stubs"
for s in sudo apt-get; do
  printf '#!/usr/bin/env bash\necho "%s $*" >>"%s"\nexit 1\n' "$s" "$work/apt-calls" >"$stubs/$s"
  chmod +x "$stubs/$s"
done
plan_with() { # plan_with <action file>: plan's stdout+stderr; outputs in $work/plan-output
  : >"$work/plan-output"
  : >"$work/apt-calls"
  PATH="$stubs:$PATH" APT_PLAN="$work/p.txt" RUNNER_TEMP="$work" GITHUB_OUTPUT="$work/plan-output" PQ_ACTION="$1" bash "$cache_sh" plan 2>&1
}
fake_action="$work/action.yml"
printf '%s\n' '      run: |' \
  '        sudo timeout -k 30 600 apt-get install -y --no-install-recommends \' \
  '          qemu-user gcc-aarch64-linux-gnu \' \
  '          "$(evil)" || {' '          exit 1' '        }' >"$fake_action"
log=$(plan_with "$fake_action")
check "plan: a bad package name -> key empty, exit 0" grep -qxF "key=" "$work/plan-output"
check "plan: a bad package name is named as the reason" grep -qF "is not a package name" <<<"$log"
check "plan: a bad package name never reaches sudo or apt-get" test ! -s "$work/apt-calls"
log=$(plan_with "$work/missing.yml")
check "plan: no action file -> key empty, exit 0" grep -qxF "key=" "$work/plan-output"
check "plan: no action file never reaches sudo or apt-get" test ! -s "$work/apt-calls"
printf '%s\n' '      run: |' '        sudo apt-get install -y qemu-user' >"$fake_action"
log=$(plan_with "$fake_action")
check "plan: the install line changed shape -> key empty, exit 0" grep -qxF "key=" "$work/plan-output"
check "plan: a changed install line never reaches sudo or apt-get" test ! -s "$work/apt-calls"
# A failed (stubbed) apt-get update: cache off, exit 0, nothing planned.
printf '%s\n' '        sudo apt-get install -y --no-install-recommends \' '          qemu-user' >"$fake_action"
rm -f "$work/p.txt"
log=$(plan_with "$fake_action")
check "plan: a failed apt-get update -> key empty, exit 0, no plan" test -n "$(grep -xF 'key=' "$work/plan-output")" -a ! -e "$work/p.txt"
check "plan: the update is the bounded one (sudo timeout ... apt-get update)" grep -qE '^sudo timeout -k [0-9]+ [0-9]+ apt-get update$' "$work/apt-calls"
# The reader stops at the error handler's `||`, never taking `{` or `exit`.
printf '%s\n' '        sudo apt-get install -y --no-install-recommends \' '          a b \' '          c || {' '          exit 1' >"$fake_action"
check "packages: words up to the '||' and no further" test "$(PQ_ACTION="$fake_action" RUNNER_TEMP="$work" bash "$cache_sh" packages | tr '\n' ' ')" = "a b c "
# The real action, through the script's own reader: its list must parse, or
# every run would silently go uncached.
real=()
while IFS= read -r p; do real+=("$p"); done < <(PQ_ACTION="$here/../actions/pq-cpu-proof/action.yml" RUNNER_TEMP="$work" bash "$cache_sh" packages)
bad_names=0
for p in "${real[@]}"; do [[ $p =~ ^[a-z0-9][a-z0-9.+-]*$ ]] || bad_names=1; done
has_qemu=0
for p in "${real[@]}"; do [ "$p" = qemu-user ] && has_qemu=1; done
check "the real action's list parses (${#real[@]} packages: ${real[*]})" test "${#real[@]}" -ge 4 -a "$bad_names" -eq 0 -a "$has_qemu" -eq 1

if [ "$fail" -ne 0 ]; then
  echo "::error::pq-apt-cache-test.sh: at least one case failed - see above"
  exit 1
fi
echo "pq-apt-cache self-test: all cases passed"
