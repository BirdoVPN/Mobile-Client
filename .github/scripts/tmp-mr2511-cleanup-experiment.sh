#!/usr/bin/env bash
# TEMPORARY (MR-2511, MOBILE-PLUGIN-MARKER-FLAKE): reverted before the pull
# request is ready. On the Gradle cache this job restored:
#   1. an --offline configuration (no request possible);
#   2. a cache cleanup with the cut-off main's save used: delete what Gradle's
#      file-access journal says this job did not access. Done with Gradle's
#      own cleanup (Cleanup.ALWAYS, removeUnusedEntriesOlderThan = the time
#      before step 1), the journal setup-gradle's cleanup reads;
#   3. the same --offline configuration again.
set -u
home="${GRADLE_USER_HOME:-$HOME/.gradle}"
files="$home/caches/modules-2/files-2.1"

absent() {
  python3 -I - gradle/verification-metadata.xml "$files" <<'PY'
import os
import sys
import xml.etree.ElementTree as ET

ns = "{https://schema.gradle.org/dependency-verification}"
total = absent = 0
kinds = {}
for c in ET.parse(sys.argv[1]).getroot().iter(ns + "component"):
    d = os.path.join(sys.argv[2], c.get("group"), c.get("name"), c.get("version"))
    have = set()
    if os.path.isdir(d):
        for h in os.listdir(d):
            have.update(os.listdir(os.path.join(d, h)))
    for a in c.findall(ns + "artifact"):
        total += 1
        if a.get("name") not in have:
            absent += 1
            ext = a.get("name").rsplit(".", 1)[-1]
            kinds[ext] = kinds.get(ext, 0) + 1
print(f"{absent} of the {total} files gradle/verification-metadata.xml lists are absent "
      f"({', '.join(f'{k} {v}' for k, v in sorted(kinds.items()))})")
PY
}

offline() {
  ./gradlew --offline help > "$RUNNER_TEMP/$1.log" 2>&1
  echo "== $1: ./gradlew --offline help -> exit $?"
  grep -E "Plugin \[id|could not resolve|Could not resolve|No cached version|BUILD (SUCCESSFUL|FAILED)" "$RUNNER_TEMP/$1.log" | head -8
}

cutoff=$(date +%s%3N)
echo "== restored cache: $(find "$files" -type f | wc -l) files in files-2.1; $(absent)"
offline "1-restored-cache"

d="$RUNNER_TEMP/dummy-cleanup-project"
mkdir -p "$d"
printf 'rootProject.name = "dummy-cleanup-project"\n' > "$d/settings.gradle"
printf 'tasks.register("noop")\n' > "$d/build.gradle"
cat > "$d/init.gradle" <<GRADLE
beforeSettings { settings ->
    def cutoff = ${cutoff}L
    settings.caches {
        cleanup = Cleanup.ALWAYS
        releasedWrappers.setRemoveUnusedEntriesOlderThan(cutoff)
        snapshotWrappers.setRemoveUnusedEntriesOlderThan(cutoff)
        downloadedResources.setRemoveUnusedEntriesOlderThan(cutoff)
        createdResources.setRemoveUnusedEntriesOlderThan(cutoff)
        buildCache.setRemoveUnusedEntriesOlderThan(cutoff)
    }
}
GRADLE
./gradlew --stop > /dev/null 2>&1
# The job's own Lint step runs after this: keep the restored cache to put back.
cp -a "$home/caches" "$RUNNER_TEMP/caches-restored"
./gradlew --project-dir "$d" --init-script "$d/init.gradle" --no-daemon --quiet noop
echo "== cleanup (files not accessed since step 1 started) -> exit $?"
echo "== after the cleanup: $(find "$files" -type f | wc -l) files in files-2.1; $(absent)"
offline "2-after-cleanup"
./gradlew --stop > /dev/null 2>&1
rm -rf "$home/caches"
mv "$RUNNER_TEMP/caches-restored" "$home/caches"
echo "== the restored cache is back for the Lint step: $(find "$files" -type f | wc -l) files in files-2.1"
exit 0
