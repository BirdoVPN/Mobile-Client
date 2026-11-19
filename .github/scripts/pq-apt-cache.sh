#!/usr/bin/env bash
# ============================================================================
# The .deb archive cache of android-ci.yml's QEMU job ("PQ KEM on a CPU
# without FEAT_SHA3 (QEMU)"; MR-2347). NOT used by android.yml: its release
# gate runs ./.github/actions/pq-cpu-proof with no cache. The action itself
# is unchanged and runs its own `apt-get update` and `apt-get install`.
# Ported from Desktop-Client's scripts/ci/apt-archive-cache.sh.
#
# WHY: the action's `apt-get install` downloads 54.8 MB of .debs (qemu-user
# and the aarch64 cross toolchain, 23 archives). On a runner in AWS us-east-2
# the image's mirrors served that at 100-224 kB/s: 4-9 minutes, in 6 of 21
# runs on 2026-11-17/18, against 2 s on Azure runners.
#
# WHAT IS PROTECTED: apt's archive directory, /var/cache/apt/archives. apt
# reuses a file there when its NAME and SIZE match the index, with no hash
# check (apt 2.7.x, apt-pkg/acquire-item.cc, pkgAcqArchive), so a restored
# archive is never put there unverified. `plan` asks apt for every archive the
# action's install will download, with its SHA-256 from the Packages index
# that `apt-get update` has just verified against the archive's signed
# InRelease; `stage` copies a restored file into apt's directory only when the
# copy apt will read hashes to that value. Anything else is left out, and apt
# downloads it as before.
#
# WHAT IS NOT: the cache entry. Nothing checks it as a whole: a missing,
# altered or extra file in it is simply not staged. It is trusted not to be
# junk only because main is the only writer (android-ci.yml saves it from
# main alone; a pull request only restores).
#
# Every failure here leaves the cache out and the install as it was: the
# action runs unchanged after `stage` and downloads whatever was not staged.
#
# ON A MISS (MR-2438): main alone writes the cache, and only from a run whose
# install finished, so while the mirror is slow it is never written (main
# runs 44401126423 and 44424368678 both died at the install's 10-minute
# ceiling, a 21.1 MB archive arriving at ~55 kB/s). apt cannot leave a slow
# mirror: the image's Timeout is an idle timer, and a trickle is not idle. So
# `fetch` downloads what the restore did not bring, with a speed floor, from
# the image's own mirrors and then two fallbacks; a mirror that is too slow or
# serves other bytes is dropped for the rest of the run. A file is kept only
# when it hashes to the plan's SHA-256 (the signed index's, the value `stage`
# checks again on the copy apt reads). Bounded; whatever it does not get, apt
# downloads as before.
#
#   pq-apt-cache.sh plan              apt-get update; write the plan; key= and prefix= to $GITHUB_OUTPUT
#   pq-apt-cache.sh packages          the package list read from the action, one per line
#   pq-apt-cache.sh parse             print-uris output on stdin -> plan lines on stdout (exit 1 on any line it does not understand)
#   pq-apt-cache.sh fetch <dir>       download into <dir> each planned archive it lacks, kept only when it hashes to the plan
#   pq-apt-cache.sh stage <dir>       copy each archive in <dir> that matches the plan into apt's archive directory
#   pq-apt-cache.sh collect <dir>     copy the planned archives apt now holds into <dir>; complete=true|false to $GITHUB_OUTPUT
#
# `collect` takes only the planned files: the archives of the versions this
# run installed, by apt's own file names. So an entry never grows, and a new
# Ubuntu version of one package means a new key and a new, complete entry.
#
# Environment: APT_PLAN (default $RUNNER_TEMP/pq-apt-plan.txt), APT_ARCHIVES
# (default /var/cache/apt/archives), PQ_ACTION (default
# .github/actions/pq-cpu-proof/action.yml); for `fetch`: APT_MIRRORS_FILE
# (default /etc/apt/apt-mirrors.txt, the image's mirror list),
# PQ_APT_FALLBACK_MIRRORS, PQ_APT_FETCH_BUDGET (seconds, default 180) and
# PQ_APT_MIN_SPEED (bytes/s, default 300 kB/s). The self-test overrides them.
# Self-test: .github/scripts/pq-apt-cache-test.sh
# ============================================================================
set -euo pipefail

