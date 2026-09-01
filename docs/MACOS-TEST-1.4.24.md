# macOS device test — the three rejection fixes

Run before resubmitting. This is round three; a partial fix restarts the clock.

Neither code fix has ever been run. CI proves the Swift compiles — and it already
caught one invented symbol that `swiftc -parse` missed, so treat a green build as
"it compiles", nothing more. **Guideline 4 is a visual defect: only a human
looking at a screen can close it.**

Use a Mac with a display no taller than the review device — a **15-inch MacBook
Air (M3)**, which is what the reviewer used. On a large external monitor the
window bug will not reproduce even if it is still there.

## 🔴 Read this before starting: THIS PLAN DOES NOT FIT IN ONE DAY

`POST /auth/register/anonymous` has **two** independent rate limits, and the
second one is the reason this test plan has to be spread across three days.

| limit | value | keyed on | window |
|---|---|---|---|
| per IP | **3** | source IP | rolling 1 hour |
| **per DEVICE** | **5** | `deviceId` | **fixed 24 hours from your first mint** |

`auth.controller.ts:719` (`attempts > 3`) and `auth.controller.ts:744-761`
(`ANON_REGISTER_DEVICE_DAILY_CAP`, default **5**, TTL `24 * 60 * 60`).

🔴 **You cannot get around the device cap.** The macOS client always sends
`deviceId` (`APIClient.swift`, `registerAnonymous()`), and that id is
deliberately stable: `KeychainService.swift:41-50` keeps `device_id` out of
`clear()` so it survives sign-out, and it lives in the keychain, which is
**outside** the sandbox container. So **none** of these resets it — changing
your public IP, signing out, `rm -rf`-ing the container, or reinstalling the
app. Only waiting out the 24 hours does.

Counted, this document spends **12 mints on one Mac**, plus section F:

| section | mints |
|---|---|
| B | 1 |
| B2 | 2 (Connect, then the location row) |
| C1 | **4** — three back-outs plus the "tap Connect again" check |
| C2 | 1 |
| D | 4+, deliberately — D exists to *reach* the limit |
| F | 6 more, on the iPhone (its own device bucket, but **your IP**) |

### Run it in three sessions, one per day

| day | sections | mints | note |
|---|---|---|---|
| 1 | A, A2, **B**, **B2**, **C2** | 4 | A and A2 cost nothing — no minting |
| 2 | **C1** | 4 | on its own; it needs 4 and you only get 5 |
| 3 | **D**, then **F** | 4+ | D is meant to hit the wall |

Within a day the **hourly IP cap still binds**, so inside day 1 either wait an
hour or change your public IP between B, B2 and C2. Section F runs on a
different device (its own bucket) but **shares your public IP**, so give it a
fresh hour or a different network.

⚠️ **The 429 message lies about which limit you hit.** `GuestAccess.swift:143`
renders the same text — *"Too many anonymous accounts have been created from
this network in the past hour"* — for **both** refusals. If you have minted 5+
on this Mac today, that message is telling you about the network when the real
cause is the device, and waiting an hour or switching networks will not help.

A 429 is **never** a test failure. The tabbed sign-in form it renders is the
*correct* 429 behaviour, not the rejection reproducing. Before recording any
failure in B, B2 or C, check whether the sheet mentions a limit at all.

---

## A. Window position `BLOCKER` — guideline 4

The rejection: *"the app includes windows that laid out and partially hidden
under menu bar."*

