# Android device testing: methods that hold up

Methods for exercising the VPN's failure paths on an emulator or a test device,
and the traps that make a test measure the wrong thing. Each one was learned on
a live run; the run is named so the evidence can be found again.

The logcat lines quoted below come from `android.util.Log` calls, which
`app/proguard-rules.pro` strips from release builds. Read them from a **debug**
build.

## Simulating a dead relay (MR-1363)

What the app must detect: the network is up, the app keeps sending into the
tunnel, and nothing comes back. `TunnelMonitor`'s UNANSWERED rule judges that
from wg-go's transfer counters ("sent more than keepalives, received nothing
for 20 s").

**Drop the relay's replies on INPUT, for IPv4 AND IPv6:**

```bash
adb root                      # an emulator image without the Play Store, or a rooted device
adb shell iptables  -I INPUT -p udp --sport 51820 -j DROP
adb shell ip6tables -I INPUT -p udp --sport 51820 -j DROP
adb shell ping 1.1.1.1        # keep traffic going through the tunnel (Ctrl-C when done)

# afterwards
adb shell iptables  -D INPUT -p udp --sport 51820 -j DROP
adb shell ip6tables -D INPUT -p udp --sport 51820 -j DROP
```

`51820` is the relay's WireGuard port. A Stealth session does not send to it
(F11 below reconnected over Stealth with both rules still in place), so this
simulates a dead relay for a direct WireGuard session only.

**Why INPUT and not OUTPUT.** An OUTPUT drop makes the app's own `send()` fail
with `EPERM`. wg-go then never counts the packets as sent, so the UNANSWERED
rule has nothing to judge, and the tunnel stays "Protected" until the 180 s
handshake-age backstop. That is not a dead relay; it is a local firewall
refusing the send, and the app's behaviour there is correct. Observed live on
2026-10-01 (client-overhaul baseline evidence, F8).

**Why both IPv4 and IPv6.** The relay has an IPv6 endpoint too. With only the
IPv4 rule, the re-dial after the dead-tunnel verdict reached the relay over
IPv6 and the app was "Protected" again at about 55 s, which looks like a
recovery from a dead relay but is a route around the test (F7). With both rules
(F11) the run showed the full path:

1. `Tunnel dead — sends unanswered for 20000ms` (about 26 s in);
2. the app shows Reconnecting and the block goes up (the run had the kill
   switch on);
3. `Dead-tunnel probe: ... -> ALIVE` (one heartbeat sent around the tunnel:
   the server still holds the session, so the app re-dials rather than ending
   it);
4. the direct re-dial fails, the app falls back to Stealth, and it is
   "Protected" again at about 56 s with UDP still blocked.

**After a run that fell back to Stealth,** the app starts later connects on
Stealth for up to a day (`AppPreferences.stealthPreferredSince`). A dead-relay
test run in that window never touches port 51820; check that the session is a
direct one before trusting a result.