plan_file="${APT_PLAN:-${RUNNER_TEMP:?RUNNER_TEMP is not set}/pq-apt-plan.txt}"
archives="${APT_ARCHIVES:-/var/cache/apt/archives}"
pq_action="${PQ_ACTION:-.github/actions/pq-cpu-proof/action.yml}"
mirrors_file="${APT_MIRRORS_FILE:-/etc/apt/apt-mirrors.txt}"
fallback_mirrors="${PQ_APT_FALLBACK_MIRRORS:-http://azure.archive.ubuntu.com/ubuntu http://archive.ubuntu.com/ubuntu}"
fetch_budget="${PQ_APT_FETCH_BUDGET:-180}"
min_speed="${PQ_APT_MIN_SPEED:-307200}"

out() { # out <name> <value>: a step output (a no-op outside Actions)
  if [ -n "${GITHUB_OUTPUT:-}" ]; then printf '%s=%s\n' "$1" "$2" >>"$GITHUB_OUTPUT"; fi
}

as_root() { # as_root <cmd...>: sudo only where we cannot write ourselves
  if [ -w "$archives" ]; then "$@"; else sudo "$@"; fi
}

# One line per archive: "<file name> <size> <sha256> <pool path>". A file name
# is apt's own (name_version_arch.deb, ':' quoted as %3a); never a path. The
# pool path (pool/..., the same on every Ubuntu mirror) is where `fetch` asks a
# mirror for it, or "-" when the URI has none; it is not part of the key.
parse() {
  local line uri file size sha path n=0 bad=0
  # 'URI' file size SHA256:hex - the line print-uris writes per archive.
  local re="^'([^']+)' ([A-Za-z0-9][A-Za-z0-9.+~%_-]*[.]deb) ([0-9]+) SHA256:([0-9a-f]{64})\$"
  local pool_re='/(pool/[A-Za-z0-9][A-Za-z0-9.+~%_/-]*[.]deb)$'
  while IFS= read -r line || [ -n "$line" ]; do
    [ -n "$line" ] || continue
    if [[ $line =~ $re ]]; then
      uri=${BASH_REMATCH[1]} file=${BASH_REMATCH[2]} size=${BASH_REMATCH[3]} sha=${BASH_REMATCH[4]} path=-
      if [[ $uri =~ $pool_re ]] && [[ /${BASH_REMATCH[1]}/ != */../* && /${BASH_REMATCH[1]}/ != */./* && ${BASH_REMATCH[1]} != *//* ]]; then
        path=${BASH_REMATCH[1]}
      fi
      printf '%s %s %s %s\n' "$file" "$size" "$sha" "$path"
      n=$((n + 1))
    else
      echo "pq-apt-cache: not understood: $line" >&2
      bad=1
    fi
  done
  [ "$bad" -eq 0 ] && [ "$n" -gt 0 ]
}

# The packages the action installs, read from it so the two cannot drift: the
# words on the continuation lines after its
# `apt-get install -y --no-install-recommends \`, up to the `||` of its error
# handler. The plan passes the same --no-install-recommends.
packages() {
  awk '
    /apt-get install -y --no-install-recommends \\$/ { on = 1; next }
    on {
      line = $0
      more = sub(/[ \t]*\\$/, "", line)
      n = split(line, w, /[ \t]+/)
      for (i = 1; i <= n; i++) {
        if (w[i] == "") continue
        if (w[i] == "||") exit
        print w[i]
      }
      if (!more) exit
    }
  ' "$pq_action"
}

plan() {
  local pkgs=() p uris key prefix os
  if [ -f "$pq_action" ]; then
    while IFS= read -r p; do pkgs+=("$p"); done < <(packages)
  fi
  if [ "${#pkgs[@]}" -eq 0 ]; then
    echo "::warning::pq-apt-cache: found no package list in $pq_action - installing without the cache"
    out key ""
    return 0
  fi
  for p in "${pkgs[@]}"; do
    if ! [[ $p =~ ^[a-z0-9][a-z0-9.+-]*$ ]]; then
      echo "::warning::pq-apt-cache: '$p' in $pq_action is not a package name - installing without the cache"
      out key ""
      return 0
    fi
  done
  echo "the action installs: ${pkgs[*]}"
  # Bounded like the action's own update (a stalled mirror once hung it for
  # 20 min), but tighter: 13 MB of lists took 5-52 s on every run of
  # 2026-11-17/18, and the action's own 5-minute update still follows, so
  # past 2 min this one gives up on the cache rather than spend the job's
  # time (MR-2438 L1). The runner's apt settings are left alone, as there.
  # The action's update, right after, then finds the lists current.
  if ! sudo timeout -k 15 120 apt-get update; then
    echo "::warning::pq-apt-cache: apt-get update failed or ran past 2 min - installing without the cache"
    out key ""
    return 0
  fi
  # print-uris forces MD5 unless a hash is named (apt-private/private-install.cc
  # CndSet), so name SHA-256. The hashes come from the Packages index that
  # `update` verified against the archive's signed InRelease.
  uris="$RUNNER_TEMP/pq-apt-print-uris.txt"
  if ! apt-get -o Acquire::ForceHash=SHA256 install -y --no-install-recommends --print-uris -qq "${pkgs[@]}" >"$uris"; then
    echo "::warning::pq-apt-cache: apt-get --print-uris failed - installing without the cache"
    out key ""
    return 0
  fi
  if ! parse <"$uris" | sort -u >"$plan_file.tmp" || ! [ -s "$plan_file.tmp" ]; then
    echo "::warning::pq-apt-cache: print-uris output not understood - installing without the cache"
    out key ""
    return 0
  fi
  mv -f "$plan_file.tmp" "$plan_file"
  # The key names the exact archive set (name, version, hash). Ubuntu updates
  # a package -> new key; restore-keys then brings the previous set, whose
  # unchanged archives still verify.
  os=$(. /etc/os-release && printf '%s%s' "$ID" "$VERSION_ID")
  prefix="pq-apt-v1-$os-$(uname -m)-"
  key="$prefix$(cut -d' ' -f1,3 "$plan_file" | sha256sum | cut -c1-16)"
  echo "planned $(wc -l <"$plan_file") archives, $(awk '{s += $2} END {printf "%.1f", s / 1048576}' "$plan_file") MiB; cache key $key"
  out key "$key"
  out prefix "$prefix"
}

# The mirrors `fetch` may ask, in order, one per line: the image's own (the
# first field of each line of its mirror list, the one apt's mirror method
# reads), then the fallbacks. Plain http(s) base URLs only, without a trailing
# slash, each once; any other line is ignored. Plain http is what apt uses
# too: the integrity check is the signed index's SHA-256, not the transport.
mirrors() {
  local -a fb=()
  read -r -a fb <<<"$fallback_mirrors" || true
  {
    if [ -r "$mirrors_file" ]; then awk '{print $1}' "$mirrors_file"; fi
    printf '%s\n' "${fb[@]}"
  } | sed 's:/*$::' | grep -E '^https?://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~-]+)*$' | awk '!seen[$0]++' || true
}

# For each planned archive <dir> does not hold with the plan's SHA-256: ask
# each mirror in turn for its pool path, keep the download only when it hashes
# to the plan, and drop (for the rest of the run) a mirror that ran below the
# speed floor, failed to connect or served other bytes. An HTTP error (the
# mirror lacks that file) only moves on to the next mirror. Never fails: a
# file it does not get is left for apt, which downloads it as before.
fetch() {
  local dir="$1" file sha path m tmp rc left why start deadline have=0 fetched=0 missed=0 got_it
  local -a pool=() dropped=()
  if [ ! -s "$plan_file" ]; then
    echo "no plan - nothing fetched"
    return 0
  fi
  while IFS= read -r m; do pool+=("$m"); done < <(mirrors)
  echo "mirrors, in order: ${pool[*]:-none}; floor $((min_speed / 1024)) kB/s, budget ${fetch_budget} s"
  mkdir -p "$dir"
  start=$SECONDS
  deadline=$((start + fetch_budget))
  while read -r file _ sha path; do
    if [ -f "$dir/$file" ] && [ ! -L "$dir/$file" ] && [ "$(sha256sum "$dir/$file" | cut -d' ' -f1)" = "$sha" ]; then
      have=$((have + 1))
      continue
    fi
    rm -f "$dir/$file"
    got_it=0
    if [ -n "$path" ] && [ "$path" != - ]; then
      for m in "${pool[@]}"; do
        [[ " ${dropped[*]} " == *" $m "* ]] && continue
        left=$((deadline - SECONDS))
        [ "$left" -gt 5 ] || break
        tmp="$dir/.birdo-fetch.$file"
        rc=0
        curl -fsSL --max-redirs 3 --proto =http,https --proto-redir =http,https \
          --connect-timeout 10 --max-time "$left" --speed-limit "$min_speed" --speed-time 10 \
          -o "$tmp" "$m/$path" </dev/null || rc=$?
        if [ "$rc" -eq 0 ] && [ -f "$tmp" ] && [ "$(sha256sum "$tmp" | cut -d' ' -f1)" = "$sha" ]; then
          mv -f "$tmp" "$dir/$file"
          got_it=1
          break
        fi
        rm -f "$tmp"
        if [ "$rc" -eq 22 ]; then
          echo "pq-apt-cache: $m does not have $path - next mirror"
          continue
        fi
        if [ "$rc" -eq 0 ]; then why="served bytes the signed index does not name"; else why="failed or ran below $((min_speed / 1024)) kB/s (curl exit $rc)"; fi
        echo "::warning::pq-apt-cache: $m $why for $file - not asked again in this run"
        dropped+=("$m")
      done
    fi
    if [ "$got_it" -eq 1 ]; then fetched=$((fetched + 1)); else missed=$((missed + 1)); fi
  done <"$plan_file"
  echo "archives: $have already restored, $fetched fetched and verified, $missed left for apt ($((SECONDS - start)) s)"
}

stage() {
  local dir="$1" file sha f tmp got staged=0 rejected=0 absent=0
  if [ ! -s "$plan_file" ]; then
    echo "no plan - nothing staged"
    return 0
  fi
  if [ ! -d "$dir" ]; then
    echo "no archives restored - apt downloads all $(wc -l <"$plan_file")"
    return 0
  fi
  while read -r file _ sha _; do
    f="$dir/$file"
    if [ -L "$f" ] || [ ! -f "$f" ]; then
      absent=$((absent + 1))
      continue
    fi
    # Copy, then hash the COPY: the bytes checked are the bytes apt reads.
    tmp="$archives/.birdo-verify.$file"
    if ! as_root install -m 0644 "$f" "$tmp"; then
      echo "::warning::pq-apt-cache: could not copy $file - apt downloads it"
      rejected=$((rejected + 1))
      continue
    fi
    got=$(as_root sha256sum "$tmp" | cut -d' ' -f1)
    if [ "$got" = "$sha" ] && as_root mv -f "$tmp" "$archives/$file"; then
      staged=$((staged + 1))
    else
      as_root rm -f "$tmp"
      echo "::warning::pq-apt-cache: $file from the cache does not match the signed index - not used, apt downloads it"
      rejected=$((rejected + 1))
    fi
  done <"$plan_file"
  echo "archives from the cache: $staged verified and staged, $rejected rejected, $absent not in the cache (apt downloads the last two)"
}

collect() {
  local dir="$1" file sha n=0 total
  if [ ! -s "$plan_file" ]; then
    echo "no plan - nothing collected"
    out complete false
    return 0
  fi
  total=$(wc -l <"$plan_file")
  rm -rf "$dir"
  mkdir -p "$dir"
  while read -r file _ sha _; do
    if [ -f "$archives/$file" ] && [ ! -L "$archives/$file" ] &&
      cp "$archives/$file" "$dir/$file" &&
      [ "$(sha256sum "$dir/$file" | cut -d' ' -f1)" = "$sha" ]; then
      n=$((n + 1))
    else
      rm -f "$dir/$file"
    fi
  done <"$plan_file"
  echo "collected $n of $total planned archives"
  if [ "$n" -eq "$total" ] && [ "$total" -gt 0 ]; then out complete true; else out complete false; fi
}

case "${1:-}" in
  plan) plan ;;
  packages) packages ;;
  parse) parse ;;
  fetch) fetch "${2:?usage: fetch <dir>}" ;;
  stage) stage "${2:?usage: stage <dir>}" ;;
  collect) collect "${2:?usage: collect <dir>}" ;;
  *)
    echo "usage: $0 plan | packages | parse | fetch <dir> | stage <dir> | collect <dir>" >&2
    exit 2
    ;;
esac