1. Quit the app completely, then wipe its state.

   ⚠️ **The macOS build is sandboxed** (`BirdoVPN-macOS.entitlements` sets
   `com.apple.security.app-sandbox`), so its preferences and saved window frame
   live inside a container — **not** the usual locations. `defaults delete
   app.birdo.vpn` resolves to a path that does not exist for a container app and
   silently does nothing.

   **A reset is two steps, and the container is only one of them.** The tokens
   live in the keychain, which is not in the container and survives deleting
   it. That matters more than it sounds: on launch, a live token makes
   `AuthViewModel.swift:207` set `hasConsented = true` and write
   `gdpr_consented` back — so a container-only wipe relaunches you signed in
   with consent already granted, and **the privacy screen never appears**.
   Sections B2 and C1 then quietly test nothing.

   So, in this order:

   1. **In the app, open the Profile tab and choose "Sign out"** (it is in the
      SESSION section, `ProfileView.swift:73-83` — **not** in Settings, which
      has no sign-out at all). This is the reliable way to clear the tokens:
      it calls the app's own `KeychainService.clear()`.
   2. Quit with ⌘Q, then:

      ```
      rm -rf ~/Library/Containers/app.birdo.vpn
      ```

      which removes the sandbox container: the autosaved window frame and the
      consent flags.

   If the app will not open far enough to sign out, the fallback is:

   ```
   security delete-generic-password -s app.birdo.vpn        # repeat until it errors
   security delete-generic-password -s app.birdo.vpn.shared # repeat until it errors
   ```

   ⚠️ Treat that fallback as unverified. There are two services, not one, and
   these items are in the **data-protection** keychain
   (`kSecUseDataProtectionKeychain = true`, `KeychainService.swift:23`), which
   the `security` CLI does not reliably reach on macOS. Prefer Sign Out.

   **Verify the reset rather than assuming it:** relaunch. If you do not see the
   first-launch privacy screen, you are not reset — do not proceed, and do not
   record the section as a pass.

   Do **both** steps wherever a section below says "reset" or "clean state".

2. Launch on the **built-in display**, not an external monitor.

**Correct:** the whole window is visible. The title bar sits fully **below** the
menu bar and can be grabbed with the mouse.

**Regression:** any part of the title bar is under the menu bar, or the window is
taller than the screen.

3. Resize to the **smallest** the app allows. At minimum size the title bar must
   still be reachable and the Connect button still visible.
4. Resize **larger**, and maximise. It must still resize — if it is locked to one
   size, that is its own guideline-4 problem and a regression from this fix.

⚠️ If the window opens correctly but only because the display is large, the test
has not passed. Repeat on the built-in display with the Dock visible.

### A2. The RESTORED-frame run `BLOCKER`

**A1 does not exercise the fix.** Deleting saved state means the window opens at
its default size, which already fits — so `clampToVisibleFrame()` finds nothing
to correct and returns immediately. A1 proves the default is sane; it proves
nothing about the code written to handle a restored frame.

**And a restored frame is what the reviewer has.** Their Mac already ran the
rejected 1.4.23, so it has a saved window frame, and AppKit restores that in
preference to `.defaultSize`. A1 tests the one machine state the reviewer is not
in.

1. **Do not** wipe the container this time.
2. Give it a saved frame bigger than the screen.

   You **cannot** do this by dragging — AppKit will not let a window exceed the
   screen, nor let the title bar go above the menu bar, fix or no fix. And do
   **not** use an external monitor: when the saved screen is gone macOS
   relocates the window itself, *before* the app gets `onAppear`, so you would
   be watching macOS do the clamp's job and could not tell the two apart.

   Write the oversized frame directly instead. Launch the app once and quit
   with ⌘Q so a frame exists, then:

   ```
   P=~/Library/Containers/app.birdo.vpn/Data/Library/Preferences/app.birdo.vpn.plist
   defaults read "$P" | grep -i "NSWindow Frame"
   ```

   That prints the autosave key and its current value. The value is eight
   numbers: `x y w h` for the window, then `x y w h` for the screen it was
   saved on. Keeping the key name exactly as printed, write back a window
   height far taller than your display and a negative `y` so it also starts
   above the menu bar:

   ```
   killall cfprefsd                      # drop the cached copy first
   defaults write "$P" "NSWindow Frame <key-name-from-above>" "0 -600 1200 2600 0 0 1512 944"
   ```

   (Replace the last four numbers with the screen values `defaults read`
   printed for your Mac.)

   If `defaults read` shows **no** `NSWindow Frame` key, stop and report that:
   it would mean the frame is not being autosaved, and this whole section —
   and the premise of the guideline-4 fix — rests on it being.

3. Relaunch.

**Correct:** the window comes back **fitting inside the screen** — its height has
been reduced and the title bar sits below the menu bar. The clamp corrected the
restored frame.

**Regression:** it returns at the oversized height you left it. That means the
clamp ran before AppKit restored the frame, or not at all — and the guideline-4
fix does not work on the reviewer's machine, which is the one machine it must
work on.

