#!/usr/bin/env bash
# TEMPORARY (MOBILE-PLUGIN-MARKER-FLAKE): removed before the pull request is
# ready. On the Gradle cache this job restored, lists which files of the base
# commit's verification-metadata.xml the cache lacks, then configures this
# change and the base commit's settings OFFLINE (no request possible) and
# prints what each could not find.
# Usage: tmp-plugin-marker-diagnostics.sh <base sha>
set -u
base="$1"
files="$HOME/.gradle/caches/modules-2/files-2.1"
git fetch --quiet --depth=1 origin "$base"
git show "$base:gradle/verification-metadata.xml" > "$RUNNER_TEMP/base-verification-metadata.xml"

echo "== files the base metadata lists that the restored cache lacks:"
python3 -I - "$RUNNER_TEMP/base-verification-metadata.xml" "$files" <<'PY'
import os
import sys
import xml.etree.ElementTree as ET

vm, files = sys.argv[1], sys.argv[2]
ns = "{https://schema.gradle.org/dependency-verification}"
total = 0
missing = []
for c in ET.parse(vm).getroot().iter(ns + "component"):
    g, n, v = c.get("group"), c.get("name"), c.get("version")
    d = os.path.join(files, g, n, v)
    have = set()
    if os.path.isdir(d):
        for h in os.listdir(d):
            have.update(os.listdir(os.path.join(d, h)))
    for a in c.findall(ns + "artifact"):
        total += 1
        if a.get("name") not in have:
            missing.append(f"{g}:{n}:{v} {a.get('name')}")
print(f"{total} files listed; {len(missing)} not in the restored cache")
for m in missing:
    print("  " + m)
PY

offline() {
  ./gradlew --offline help --debug > "$RUNNER_TEMP/offline-$1.log" 2>&1
  echo "== $1: --offline help exit $?"
  grep -E "Plugin \[id|could not resolve|BUILD (SUCCESSFUL|FAILED)" "$RUNNER_TEMP/offline-$1.log" | sed -E 's/^[^]]*\] //' | head -10
  grep -iE "no cached|non-existence|no meta-?data|not available|offline mode|missing" "$RUNNER_TEMP/offline-$1.log" \
    | sed -E 's/^[^]]*\] \[[^]]*\] //' | sort -u | head -40
}

offline this-change
for f in settings.gradle.kts gradle/verification-metadata.xml; do
  cp "$f" "$RUNNER_TEMP/$(basename "$f").pr"
  git show "$base:$f" > "$f"
done
offline "base-$base"
for f in settings.gradle.kts gradle/verification-metadata.xml; do
  cp "$RUNNER_TEMP/$(basename "$f").pr" "$f"
done
if ! git diff --quiet HEAD -- settings.gradle.kts gradle/verification-metadata.xml; then
  echo "::error::the diagnostics did not restore the tree"
  exit 1
fi
exit 0
