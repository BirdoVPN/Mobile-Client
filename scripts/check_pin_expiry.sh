#!/usr/bin/env bash
#
# Fail CI while there is still time to act on the Android pin-set expiry
# (MR-1669).
#
# network_security_config.xml gives the birdo.app pin-set an `expiration`.
# Past that date Android STOPS ENFORCING the pins: it falls back to ordinary CA
# validation and connections carry on, so nothing breaks that a user or a test
# would notice; the pinning layer is simply gone. The step this replaces said
# the app "will fail to connect", which is not what happens, and it failed only
# once the date had passed, when every installed build had already stopped
# pinning.
#
# The remedy is a release: move the date out (the pins themselves are checked
# against the live chain daily by cert-pins.yml), ship it, and let installs
# update before the old date passes. So this FAILS from FAIL_DAYS before
# expiry and warns from WARN_DAYS.
#
# Usage: scripts/check_pin_expiry.sh [network_security_config.xml]
#   PIN_EXPIRY_TODAY=YYYY-MM-DD stands in for today's UTC date (self-test).
set -euo pipefail

FAIL_DAYS=30
WARN_DAYS=90

config=${1:-app/src/main/res/xml/network_security_config.xml}
today=${PIN_EXPIRY_TODAY:-$(date -u +%F)}

if [ ! -f "$config" ]; then
    echo "::error::check_pin_expiry: $config not found"
    exit 1
fi
dates=$(grep -oE 'expiration="[^"]+"' "$config" | sed -E 's/^expiration="([^"]+)"$/\1/' || true)
if [ -z "$dates" ]; then
    echo "::error file=$config::No pin-set expiration found in $config"
    exit 1
fi
if ! today_epoch=$(date -u -d "$today" +%s 2>/dev/null); then
    echo "::error::check_pin_expiry: '$today' is not a date"
    exit 1
fi

status=0
for exp in $dates; do
    if ! exp_epoch=$(date -u -d "$exp" +%s 2>/dev/null); then
        echo "::error file=$config::pin-set expiration '$exp' is not a date"
        status=1
        continue
    fi
    days=$(( (exp_epoch - today_epoch) / 86400 ))
    echo "Certificate pin-set expiration: $exp ($days days from $today)"
    if [ "$days" -lt 0 ]; then
        echo "::error file=$config::The pin-set EXPIRED on $exp. Every installed build has stopped enforcing these pins and validates birdo.app with the system CA store alone: connections still work, unpinned. Move expiration out and ship a release."
        status=1
    elif [ "$days" -lt "$FAIL_DAYS" ]; then
        echo "::error file=$config::The pin-set expires in $days days ($exp). After that date Android stops enforcing these pins and falls back to ordinary CA validation: connections keep working, unpinned. Move expiration out (keep the pins) and ship a release before then. This check fails from $FAIL_DAYS days out so that there is time to."
        status=1
    elif [ "$days" -lt "$WARN_DAYS" ]; then
        echo "::warning file=$config::The pin-set expires in $days days ($exp), after which Android stops enforcing the pins (connections continue, unpinned). Move expiration out in the next release: this check fails from $FAIL_DAYS days out."
    else
        echo "Pin expiry OK: $days days left (warns under $WARN_DAYS, fails under $FAIL_DAYS)"
    fi
done
exit "$status"
