import XCTest

/// MR-953 F1 — pins `HeartbeatHTTP`, the bytes the extension's heartbeat now
/// sends THROUGH the tunnel on a raw TLS connection (TunnelHeartbeatTransport)
/// instead of a URLSession, which NetworkExtension routes outside the
/// provider's own tunnel. The request must say exactly what the URLSession one
/// said, and the answer must be read the way the server writes it.
final class HeartbeatHTTPTests: XCTestCase {

    private func heartbeatRequest() -> URLRequest {
        var request = URLRequest(url: URL(string: "https://api.birdo.app/vpn/heartbeat/key-123")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer test-token-not-real", forHTTPHeaderField: "Authorization")
        request.setValue("ios", forHTTPHeaderField: "X-Desktop-Client")
        request.setValue("BirdoVPN/0.0.0-test", forHTTPHeaderField: "User-Agent")
        return request
    }

    private func text(_ data: Data?) -> String { String(decoding: data ?? Data(), as: UTF8.self) }

    // MARK: - Request

    func testTheHeartbeatRequestCarriesWhatTheURLSessionOneDid() throws {
        let bytes = try XCTUnwrap(HeartbeatHTTP.encode(heartbeatRequest()))
        let lines = text(bytes).components(separatedBy: "\r\n")
        XCTAssertEqual(lines.first, "POST /vpn/heartbeat/key-123 HTTP/1.1")
        XCTAssertEqual(lines[1], "Host: api.birdo.app")
        for header in ["Authorization: Bearer test-token-not-real",
                       "Content-Type: application/json",
                       "X-Desktop-Client: ios",
                       "User-Agent: BirdoVPN/0.0.0-test",
                       "Content-Length: 0",
                       "Connection: close",
                       "Accept-Encoding: identity"] {
            XCTAssertTrue(lines.contains(header), "missing \(header)")
        }
        XCTAssertTrue(text(bytes).hasSuffix("\r\n\r\n"), "an empty POST ends at the blank line")
    }

    /// The provider builds the URL relative to its base, exactly like this.
    func testTheProvidersRelativeURLIsResolvedAgainstItsBase() throws {
        var request = heartbeatRequest()
        request.url = URL(string: "/vpn/heartbeat/key-123", relativeTo: URL(string: "https://api.birdo.app")!)
        let lines = text(HeartbeatHTTP.encode(request)).components(separatedBy: "\r\n")
        XCTAssertEqual(lines.first, "POST /vpn/heartbeat/key-123 HTTP/1.1")
        XCTAssertEqual(lines[1], "Host: api.birdo.app")
    }

    func testTheBodyFollowsTheHeadWithItsLength() throws {
        var request = heartbeatRequest()
        request.httpBody = Data(#"{"a":1}"#.utf8)
        let s = text(HeartbeatHTTP.encode(request))
        XCTAssertTrue(s.contains("Content-Length: 7\r\n"))
        XCTAssertTrue(s.hasSuffix("\r\n\r\n{\"a\":1}"))
    }

    func testTheQueryAndANonDefaultPortAreKept() {
        var request = URLRequest(url: URL(string: "https://api.birdo.app:8443/x/y?z=1")!)
        request.httpMethod = "GET"
        let s = text(HeartbeatHTTP.encode(request))
        XCTAssertTrue(s.hasPrefix("GET /x/y?z=1 HTTP/1.1\r\nHost: api.birdo.app:8443\r\n"))
    }

    func testCallerHeadersCannotOverrideTheFramingOnes() {
        var request = heartbeatRequest()
        request.setValue("keep-alive", forHTTPHeaderField: "Connection")
        request.setValue("gzip", forHTTPHeaderField: "Accept-Encoding")
        request.setValue("evil.example", forHTTPHeaderField: "Host")
        let lines = text(HeartbeatHTTP.encode(request)).components(separatedBy: "\r\n")
        XCTAssertEqual(lines.filter { $0.lowercased().hasPrefix("connection:") }, ["Connection: close"])
        XCTAssertEqual(lines.filter { $0.lowercased().hasPrefix("accept-encoding:") }, ["Accept-Encoding: identity"])
        XCTAssertEqual(lines.filter { $0.lowercased().hasPrefix("host:") }, ["Host: api.birdo.app"])
    }

    /// Plain http would put the bearer token on the wire in the clear.
    func testOnlyHTTPSIsEverWritten() {
        var request = heartbeatRequest()
        request.url = URL(string: "http://api.birdo.app/vpn/heartbeat/key-123")
        XCTAssertNil(HeartbeatHTTP.encode(request))
    }

    func testAHeaderValueThatCouldSplitTheRequestIsRefused() {
        var request = heartbeatRequest()
        request.setValue("BirdoVPN\r\nX-Injected: 1", forHTTPHeaderField: "User-Agent")
        // URLRequest may already strip or keep it; either way nothing injected may be written.
        if let bytes = HeartbeatHTTP.encode(request) {
            XCTAssertFalse(text(bytes).contains("\r\nX-Injected: 1\r\n"))
        }
    }

    // MARK: - Response

    func testAContentLengthAnswerIsCompleteAtItsLength() throws {
        let body = #"{"valid":true,"serverOnline":true}"#
        let raw = Data("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(body.utf8.count)\r\n\r\n\(body)".utf8)
        XCTAssertNil(try HeartbeatHTTP.parse(raw.prefix(raw.count - 1), atEOF: false), "one byte short")
        let response = try XCTUnwrap(HeartbeatHTTP.parse(raw, atEOF: false))
        XCTAssertEqual(response, HeartbeatHTTP.Response(statusCode: 200, body: Data(body.utf8)))
    }

    func testARevokedAnswerReadsAsTheProviderExpects() throws {
        let body = #"{"valid":false}"#
        let raw = Data("HTTP/1.1 200 OK\r\ncontent-length: \(body.utf8.count)\r\n\r\n\(body)".utf8)
        let response = try XCTUnwrap(HeartbeatHTTP.parse(raw, atEOF: false))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: response.body) as? [String: Any])
        XCTAssertEqual(json["valid"] as? Bool, false)
    }

