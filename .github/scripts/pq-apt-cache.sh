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
#   pq-apt-cache.sh plan              apt-get update; write the plan; key= and prefix= to $GITHUB_OUTPUT
#   pq-apt-cache.sh packages          the package list read from the action, one per line
#   pq-apt-cache.sh parse             print-uris output on stdin -> plan lines on stdout (exit 1 on any line it does not understand)
#   pq-apt-cache.sh stage <dir>       copy each archive in <dir> that matches the plan into apt's archive directory
#   pq-apt-cache.sh collect <dir>     copy the planned archives apt now holds into <dir>; complete=true|false to $GITHUB_OUTPUT
#
# `collect` takes only the planned files: the archives of the versions this
# run installed, by apt's own file names. So an entry never grows, and a new
# Ubuntu version of one package means a new key and a new, complete entry.
#
# Environment: APT_PLAN (default $RUNNER_TEMP/pq-apt-plan.txt), APT_ARCHIVES
# (default /var/cache/apt/archives), PQ_ACTION (default
# .github/actions/pq-cpu-proof/action.yml). The self-test overrides them.
# Self-test: .github/scripts/pq-apt-cache-test.sh
# ============================================================================
set -euo pipefail

plan_file="${APT_PLAN:-${RUNNER_TEMP:?RUNNER_TEMP is not set}/pq-apt-plan.txt}"
archives="${APT_ARCHIVES:-/var/cache/apt/archives}"
pq_action="${PQ_ACTION:-.github/actions/pq-cpu-proof/action.yml}"

out() { # out <name> <value>: a step output (a no-op outside Actions)
  if [ -n "${GITHUB_OUTPUT:-}" ]; then printf '%s=%s\n' "$1" "$2" >>"$GITHUB_OUTPUT"; fi
}

as_root() { # as_root <cmd...>: sudo only where we cannot write ourselves
  if [ -w "$archives" ]; then "$@"; else sudo "$@"; fi
}

# One line per archive: "<file name> <size> <sha256>". A file name is apt's own
# (name_version_arch.deb, ':' quoted as %3a); never a path.
parse() {
  local line n=0 bad=0
  # 'URI' file size SHA256:hex - the line print-uris writes per archive.
  local re="^'[^']+' ([A-Za-z0-9][A-Za-z0-9.+~%_-]*[.]deb) ([0-9]+) SHA256:([0-9a-f]{64})\$"
  while IFS= read -r line || [ -n "$line" ]; do
    [ -n "$line" ] || continue
    if [[ $line =~ $re ]]; then
      printf '%s %s %s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}"
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
  # 20 min). The runner's apt settings are left alone, as there. The
  # action's update, right after, then finds the lists current.
  if ! sudo timeout -k 30 300 apt-get update; then
    echo "::warning::pq-apt-cache: apt-get update failed or ran past 5 min - installing without the cache"
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
  while read -r file _ sha; do
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
  while read -r file _ sha; do
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
  stage) stage "${2:?usage: stage <dir>}" ;;
  collect) collect "${2:?usage: collect <dir>}" ;;
  *)
    echo "usage: $0 plan | packages | parse | stage <dir> | collect <dir>" >&2
    exit 2
    ;;
esac