4. *(optional, informational — NOT a substitute for steps 2-3)* If you happen
   to have an external display, you can also maximise on it, unplug, and
   relaunch. Note this proves nothing either way: macOS relocates a window
   whose saved screen is gone **before** `onAppear`, so the clamp may
   legitimately have nothing left to do. **There is no pass/fail here, and this
   step cannot be used to sign off A2.** A2 passes or fails on steps 2-3.

## B. Connect with no account `BLOCKER` — guideline 5.1.1(v)

The rejection: *"the app requires users to register before accessing non
account-based features (connect to VPN)."*

Start from a genuinely clean state — **the two-step reset in A step 1** (Sign
Out, then delete the container). Confirm it worked by seeing the first-launch
privacy screen; if you do not see it, you still have a session.

1. Launch. You should land in the **guest shell**, signed out.
2. Confirm the Connect button reads **"Connect"**, not "Sign in to connect", and
   the note below it says no account is needed.
3. **Tap Connect.**

**Correct:** a sheet appears showing a spinner and *"Setting up your anonymous
account"*. **No email field, no password field, no tabs.** Then it shows a
24-digit number with a prompt to save it. Acknowledge it.

**Regression — this is the rejection reproducing:** the tabbed sign-in form
appears with email/password. If you see a form at any point in this path, the fix
did not work.

4. After acknowledging, you land on Home signed in. **Tap Connect again** and
   confirm the tunnel actually comes up.

> ⚠️ Intended behaviour: you tap Connect **twice** — once to provision, once to
> connect. The ID has to be shown and acknowledged in between, because it is the
> only credential. Auto-connecting after acknowledge was built and removed: three
> review rounds each found it broken a different way, and the guideline requires
> that connecting not demand registration, not that the app connect for you.

5. Verify the account is real: check the Profile tab shows the anonymous number,
   and that traffic is actually flowing through the tunnel (check your egress IP).

### B2. The consent-deferred path `BLOCKER`

**Do not skip this.** It is a separate user class, it is the one App Review is
most likely to be in ("Not now" is offered on the very first screen), and a
build that passes B can still fail here. Review found this exact path still
showing the rejected form after B had been declared passing.

1. Fully reset — **both steps** from A step 1: Sign Out, *then* delete the
   container. Deleting the container alone leaves the tokens in the keychain,
   the app relaunches signed in with consent grandfathered, and the privacy
   screen never appears — so this section silently tests nothing. **If you do
   not see the privacy screen, you are not reset.**
2. On the first-launch privacy screen tap **"Not now"**, not "I Agree".
3. You land in the guest shell. **Tap Connect.**

**Correct:** the privacy screen appears in a sheet. Accept it. The sheet then
moves *by itself* to "Setting you up" and the 24-digit number — with **no
email or password form at any point**.

**Regression — this is the rejection reproducing:** after accepting consent you
are shown the tabbed sign-in form, and/or a headline reading **"Sign in to
connect"**. If you see either, stop; the fix does not cover this user.

4. Repeat with a location row instead of Connect. ⚠️ **There is no "Servers"
   tab** — the tabs are Profile / Connect / Limit / Settings
   (`ContentView.swift:88-93`). The list is a screen pushed from Home, and for
   a guest it is titled **"Locations"** (`ServerListView.swift:209`). Open it
   from Home's server selector and tap a location row.

## C. Backing out, and the 24-digit ID `BLOCKER`

Two different moments, with deliberately different behaviour. An earlier draft of
this section claimed dismissal always loses the account; that was wrong, and the
code says otherwise.

**C1 — during the mint, backing out is allowed and must ABORT it.**

1. Tap Connect from a clean guest state — again **both steps** from A step 1;
   signing out is what actually makes you a guest. While the
   spinner shows, tap **"Not
   now"**, and on a repeat press **Escape**.

   ⚠️ This is macOS: a SwiftUI `.sheet` is a document-modal window attached to
   the parent, so there is **no swipe-to-dismiss**, and clicking the parent
   window outside the sheet does nothing whether or not the guard works. Only
   "Not now" and **Escape** actually exercise the code under test.

**Correct:** the sheet closes and you are back on Home, **still signed out**.

**Regression:** you end up signed in, or the Profile tab shows an anonymous
number you never saw.

