import XCTest

/// Captures App Store screenshots by driving the real app, on iOS and on macOS.
///
/// This is the only way to produce them. `simctl` cannot tap or type, the app
/// gates on a consent modal and then on a session held in the keychain, and the
/// simulator keychain is isolated from the host — so nothing outside the app can
/// seed a login. XCUITest runs in the app's own process space and drives the
/// actual UI, which is why `fastlane snapshot` works this way too.
///
/// On macOS the same constraints bite harder: `screencapture` over SSH cannot
/// reach the window server, and installing the signed .pkg locally needs root.
/// XCUITest sidesteps both by launching the app in the console GUI session.
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

    // MARK: - Platform shims

    /// AppKit has no touch, so the same gesture is `click()` on macOS and
    /// `tap()` on iOS. Every interaction below goes through this rather than
    /// carrying an `#if` at each call site.
    private func press(_ element: XCUIElement) {
        #if os(macOS)
        element.click()
        #else
        element.tap()
        #endif
    }

    /// Attach a screenshot under a name the extraction step can recognise.
    ///
    /// On macOS this captures the app's own windows rather than the whole
    /// display, so the runner's desktop and menu bar stay out of the shot.
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

    /// Finds a control by label across the element types SwiftUI can pick on
    /// each platform. A SwiftUI `TabView` is a button row on iOS but renders as
    /// radio buttons or tabs on macOS, so querying `buttons` alone finds
    /// nothing there.
    private func control(_ label: String) -> XCUIElement? {
        let candidates: [XCUIElementQuery] = [
            app.buttons,
            app.radioButtons,
            app.tabs,
            app.staticTexts,
        ]
        for query in candidates {
            let element = query[label].firstMatch
            if element.exists { return element }
        }
        return nil
    }

    func testCaptureAppStoreScreenshots() throws {
        let accountId = ProcessInfo.processInfo.environment["BIRDO_ANON_ID"] ?? ""
        XCTAssertFalse(accountId.isEmpty, "Set BIRDO_ANON_ID for the screenshot run")

        // Record the launch state unconditionally. On a platform being driven
        // for the first time this is the only evidence of where it stopped if
        // the queries below find nothing.
        capture("00-launch")

        // ── Sign in with the anonymous account ────────────────────────────────
        // The login screen opens on the EMAIL tab, so the anonymous field does
        // not exist yet — the tab has to be selected first. Skipping this is
        // why the first run captured the login screen instead of the app.
        if let anonymousTab = control("Anonymous") {
            press(anonymousTab)
        }

        let idField = app.textFields["login_anonymous_id_field"]
        if waitFor(idField, 15) {
            press(idField)
            idField.typeText(accountId)
            // Dismiss the software keyboard so it cannot cover the submit
            // button on the shorter layouts. macOS has no software keyboard,
            // hence the existence check rather than an unconditional tap.
            if app.keyboards.buttons["return"].exists {
                press(app.keyboards.buttons["return"])
            }
            if let submit = control("login_anonymous_submit") {
                press(submit)
            } else {
                let submit = app.buttons["login_anonymous_submit"]
                if submit.waitForExistence(timeout: 5) { press(submit) }
            }
        }

        // ── Home / Connect ────────────────────────────────────────────────────
        // Wait for the tab shell rather than a fixed sleep: sign-in latency
        // varies, and capturing on a timer produced a screenshot of the login
        // screen labelled "connect".
        var homeArrived = false
        let deadline = Date().addingTimeInterval(45)
        while Date() < deadline {
            if control("Settings") != nil { homeArrived = true; break }
            usleep(500_000)
        }
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
            press(serverRow)
            sleep(5)
            capture("02-servers")
            // Back to Home so Settings is reachable from a known state.
            let back = app.navigationBars.buttons.firstMatch
            if back.exists { press(back) }
            sleep(2)
        }

        // ── Settings ──────────────────────────────────────────────────────────
        if let settingsTab = control("Settings") {
            press(settingsTab)
            sleep(4)
            capture("03-settings")
        }
    }
}
