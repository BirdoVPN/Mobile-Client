#!/usr/bin/env bash
# Installs fdroidserver into a fresh venv with every package pinned by hash (MR-2102).
#
# Usage: bash .github/fdroid/install-fdroidserver.sh <venv-dir>
#
# Two workflows call this, so both run exactly the same install:
#  - fdroid.yml, the job that signs and publishes the F-Droid repo. It holds the
#    repo signing keystore, which is why the installer and every package it pulls
#    in are pinned by hash.
#  - fdroid-deps.yml, the pull-request check. fdroid.yml runs only on a release or
#    a dispatch, and a dispatch publishes, so before this check a change to the
#    requirements files was first installed by a real publish (Dependabot #598:
#    wheel 0.46 needs `packaging`, which was not pinned, so --require-hashes would
#    have refused the install).
set -euo pipefail

venv="${1:?usage: install-fdroidserver.sh <venv-dir>}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The apt package (2.2.1) ships an old androguard that cannot parse modern AAPT2
# resource tables ("res1 must be zero!"), so install the current fdroidserver from
# pip into a venv instead.
# Bounded for the same reason as .github/actions/pq-cpu-proof: a stalled Ubuntu
# mirror must fail in minutes, not eat the job timeout. The image's own apt
# Retries/Timeout are left alone (they drive its mirror failover); `timeout` is
# the ceiling they cannot provide.
sudo timeout -k 30 300 apt-get update || {
  echo "::error::apt-get update failed or ran past 5 min: an Ubuntu mirror problem, not this change. Re-run the job."
  exit 1
}
sudo timeout -k 30 600 apt-get install -y libmagic1 python3-venv || {
  echo "::error::apt-get install failed or ran past 10 min: an Ubuntu mirror problem, not this change. Re-run the job."
  exit 1
}

python3 -m venv "$venv"
#  1. pip, wheel, setuptools (the build backend), babel and packaging (wheel's
#     run-time dependency since 0.46.0) from requirements-build.txt,
#     --require-hashes.
#  2. fdroidserver 2.4.5 and its full dependency closure from requirements.txt,
#     --require-hashes. fdroidserver, args, clint and sdkmanager are sdists, so
#     --no-build-isolation makes pip build them with the hashed setuptools above
#     rather than fetching unpinned build requirements. --no-deps is NOT used: pip
#     must see every dependency in the file or it refuses the install.
# To bump fdroidserver, regenerate both files as described in requirements.txt;
# the pull-request check (fdroid-deps.yml) then installs them.
"$venv/bin/python" -m pip install --quiet --require-hashes \
  -r "$here/requirements-build.txt"
"$venv/bin/python" -m pip install --quiet --require-hashes --no-build-isolation \
  -r "$here/requirements.txt"
"$venv/bin/fdroid" --version
