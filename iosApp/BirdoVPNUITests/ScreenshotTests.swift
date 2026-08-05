import XCTest

/// Captures App Store screenshots by driving the real app.
///
/// This is the only way to produce them. `simctl` cannot tap or type, the app
/// gates on a consent modal and then on a session held in the keychain, and the
/// simulator keychain is isolated from the host — so nothing outside the app can
/// seed a login. XCUITest runs in the app's own process space and drives the
/// actual UI, which is why `fastlane snapshot` works this way too.
///
/// Screenshots are emitted as XCTAttachments with `.keepAlways`, so they survive
/// into the .xcresult bundle whether or not the test passes. `xcresulttool`
/// extracts them afterwards.
///
/// The account is a throwaway anonymous ID supplied for exactly this purpose.
/// It is passed as an environment variable rather than hardcoded so the suite
/// carries no credential, and read at runtime so rotating it needs no code
/// change.
final class ScreenshotTests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        // The consent gate is a modal with no identifier stable enough to tap
        // reliably across layouts, and it blocks every screen behind it. The app
        // reads this exact key on launch, so pre-setting it is both the least
        // brittle route and the one that matches what a returning user sees.
        app.launchArguments += ["-gdpr_consented", "YES"]
        // Kill switch OFF for capture only. With it on and no tunnel up, Home
        // shows a red "Kill Switch — All traffic blocked" banner across the hero
        // shot. That is correct behaviour and must stay the shipping default —
        // it just makes for a storefront image that reads as an error state.
        app.launchArguments += ["-kill_switch", "NO"]
        app.launch()
    }

    /// Attach a screenshot under a name the extraction step can recognise.
    private func capture(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// Wait for an element, returning whether it arrived rather than failing.
    ///
    /// A missing element must not abort the run: a partial set of screenshots is
    /// far more useful than none, and the log then says exactly which screen was
    /// unreachable.
    @discardableResult
    private func waitFor(_ element: XCUIElement, _ seconds: TimeInterval = 20) -> Bool {
        element.waitForExistence(timeout: seconds)
    }

    func testCaptureAppStoreScreenshots() throws {
        let accountId = ProcessInfo.processInfo.environment["BIRDO_ANON_ID"] ?? ""
        XCTAssertFalse(accountId.isEmpty, "Set BIRDO_ANON_ID for the screenshot run")

        // ── Sign in with the anonymous account ────────────────────────────────
        // The login screen opens on the EMAIL tab, so the anonymous field does
        // not exist yet — the tab has to be selected first. Skipping this is
        // why the first run captured the login screen instead of the app.
        let anonymousTab = app.buttons["Anonymous"].firstMatch
        if anonymousTab.waitForExistence(timeout: 25) {
            anonymousTab.tap()
        } else {
            // No tab bar means we are already past login (a previous run left a
            // session on this simulator). Record what is on screen and continue.
            capture("00-launch")
        }

        let idField = app.textFields["login_anonymous_id_field"]
        if waitFor(idField, 15) {
            idField.tap()
            idField.typeText(accountId)
            // Dismiss the keyboard so it cannot cover the submit button on the
            // shorter layouts.
            if app.keyboards.buttons["return"].exists {
                app.keyboards.buttons["return"].tap()
            }
            let submit = app.buttons["login_anonymous_submit"]
            if submit.waitForExistence(timeout: 5) { submit.tap() }
        }

        // ── Home / Connect ────────────────────────────────────────────────────
        // Wait for the tab shell rather than a fixed sleep: sign-in latency
        // varies, and capturing on a timer produced a screenshot of the login
        // screen labelled "connect".
        let homeArrived = app.buttons["Settings"].firstMatch.waitForExistence(timeout: 45)
        if !homeArrived {
            // Capture the screen BEFORE asserting: without this the failure says
            // only "still on login" and the reason — a rejected ID, a validation
            // message, an unexpected step — is lost with the simulator.
            capture("99-stuck")
            let err = app.staticTexts["login_error"]
            let detail = err.exists ? err.label : "(no login_error element)"
            XCTFail("Never reached the tab shell. On-screen error: \(detail)")
            return
        }
        // The globe animates in; let it settle rather than catching a
        // half-drawn first frame.
        sleep(12)
        capture("01-connect")

        // ── Server list ───────────────────────────────────────────────────────
        // There is no Servers TAB — the bar is Profile / Connect / Limit /
        // Settings. The list opens from the selected-server row on Home, which
        // is why looking for a tab captured nothing.
        let serverRow = app.buttons.containing(
            NSPredicate(format: "label CONTAINS[c] %@", "birdo-vpn-")
        ).firstMatch
        if serverRow.waitForExistence(timeout: 10) {
            serverRow.tap()
            sleep(5)
            capture("02-servers")
            // Back to Home so Settings is reachable from a known state.
            let back = app.navigationBars.buttons.firstMatch
            if back.exists { back.tap() }
            sleep(2)
        }

        // ── Settings ──────────────────────────────────────────────────────────
        let settingsTab = app.buttons["Settings"].firstMatch
        if settingsTab.waitForExistence(timeout: 10) {
            settingsTab.tap()
            sleep(4)
            capture("03-settings")
        }
    }
}
