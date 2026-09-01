# UX pass test — the four owner-requested changes

Covers Mobile-Client#345 and Desktop-Client#106. Run alongside the two existing
device tests, not instead of them.

All four changes compile and pass CI on every platform. That proves the code is
valid, not that the app is right — three of the four move things a user has to
find, and one of them relocated a load-bearing hook on iOS. Compiling cannot
detect either.

Run on **all three** platforms unless a section says otherwise. Sections marked
`BLOCKER` should pass before tagging.

---

## 1. Server load is gone, and list ORDER still follows it `BLOCKER`

The display goes; the ordering behaviour stays.

⚠️ **An earlier draft of this section was wrong and would have failed a correct
build.** It told you to check that the app *selects* a low-load server. It does
not, on two of the three clients, and never did:

| client | how it picks a server |
|---|---|
| iOS / macOS | `list.first { isOnline && accessible }` — API order, **load not consulted** |
| Desktop | `.find(\|s\| s.is_online)` — first online, **load not consulted** |
| Android | `minByOrNull { it.load }` in `VpnManager.quickConnect` — the only load-aware selection anywhere, and only reached when no server is already selected |

So "did it pick a low-load node?" is not a valid check. What the surviving
comparators actually drive is **the order of the server list**. Test that.

**Nothing shows load:**

1. Open the server list. No percentages, no bars, no coloured load dots.
2. Check the home screen's selected-server subtitle. It should read the city (or
   country) alone — **no trailing `·` where the load used to be.**
3. Open the Multi-Hop entry/exit pickers. No percentages next to the nodes.
4. Desktop: check the globe and the dashboard too.

**List order still follows load:**

⚠️ **Sign in first.** The load comparator sorts the *authenticated* server list.
A signed-out guest sees the public locations list, which is ordered differently
and never consults load — testing it there is a guaranteed false fail.

5. Open the admin console's fleet view, which *does* still show load —
   deliberately, it is operational data — and note the current load of three or
   four online servers in the same country.
6. Open the app's server list and compare. Within a group that is otherwise
   equal, **the lower-load servers should appear above the higher-load ones.**

**Regression:** the list order bears no relation to load — e.g. strictly
alphabetical, or unchanged as load shifts. That means a comparator was deleted
along with the display.

> Worth raising separately: that iOS and Desktop ignore load when auto-selecting
> is a **pre-existing** product gap, not something this change caused. It is
> noted here only so you do not report it as a regression.

## 2. The name reads BirdoVPN everywhere `BLOCKER`

One word, no space. Check every surface a user actually sees — the first pass
missed the ones that were not in the obvious files.

**All platforms:** app name under the icon, window/page title, about screen,
connection notifications.

**iOS / macOS:**
- Settings › General › VPN & Device Management (iOS) or System Settings ›
  Network (macOS) — **the tunnel profile name.** This was missed initially.
- The Face ID / Touch ID prompt when Hide App Contents is on.
- The App Store listing name.

**Android:**
- The launcher label and the persistent VPN notification.
- The Play listing title — **this is the most-read instance of the name.**
- The home-screen widget while loading.

**Desktop:** ⚠️ these were **all** missed in the first pass and are the most
likely to still be wrong:
- The **tray menu** (right-click the tray icon) — it said `Quit Birdo VPN` while
  its own tooltip said `BirdoVPN`.
- The tray tooltip on hover, including right after launch before the app has
  connected.
- **The biometric prompt.** The Rust command hardcodes this and ignores what the
  UI passes, so the earlier rename changed nothing here.
- Sign in with Google/GitHub, then read the **browser page** that appears after
  redirect — title and heading.
- Trigger an update-required error if you can; its text reaches the UI verbatim.

**Expected to still say two words, correctly:** the legal entity
"Birdo Networks Ltd" on invoices, receipts and legal pages. That is the
registered company name and must stay.

**Also expected:** if you already had 2FA set up, **your authenticator app will
still show "Birdo VPN" forever.** The issuer is baked in at enrollment. Only new
enrollments get the new name. This is not a bug.

## 3. DNS and Port Forwarding in their new home `BLOCKER`

Both moved out of the VPN Settings sub-page into the VPN group on the main
Settings page.

1. Open Settings. **Custom DNS Servers** and **Port Forwarding** should be
   visible in the VPN group, without opening a sub-page.
2. Open the VPN Settings sub-page. **Neither should still be there** — if you
   see them in both places, the move duplicated rather than relocated.
3. Turn Custom DNS on, enter a valid address, and confirm it saves.
4. Enter an **invalid** address. The same validation error as before must appear.
5. Port Forwarding: if your plan does not include it, the upgrade route must
   still trigger rather than silently doing nothing.

### 3a. The iOS reapply hook `BLOCKER` — iOS/macOS only

This is the subtle one and the reason this document exists.

DNS fields save per keystroke but only *flag* a reconnect, which fires when you
leave the screen. On the old sub-page that was guaranteed. On the Settings tab it
is not, so a backstop was added.

1. **Connect the VPN.**
2. Go to Settings, turn on Custom DNS, type a full valid address.
3. **Without leaving Settings, background the app** (home gesture / ⌘-Tab away).
4. Return to the app.

**Correct:** the tunnel reconnected briefly and is using the new DNS. Verify with
a DNS-leak check that it reports your chosen resolver.

**Regression:** the address is saved in the UI but the tunnel never reconnected,
so the old DNS is still live. That is the exact case the `scenePhase` backstop
exists for, and it means the backstop is not firing.

5. Repeat, but switch to another **tab** instead of backgrounding. Same result
   expected, via the other path.

## 4. The Display group, and the two moved toggles `BLOCKER`

1. Settings has a **Display** group containing Notifications (and on Android and
   Desktop, Show IP Address and Show Server Location).
2. **There is no second section also called "Notifications."** Two adjacent
   sections with overlapping names was a defect found in review; confirm it did
   not come back.
3. iOS does **not** have Show IP / Show Server Location — that is expected, not a
   miss.
4. **Quantum Protection** and **Kill Switch** appear directly **below** the
   biometric setting.
5. Turn the Kill Switch **off**. The warning dialog about traffic falling back to
   an unencrypted connection **must still appear**. Cancel it, and confirm the
   switch stays on.
6. Toggle each moved setting and confirm it still takes effect — these moved
   between files, and a toggle that renders but is no longer wired would look
   identical.

---

## Reporting

Per section: pass / fail / not run. On failure, name the platform, the exact
step, and what you saw instead.

Sections 1–4 are all blockers for a tag. Section 3a is the one most likely to
fail quietly, because a saved-but-not-applied setting looks like success.
