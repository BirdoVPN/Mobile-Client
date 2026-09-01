# Device test plan — Mobile-Client #335

Run before tagging any release containing #335. Merging shipped nothing; a tag does.

Four fixes touch routing, kill-switch-adjacent fail-closed paths, and account
identity. All have compile verification and two adversarial review rounds. **None
has been exercised on a device.** The three highest-risk items are marked
`BLOCKER` — a release should not go out if any of them fails.

Requires: an Android handset, an iPhone, a **SOVEREIGN** account, and a second
account that is **not** SOVEREIGN (or a downgraded one).

---

## A. Multi-Hop downgrade — the server list `BLOCKER`

The defect: tapping a server during a live Multi-Hop session rebuilt a single hop
while the app kept drawing both.

1. Sign in as SOVEREIGN. Arm Multi-Hop, pick entry and exit in **different
   countries**. Connect.
2. Confirm the tunnel is genuinely multi-hop before testing anything: check the
   egress country from the device (any IP-geolocation page) and confirm it is the
   **exit** country, not the entry country.
3. Open the server list. ⚠️ **There is no "Servers" tab** — the bottom bar is
   Profile / Connect / Limit / Settings, and the list is a pushed screen. Worse,
   while Multi-Hop is armed the home screen renders the entry → exit pair
   *instead of* the server selector, so the usual route is not there either.

   Open it with the deep link:

   ```
   adb shell am start -a android.intent.action.VIEW -d birdo://servers
   ```

   On iOS/macOS, open the server list from the home screen's server selector.
   (An earlier draft claimed the selector disappears while Multi-Hop is armed
   and told you to race the render — that was wrong, and it licensed skipping a
   blocker. Just open the list normally.)

4. Tap a different node.

**Correct:** the tap is refused, the session stays up, and the egress country is
**unchanged**.

⚠️ **Where the message appears differs by platform**, so do not fail iOS for
looking wrong:

- **Android** shows the red banner on the server list itself.
- **iOS/macOS** writes it to `vpnVM.error`, which only the **Home** screen
  renders — `ServerListView` deliberately isolates its own errors. So on iOS
  the list shows nothing; **go back to Home** and the banner reading *"Switching
  servers would replace your Multi-Hop route with a single hop. Disconnect first
  if you meant to switch."* should be there.

That split is itself worth reporting — the user gets no feedback on the screen
they tapped — but it is **not** a regression from this change.

**Regression:** the tunnel reconnects, the app still shows the entry → exit route,
and the egress country becomes the tapped node's. That is the original bug.

5. Tap the **already-highlighted** row. The banner must **stay**. (A previous
   draft cleared it here — a tap that did nothing removed the only feedback.)
6. Tap a different node again — banner still shown, still refused.

## B. Multi-Hop downgrade — Quick Settings tile `BLOCKER`

1. Still SOVEREIGN, Multi-Hop armed, **disconnected**.
2. Add the Birdo tile to the Quick Settings shade. Open the app so the
   subscription is cached, then **tap the tile within 30 seconds** — the tile
   reads a cache with a 30-second TTL refilled only from UI paths. Wait longer
   and you are testing the "entitlement unknown" branch instead of this one.

**Correct:** connects as **Multi-Hop** — the app shows entry → exit and the egress
country is the exit's.

**Regression:** connects single-hop while the app draws two hops.

3. Disconnect, then make the Multi-Hop pair **incomplete**.

   ⚠️ There is no Multi-Hop UI in Settings at all, and no "clear this node"
   control anywhere — the only writer is Home (`HomeScreen.kt:304-311`) and the
   arm toggle nulls **both** nodes together (`HomeScreen.kt:196-200`). So build
   the half-set state going *up*, not down: with Multi-Hop armed, set the
   **entry** node only and leave the exit unset (do not open the exit picker).

   If arming auto-populates both, say so and mark this step not-run — it would
   mean the incomplete state is unreachable through the UI, which is itself
   worth reporting.

   Then tap the tile.

**Correct:** the app opens. Nothing connects silently.

## C. Non-SOVEREIGN tile — the regression review caught `BLOCKER`

This is the case an earlier draft broke. It must be tested even though it looks
unrelated to Multi-Hop.

1. Sign in as a **non-SOVEREIGN** account that has Multi-Hop armed in prefs. The
   reliable way to produce this: arm Multi-Hop while SOVEREIGN, then downgrade —
   nothing clears the pref.