    func testAChunkedAnswerIsDecoded() throws {
        let raw = Data("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n7\r\n{\"valid\r\n7;ext=1\r\n\":true}\r\n0\r\n\r\n".utf8)
        XCTAssertNil(try HeartbeatHTTP.parse(raw.prefix(raw.count - 7), atEOF: false), "last chunk not in yet")
        let response = try XCTUnwrap(HeartbeatHTTP.parse(raw, atEOF: false))
        XCTAssertEqual(text(response.body), #"{"valid":true}"#)
    }

    func testAnAnswerWithNoLengthEndsWhenTheServerCloses() throws {
        let raw = Data("HTTP/1.1 401 Unauthorized\r\nContent-Type: text/plain\r\n\r\nnope".utf8)
        XCTAssertNil(try HeartbeatHTTP.parse(raw, atEOF: false))
        XCTAssertEqual(try HeartbeatHTTP.parse(raw, atEOF: true),
                       HeartbeatHTTP.Response(statusCode: 401, body: Data("nope".utf8)))
    }

    func testAnInterimAnswerIsSkipped() throws {
        let raw = Data("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n".utf8)
        XCTAssertEqual(try HeartbeatHTTP.parse(raw, atEOF: false),
                       HeartbeatHTTP.Response(statusCode: 204, body: Data()))
    }

    func testAClosedConnectionShortOfTheLengthIsTruncatedNotAnAnswer() {
        let raw = Data("HTTP/1.1 200 OK\r\nContent-Length: 50\r\n\r\n{\"valid\":fa".utf8)
        XCTAssertThrowsError(try HeartbeatHTTP.parse(raw, atEOF: true)) {
            XCTAssertEqual($0 as? HeartbeatHTTP.ParseError, .truncated)
        }
        XCTAssertThrowsError(try HeartbeatHTTP.parse(Data(), atEOF: true))
    }

    func testGarbageIsMalformed() {
        for raw in ["SSH-2.0-OpenSSH\r\n\r\n", "HTTP/1.1 2000 OK\r\n\r\n", "HTTP/1.1 200 OK\r\nno colon\r\n\r\n",
                    "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n"] {
            XCTAssertThrowsError(try HeartbeatHTTP.parse(Data(raw.utf8), atEOF: false), raw)
        }
    }

    func testAnOversizedAnswerIsRefused() {
        let head = Data("HTTP/1.1 200 OK\r\nContent-Length: \(HeartbeatHTTP.maxResponseBytes + 1)\r\n\r\n".utf8)
        XCTAssertThrowsError(try HeartbeatHTTP.parse(head, atEOF: false)) {
            XCTAssertEqual($0 as? HeartbeatHTTP.ParseError, .tooLarge)
        }
        let flood = Data(repeating: 0x41, count: HeartbeatHTTP.maxResponseBytes + 1)
        XCTAssertThrowsError(try HeartbeatHTTP.parse(flood, atEOF: false))
    }

    // MARK: - Lengths the peer sends are bounded before any arithmetic

    private func chunked(_ body: String) -> Data {
        Data("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n\(body)".utf8)
    }

    private func assertRefused(_ raw: Data, _ expected: HeartbeatHTTP.ParseError,
                               file: StaticString = #filePath, line: UInt = #line) {
        for atEOF in [false, true] {
            XCTAssertThrowsError(try HeartbeatHTTP.parse(raw, atEOF: atEOF), file: file, line: line) {
                XCTAssertEqual($0 as? HeartbeatHTTP.ParseError, expected, file: file, line: line)
            }
        }
    }

    /// The review's input. `body.count + size` overflowed on a chunk size of
    /// Int.max and TRAPPED, in debug and at -O alike: the extension died, and
    /// with it the tunnel. A trap cannot be caught, so on the old code this
    /// test does not fail, it kills the test process.
    func testAChunkSizeOfIntMaxIsRefusedNotAddedToTheBodyLength() {
        assertRefused(chunked("1\r\na\r\n7fffffffffffffff\r\n"), .tooLarge)
        assertRefused(chunked("7fffffffffffffff\r\n"), .tooLarge)
        assertRefused(chunked("7FFFFFFFFFFFFFFE\r\n"), .tooLarge)
    }

    /// Too long to fit an Int at all: the same answer, not a different path.
    func testAChunkSizeThatDoesNotFitAnIntIsTooLarge() {
        assertRefused(chunked("8000000000000000\r\n"), .tooLarge)
        assertRefused(chunked("ffffffffffffffffffffffff\r\n"), .tooLarge)
    }

    /// Each chunk fits the cap; together they do not. No bytes of the second
    /// chunk are needed to refuse it.
    func testChunksThatTogetherExceedTheCapAreRefused() {
        let first = String(repeating: "a", count: 40_000)
        assertRefused(chunked("9c40\r\n\(first)\r\n7530\r\n"), .tooLarge)
        // 40 000 + 25 536 is exactly the cap: still an answer in the making.
        XCTAssertEqual(40_000 + 0x63c0, HeartbeatHTTP.maxResponseBytes)
        XCTAssertNoThrow(try HeartbeatHTTP.parse(chunked("9c40\r\n\(first)\r\n63c0\r\n"), atEOF: false))
        assertRefused(chunked("9c40\r\n\(first)\r\n63c1\r\n"), .tooLarge)
    }

    /// `Int(_:radix:)` accepts a sign; a length does not have one.
    func testASignedOrEmptyChunkSizeIsMalformed() {
        for size in ["-0", "+5", "-1", "", ";ext=1", "0x5", "5 5", "g"] {
            assertRefused(chunked("\(size)\r\nhello\r\n0\r\n\r\n"), .malformed)
        }
    }

    func testAContentLengthIsPlainDigitsWithinTheCap() {
        func answer(_ length: String) -> Data {
            Data("HTTP/1.1 200 OK\r\nContent-Length: \(length)\r\n\r\nhello".utf8)
        }
        for length in ["+5", "-0", "-5", "5, 5", "0x5", "5.0", ""] {
            assertRefused(answer(length), .malformed)
        }
        for length in ["9223372036854775807", "9223372036854775808",
                       "999999999999999999999999999999", "\(HeartbeatHTTP.maxResponseBytes + 1)"] {
            assertRefused(answer(length), .tooLarge)
        }
        XCTAssertEqual(try HeartbeatHTTP.parse(answer("005"), atEOF: false)?.body, Data("hello".utf8))
    }

    func testAStatusCodeIsThreePlainDigits() {
        for status in ["+20", "-20", "2e2", "20", "2000"] {
            assertRefused(Data("HTTP/1.1 \(status) OK\r\nContent-Length: 0\r\n\r\n".utf8), .malformed)
        }
    }

    /// Interim answers are skipped, but not without end.
    func testAFloodOfInterimAnswersIsRefused() throws {
        let interim = "HTTP/1.1 100 Continue\r\n\r\n"
        let final = "HTTP/1.1 204 No Content\r\n\r\n"
        let allowed = String(repeating: interim, count: HeartbeatHTTP.maxInterimResponses) + final
        XCTAssertEqual(try HeartbeatHTTP.parse(Data(allowed.utf8), atEOF: false)?.statusCode, 204)
        let flood = String(repeating: interim, count: HeartbeatHTTP.maxInterimResponses + 1) + final
        assertRefused(Data(flood.utf8), .malformed)
    }
}
