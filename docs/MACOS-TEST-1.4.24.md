# macOS device test — the three rejection fixes

Run before resubmitting. This is round three; a partial fix restarts the clock.

Neither code fix has ever been run. CI proves the Swift compiles — and it already
caught one invented symbol that `swiftc -parse` missed, so treat a green build as
"it compiles", nothing more. **Guideline 4 is a visual defect: only a human
looking at a screen can close it.**

Use a Mac with a display no taller than the review device — a **15-inch MacBook
Air (M3)**, which is what the reviewer used. On a large external monitor the
window bug will not reproduce even if it is still there.

## ⚠️ Read this before starting: you get three account creations per hour

`POST /auth/register/anonymous` is rate limited **server-side to 3 creations per
IP per hour**. The client keeps no count and nothing resets it.

Sections B, B2 and C each mint at least one account, so running them
back-to-back **exhausts the budget partway through C** — and a 429 renders the
tabbed sign-in form under the headline "Sign in to connect", which B, B2 and C
all name as the regression. You would report the rejection as reproducing on a
correct build.

So:

- **Run C1 first**, then B, then B2, and treat D as its own session.
- Between sections, either wait the hour or **change your public IP** (phone
  hotspot, different network). A VPN to somewhere else works too — you are
  testing the app, not the tunnel, at that point.
- If you hit a 429 mid-section, that is **not** a failure. Its message names an
  IP limit lasting an hour. Stop, change IP or wait, and restart that section.

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

   The command that actually works:

   ```
   rm -rf ~/Library/Containers/app.birdo.vpn
   ```

   That removes the sandbox container, including the autosaved window frame and
   the consent flag. Use this same command wherever a section below says "reset"
   or "clean state".

2. Launch on the **built-in display**, not an external monitor.

**Correct:** the whole window is visible. The title bar sits fully **below** the
menu bar and can be grabbed with the mouse.

**Regression:** any part of the title bar is under the menu bar, or the window is
taller than the screen.

3. Resize to the **smallest** the app allows. At minimum size the title bar must
   still be reachable and the Connect button still visible.
5. Resize **larger**, and maximise. It must still resize — if it is locked to one
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
2. Launch the app and **resize it taller than the display** — drag the bottom
   edge down until the window is clearly taller than the screen. Quit with ⌘Q so
   the frame is saved.

   > Do not bother trying to drag the title bar above the menu bar: AppKit
   > refuses that for any titled window, with or without this fix. Only the
   > oversize case is reachable, so that is what this tests.

3. Relaunch.

**Correct:** the window comes back **fitting inside the screen** — its height has
been reduced and the title bar sits below the menu bar. The clamp corrected the
restored frame.

**Regression:** it returns at the oversized height you left it. That means the
clamp ran before AppKit restored the frame, or not at all — and the guideline-4
fix does not work on the reviewer's machine, which is the one machine it must
work on.

4. *(optional, informational)* Repeat with an external display attached, then
   unplug it before relaunching. macOS relocates a window whose saved screen is
   gone **before** the app gets `onAppear`, so the clamp may legitimately have
   nothing to do here. Report what you see; there is no pass/fail.

## B. Connect with no account `BLOCKER` — guideline 5.1.1(v)

The rejection: *"the app requires users to register before accessing non
account-based features (connect to VPN)."*

Start from a genuinely clean state — sign out, then delete the keychain entries
so the app has no session and no stored anonymous id.

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

1. Fully reset: delete the app's data so the privacy screen appears again.
2. On the first-launch privacy screen tap **"Not now"**, not "I Agree".
3. You land in the guest shell. **Tap Connect.**

**Correct:** the privacy screen appears in a sheet. Accept it. The sheet then
moves *by itself* to "Setting you up" and the 24-digit number — with **no
email or password form at any point**.

**Regression — this is the rejection reproducing:** after accepting consent you
are shown the tabbed sign-in form, and/or a headline reading **"Sign in to
connect"**. If you see either, stop; the fix does not cover this user.

4. Repeat with a location row on the Servers tab instead of Connect.

## C. Backing out, and the 24-digit ID `BLOCKER`

Two different moments, with deliberately different behaviour. An earlier draft of
this section claimed dismissal always loses the account; that was wrong, and the
code says otherwise.

**C1 — during the mint, backing out is allowed and must ABORT it.**

1. Tap Connect from a clean guest state. While the spinner shows, tap **"Not
   now"** (and on a repeat, swipe the sheet away, and press Escape).

**Correct:** the sheet closes and you are back on Home, **still signed out**.

**Regression:** you end up signed in, or the Profile tab shows an anonymous
number you never saw.

> ⚠️ An account **is** still created server-side either way. Cancelling drops the
> *result* — the request has already been sent and cannot be un-sent (the code
> says so at the cancellation check). So this step always spends one of your
> three mints, and "no account exists" is neither true nor observable from the
> device. What is being tested is that the app does not sign you into an account
> you backed out of.

2. Tap Connect again. **Correct:** a fresh mint starts, showing the spinner.
**Regression:** the tabbed email/password form appears, or a headline reading
"Sign in to connect". Either is the rejection reproducing.

**C2 — once the 24-digit number is shown, it must NOT be dismissable.**

3. Reach the number. Try swipe down, Escape, click outside, ⌘W.

**Correct:** the sheet refuses to close. The number stays on screen.

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
   attempt you must sign out AND clear the stored session (delete the app's
   keychain entries) so the app is a guest again.
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
2. Confirm an existing signed-in iOS user sees no change.

---

## Reporting

Per section: pass / fail / not run. On failure capture the exact step, a
screenshot, and Console output for the app.

**A, A2, B, B2 and C are blockers** — do not resubmit without all four passing. D, E, F are
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
