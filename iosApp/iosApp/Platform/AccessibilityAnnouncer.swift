import SwiftUI

/// MR-1620 -- spoken state changes. One call site so the platform split lives
/// here: iOS posts a VoiceOver announcement, macOS posts the AppKit equivalent.
enum AccessibilityAnnouncer {
    @MainActor
    static func post(_ message: String) {
        #if canImport(UIKit)
        UIAccessibility.post(notification: .announcement, argument: message)
        #elseif canImport(AppKit)
        NSAccessibility.post(
            element: NSApp as Any,
            notification: .announcementRequested,
            userInfo: [
                .announcement: message,
                .priority: NSAccessibilityPriorityLevel.high.rawValue,
            ]
        )
        #endif
    }
}