2. Open the app so the subscription is cached, then **tap the tile within 30
   seconds**. ⚠️ The tile reads a cache with a **30-second TTL** that is refilled
   only from UI paths — wait longer and it reads `null`, which is the
   "entitlement unknown" branch (step 3), not the one this step is testing.

**Correct:** a normal **single-hop** connection. That is what the account is
entitled to and what the app displays for it.

**Regression:** nothing happens, on this tap and every subsequent one, with no
error anywhere. The draft that did this looked correct in review.

3. Force-stop the app (so no subscription is cached) and tap the tile with
   Multi-Hop still armed.

**Correct:** the app opens rather than guessing.

## D. IPv4 default route — normal connect `BLOCKER`

The guard fails **closed**, so the risk is refusing healthy connections.

1. Sign out of Multi-Hop entirely. Connect to any single node, on IPv4-only Wi-Fi.

**Correct:** connects normally. Browse a site; traffic works.

**Regression:** "VPN permission denied" or a kill-switch-blocked state on a
connection that used to work. If this happens, **stop and report** — it means a
normal `allowedIps` is not matching the `0.0.0.0/0` check.

2. Repeat on mobile data, and on a dual-stack network if available.
3. `adb logcat | grep BirdoVPN` during connect — **note the capitalisation**;
   the tag is `BirdoVPN` and `grep` is case-sensitive, so a lower-case `Vpn`
   matches nothing and the check passes no matter what the app did. The line
   `allowedIps carried no IPv4 default route - added one` should **not** appear on
   a healthy connect. If it does, the server is sending something unexpected —
   report it; the tunnel is still safe.

## E. iOS log redaction

1. iPhone: connect, then force a failure — turn Wi-Fi off mid-handshake, or
   connect on a network that blocks UDP.
2. Take a sysdiagnose, or watch the unified log in Console.app filtered to the
   PacketTunnel process.

**Correct:** lines like `wg: Unable to update bind | <private>` or
`wg: peer(ab12…xy90) - Failed to send handshake initiation | <private>`. Any
address is in the private half.

**Regression:** any node hostname (`de1.birdo.app`), IP, or `host:port` visible in
the public half. Report the exact line.

Diagnostics are still readable with a logging profile installed — that is the
point of the split, not a bug.

## F. iOS device identity — migration `BLOCKER` for existing users

The migration must be invisible. If it is not, every existing iOS user burns a
device slot and loses their trusted-device 2FA skip.

1. On an iPhone with a **pre-#335 build already installed and signed in**, note
   the device row (name and last-seen).

   ⚠️ **The device list is not in the app.** Neither client has a devices
   screen — iOS Profile shows only a `Devices` count tile
   (`SubscriptionView.swift:153`), and Android's Profile has none. The per-device
   rows live in the **web dashboard**: sign in at
   `https://birdo.app/dashboard/devices` (`birdo-web/app/dashboard/devices/page.tsx`)
   and read them there. Sections F and G both depend on this.
2. Install the new build **over** it. Do not delete the app — that defeats the
   test.
3. Open, sign in if needed, connect.

**Correct:** the **same** device row. No new entry. No "device limit reached".
2FA does not re-challenge on a trusted device.

**Regression:** a second device row appears for the same handset. That is the
migration hinge failing, and it affects every existing user.

## G. iOS device identity — rotation on erasure

1. Fresh iPhone install. Create a throwaway account. Connect once so a device row
   exists. Note it — again from `https://birdo.app/dashboard/devices`, not the
   app (see F step 1).
2. Delete the account from Profile.
3. Register a **new** account on the same handset. Connect.

**Correct:** the new account's device row is a **different** device id. The two
accounts share no identifier.

**Regression:** the same device id appears under the new account — the erased
account's fingerprint outlived the erasure.

4. Also confirm a **failed** deletion does not rotate: attempt deletion with a
   deliberately wrong password, then connect. The device row must be unchanged.

---

## Reporting

For each section: pass / fail / not run. On failure, capture `adb logcat` or the
sysdiagnose, the exact step, and what happened instead.

Sections A, B, C, D and F are blockers. E and G are important but a release could
proceed with them noted as untested if the blockers pass and the risk is
accepted deliberately — say so explicitly rather than leaving it implied.
