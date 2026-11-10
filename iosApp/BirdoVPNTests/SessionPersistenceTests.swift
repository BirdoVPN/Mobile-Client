import XCTest

/// A-L24 / MR-2241 / MA-IOS-010: the keychain write after an anonymous mint is
/// retried once before the app falls back to showing the number unsaved.
final class SessionPersistenceTests: XCTestCase {

    /// A keychain stub that fails its first `failures` writes.
    private final class FlakyStore {
        var failures: Int
        private(set) var calls = 0
        init(failures: Int) { self.failures = failures }
        func save() -> Bool {
            calls += 1
            if failures > 0 { failures -= 1; return false }
            return true
        }
    }

    func testAWriteThatWorksIsNotRepeated() {
        let store = FlakyStore(failures: 0)
        XCTAssertTrue(SessionPersistence.saveWithRetry(store.save))
        XCTAssertEqual(store.calls, 1)
    }

    func testOneTransientFailureIsRetriedAndSucceeds() {
        let store = FlakyStore(failures: 1)
        XCTAssertTrue(SessionPersistence.saveWithRetry(store.save))
        XCTAssertEqual(store.calls, 2)
    }

    func testAKeychainThatKeepsFailingIsTriedTwiceThenReportedFailed() {
        let store = FlakyStore(failures: 100)
        XCTAssertFalse(SessionPersistence.saveWithRetry(store.save))
        XCTAssertEqual(store.calls, 2, "exactly one retry, never a loop")
    }
}