> ⚠️ An account **is** still created server-side either way. Cancelling drops the
> *result* — the request has already been sent and cannot be un-sent (the code
> says so at the cancellation check). So this step always spends one of your
> three mints, and "no account exists" is neither true nor observable from the
> device. What is being tested is that the app does not sign you into an account
> you backed out of.

2. **Change your public IP first** (see the budget table — you have spent three
   mints by now), then tap Connect again.

**Correct:** a fresh mint starts, showing the spinner.

**Regression:** the tabbed email/password form appears, or a headline reading
"Sign in to connect", **and the sheet says nothing about a rate limit**. If it
does mention an hourly IP limit, you simply ran out of mints — change IP and
repeat this step; that is not the rejection.

**C2 — once the 24-digit number is shown, it must NOT be dismissable.**

3. Reach the number. Try **Escape**, then **⌘W**.

**Correct:** the sheet refuses to close.

🔴 Test with Escape and ⌘W **only**. On macOS a sheet has no swipe gesture and
clicking outside it is inert *by default* — so "I swiped and it did not close"
and "I clicked away and it did not close" are true on a build with the guard
removed. Recording those as passes is how this section signs off a regression.
The guard under test is `.interactiveDismissDisabled(authVM.createdAnonymousId
!= nil)` (`ContentView.swift:205-208`), and Escape is what exercises it. The number stays on screen.

**Regression:** the sheet closes before you acknowledge.

4. Copy the number. Sign out, then sign back in using the anonymous tab and that
   number. It must work.

> The number also appears on the Profile tab afterwards, so it is recoverable
> even if C2 ever regresses — but do not treat that as a substitute. It is shown
> once, on purpose, because that is when the user is looking.

## D. Rate limit — the 429 path

Anonymous creation is capped at **3 new accounts per IP per hour**. Testing B and
C repeatedly will hit it, so exercise it deliberately rather than being surprised.

1. From a clean state, mint four or more accounts within one hour. **Tapping
   Connect repeatedly does not do this** — after the first account you are
   signed in, so Connect dials the tunnel instead of minting. Between each
   attempt you must sign out** — that is all. `completeLocalLogout()` already calls
   `keychain.clear()` (`AuthViewModel.swift:779-782`), which leaves you a guest
   with consent intact, so Connect mints again. Do **not** use the `security`
   fallback here; this page already flags it as unverified.
2. On the attempt that trips the limit:

**Correct:** the spinner is replaced by the ordinary sign-in form carrying a
message that explains it is an **IP** limit lasting an hour, not the user's
fault, and that the app still works without an account. Email and SSO remain
available.

**Regression:** a spinner that never resolves, a blank sheet, a message about
"too many attempts, please wait a moment" (wrong — the wait is an hour), or no
message at all.

## E. Existing users are unaffected

1. Sign in with a **real email account**. Connect.

**Correct:** unchanged behaviour throughout. No anonymous account is created, no
provisioning sheet appears.

2. Sign out and back in. Confirm nothing has changed for account holders.

## F. iOS did not regress

The same code ships on iOS, and Apple did not cite iOS this round — so the job
here is confirming nothing broke.

1. On an iPhone, run **B** and **C**. Both must behave identically.

   ⚠️ **Budget:** that is 6 more mints (B 1 + C1 4 + C2 1). The iPhone has its
   own `device_id`, so it gets its own 5-per-24h bucket — but it shares your
   **public IP**, so those mints land in the same hourly bucket as the Mac's.
   Run F on day 3 after D, on a different network or a fresh hour, and expect
   to split it across two hours.
2. Confirm an existing signed-in iOS user sees no change.

---

## Reporting

Per section: pass / fail / not run. On failure capture the exact step, a
screenshot, and Console output for the app.

**A, A2, B, B2 and C are blockers** — all **five** must pass before you resubmit. D, E, F are
important; a release could proceed with them noted as untested only if you accept
that risk explicitly rather than leaving it implied.

## Before resubmitting

- [ ] A, A2, B, B2 and C pass on a 15-inch (or smaller) built-in display
- [ ] The four subscriptions are attached to the **macOS** submission — see
      `APPLE-MACOS-WEBUI-STEPS.md`, and verify via the API afterwards rather
      than trusting the UI
- [ ] Build number bumped and uploaded
- [ ] Review notes explain the 5.1.1 change in plain terms: connecting no longer
      requires an account; tapping Connect creates an anonymous one with no
      email, no password and no form
