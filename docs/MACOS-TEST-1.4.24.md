# macOS device test — the three rejection fixes

Run before resubmitting. This is round three; a partial fix restarts the clock.

Neither code fix has ever been run. CI proves the Swift compiles — and it already
caught one invented symbol that `swiftc -parse` missed, so treat a green build as
"it compiles", nothing more. **Guideline 4 is a visual defect: only a human
looking at a screen can close it.**

Use a Mac with a display no taller than the review device — a **15-inch MacBook
Air (M3)**, which is what the reviewer used. On a large external monitor the
window bug will not reproduce even if it is still there.

---

## A. Window position `BLOCKER` — guideline 4

The rejection: *"the app includes windows that laid out and partially hidden
under menu bar."*

1. Quit the app completely. Delete its saved window state so you get a genuine
   first launch:
   `defaults delete app.birdo.vpn 2>/dev/null; rm -rf ~/Library/Saved\ Application\ State/app.birdo.vpn.savedState`
2. Launch on the **built-in display**, not an external monitor.

**Correct:** the whole window is visible. The title bar sits fully **below** the
menu bar and can be grabbed with the mouse.

**Regression:** any part of the title bar is under the menu bar, or the window is
taller than the screen.

3. Drag the window to the very top of the screen and release. It must not be
   possible to park the title bar under the menu bar.
4. Resize to the **smallest** the app allows. At minimum size the title bar must
   still be reachable and the Connect button still visible.
5. Resize **larger**, and maximise. It must still resize — if it is locked to one
   size, that is its own guideline-4 problem and a regression from this fix.

⚠️ If the window opens correctly but only because the display is large, the test
has not passed. Repeat on the built-in display with the Dock visible.

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

## C. The 24-digit ID is never lost `BLOCKER`

This is the risk the fix deliberately traded against. The ID is the **only** way
back into that account.

1. Repeat B up to the point where the 24-digit number is shown.
2. **Try to dismiss the sheet without acknowledging:** swipe down, press Escape,
   click outside it, and use ⌘W.

**Correct:** the sheet refuses to close. The number stays on screen.

**Regression:** the sheet closes. That account is now unrecoverable — report it
immediately, it is worse than the bug being fixed.

3. Copy the number. Sign out, then sign back in using the anonymous tab and that
   number. It must work.

## D. Rate limit — the 429 path

Anonymous creation is capped at **3 new accounts per IP per hour**. Testing B and
C repeatedly will hit it, so exercise it deliberately rather than being surprised.

1. From a clean state, tap Connect four or more times in an hour (creating an
   account each time).
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

**A, B, B2 and C are blockers** — do not resubmit without all four passing. D, E, F are
important; a release could proceed with them noted as untested only if you accept
that risk explicitly rather than leaving it implied.

## Before resubmitting

- [ ] A, B, B2 and C pass on a 15-inch (or smaller) built-in display
- [ ] The four subscriptions are attached to the **macOS** submission — see
      `APPLE-MACOS-WEBUI-STEPS.md`, and verify via the API afterwards rather
      than trusting the UI
- [ ] Build number bumped and uploaded
- [ ] Review notes explain the 5.1.1 change in plain terms: connecting no longer
      requires an account; tapping Connect creates an anonymous one with no
      email, no password and no form
