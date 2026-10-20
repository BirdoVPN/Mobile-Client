#!/usr/bin/env bash
#
# Fail CI while there is still time to act on the Android pin-set expiry
# (MR-1669).
#
# network_security_config.xml gives the birdo.app pin-set an `expiration`.
# Past that date Android STOPS ENFORCING THAT PIN-SET: it falls back to
# ordinary CA validation for what the pin-set covers. Release builds pin
# birdo.app and *.birdo.app a second time, with the same pins, in the OkHttp
# CertificatePinner (NetworkModule.kt, `if (!BuildConfig.DEBUG)`), which has no
# expiry, so API calls stay pinned and no connection fails; what is lost is the
# platform layer. The step this replaces said the app "will fail to connect",
# which is not what happens, and it failed only once the date had passed, when
# every installed build had already stopped enforcing the pin-set.
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
        echo "::error file=$config::The pin-set EXPIRED on $exp. Android no longer enforces this network_security_config pin-set and falls back to CA validation for what it covers; the OkHttp CertificatePinner (NetworkModule.kt) has no expiry and keeps pinning API calls in release builds, so connections do not fail. Move expiration out (keep the pins) and ship a release."
        status=1
    elif [ "$days" -lt "$FAIL_DAYS" ]; then
        echo "::error file=$config::The pin-set expires in $days days ($exp). After that date Android stops enforcing the network_security_config pin-set and falls back to CA validation for what it covers; the OkHttp CertificatePinner (NetworkModule.kt) has no expiry and keeps pinning API calls in release builds; connections do not fail. Move expiration out (keep the pins) and ship a release before then. This check fails from $FAIL_DAYS days out so that there is time to."
        status=1
    elif [ "$days" -lt "$WARN_DAYS" ]; then
        echo "::warning file=$config::The pin-set expires in $days days ($exp). After that date Android stops enforcing the network_security_config pin-set (the OkHttp CertificatePinner keeps pinning API calls in release builds; connections do not fail). Move expiration out in the next release: this check fails from $FAIL_DAYS days out."
    else
        echo "Pin expiry OK: $days days left (warns under $WARN_DAYS, fails under $FAIL_DAYS)"
    fi
done
exit "$status"
