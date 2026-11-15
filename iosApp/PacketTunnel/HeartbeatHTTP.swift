import Foundation

/// MR-953 F1 — the heartbeat's HTTP/1.1 exchange as bytes.
///
/// The extension's heartbeat used URLSession, and NetworkExtension routes the
/// provider process's own connections OUTSIDE its own tunnel: the bearer token,
/// the key id and a resolver lookup of api.birdo.app left from the user's real
/// IP every 30 s. The fix sends it through the tunnel on a raw TLS connection
/// (TunnelHeartbeatTransport), which needs the request written out and the
/// answer read back by hand. That is all this file does, and it is
/// Foundation-only so BirdoVPNTests pins it (HeartbeatHTTPTests).
///
/// Deliberately small: one request, `Connection: close`, no compression asked
/// for, a bounded response. The answer is complete at its Content-Length, at
/// the end of a chunked body, or (neither given) when the server closes.
enum HeartbeatHTTP {
    /// What the heartbeat reads from an answer.
    struct Response: Equatable {
        let statusCode: Int
        let body: Data
    }

    enum ParseError: Error, Equatable {
        case malformed
        case tooLarge
        case truncated
    }

    /// The `{valid, serverOnline, message?}` answer is a few dozen bytes; an
    /// error page is a few KB. Anything bigger is not the heartbeat answering.
    static let maxResponseBytes = 64 * 1024

    /// Interim 1xx answers skipped before the real one. None is ever asked
    /// for; the bound keeps a peer from making every read re-walk a buffer of
    /// them.
    static let maxInterimResponses = 4

    private static let headerTerminator = Data("\r\n\r\n".utf8)
    private static let lineTerminator = Data("\r\n".utf8)

    /// The request as HTTP/1.1 bytes, or nil when it cannot be written safely:
    /// not https, no host, or a header name/value carrying CR or LF.
    static func encode(_ request: URLRequest) -> Data? {
        guard let url = request.url?.absoluteURL,
              url.scheme?.lowercased() == "https",
              let host = url.host, !host.isEmpty,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: true) else { return nil }
        var target = components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath
        if let query = components.percentEncodedQuery { target += "?\(query)" }
        let method = request.httpMethod ?? "GET"
        let body = request.httpBody ?? Data()
        var hostHeader = host
        if let port = url.port, port != 443 { hostHeader += ":\(port)" }

        var lines = ["\(method) \(target) HTTP/1.1", "Host: \(hostHeader)"]
        let reserved: Set<String> = ["host", "content-length", "connection", "transfer-encoding", "accept-encoding"]
        for (name, value) in (request.allHTTPHeaderFields ?? [:]).sorted(by: { $0.key < $1.key }) {
            guard !reserved.contains(name.lowercased()) else { continue }
            guard isSafeHeaderToken(name), isSafeHeaderValue(value) else { return nil }
            lines.append("\(name): \(value)")
        }
        if !body.isEmpty || method == "POST" || method == "PUT" || method == "PATCH" {
            lines.append("Content-Length: \(body.count)")
        }
        lines.append("Accept-Encoding: identity")
        lines.append("Connection: close")
        guard isSafeHeaderValue(target), !target.contains(" ") else { return nil }

        var data = Data((lines.joined(separator: "\r\n") + "\r\n\r\n").utf8)
        data.append(body)
        return data
    }

    /// The response in `buffer`, or nil while more bytes are needed.
    ///
    /// Every length here is the PEER's (Content-Length, chunk sizes), so none
    /// is added to or compared with anything before it has been bounded by
    /// `maxResponseBytes`: Swift traps on integer overflow, and a trap in the
    /// extension kills the tunnel. Lengths are plain digits only (no sign, no
    /// whitespace inside), and a number too long to be a sane length is
    /// `.tooLarge` or `.malformed`, never arithmetic.
    /// - Parameter atEOF: the server has closed; a response with no length is
    ///   complete now, and one still short of its length is `.truncated`.
    static func parse(_ buffer: Data, atEOF: Bool) throws -> Response? {
        if buffer.count > maxResponseBytes { throw ParseError.tooLarge }
        var start = buffer.startIndex
        var interim = 0
        while true {
            guard let headEnd = buffer.range(of: headerTerminator, in: start..<buffer.endIndex) else {
                if atEOF { throw buffer.isEmpty ? ParseError.truncated : ParseError.malformed }
                return nil
            }
            guard let head = String(data: buffer[start..<headEnd.lowerBound], encoding: .utf8) else {
                throw ParseError.malformed
            }
            let headLines = head.components(separatedBy: "\r\n")
            let status = try statusCode(headLines[0])
            var headers: [String: String] = [:]
            for line in headLines.dropFirst() {
                guard let colon = line.firstIndex(of: ":") else { throw ParseError.malformed }
                let name = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
                let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                headers[name] = value
            }
            let bodyStart = headEnd.upperBound
            // An interim 1xx answer (never asked for, but legal) is skipped.
            if (100...199).contains(status) && status != 101 {
                interim += 1
                if interim > maxInterimResponses { throw ParseError.malformed }
                start = bodyStart
                continue
            }
            let rest = buffer[bodyStart...]
            if let encoding = headers["transfer-encoding"], encoding.lowercased().contains("chunked") {
                guard let body = try dechunk(rest, atEOF: atEOF) else { return nil }
                return Response(statusCode: status, body: body)
            }
            if let lengthText = headers["content-length"] {
                // Bounded by `boundedLength` before any use: `rest.count` and
                // `length` are both at most `maxResponseBytes` below.
                let length = try boundedLength(lengthText, radix: 10)
                if rest.count >= length {
                    return Response(statusCode: status, body: Data(rest.prefix(length)))
                }
                if atEOF { throw ParseError.truncated }
                return nil
            }
            if status == 204 || status == 304 {
                return Response(statusCode: status, body: Data())
            }
            return atEOF ? Response(statusCode: status, body: Data(rest)) : nil
        }
    }

    // MARK: - Private

    private static func statusCode(_ line: String) throws -> Int {
        let parts = line.split(separator: " ", maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count >= 2, parts[0].hasPrefix("HTTP/1."),
              parts[1].utf8.count == 3, parts[1].utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }),
              let code = Int(parts[1]), (100...599).contains(code) else {
            throw ParseError.malformed
        }
        return code
    }

    /// A length the peer sent (Content-Length in decimal, a chunk size in
    /// hex), as a value in `0...maxResponseBytes`.
    ///
    /// Digits only: `Int(_:radix:)` alone also accepts a sign, so "-0" and
    /// "+5" would pass. A value over the cap is `.tooLarge` whatever its size:
    /// one that does not even fit an Int (where `Int(_:radix:)` returns nil)
    /// is the same answer, not a different error, and nothing is ever added to
    /// an unbounded value.
    private static func boundedLength(_ text: String, radix: Int) throws -> Int {
        let digits = text.utf8
        guard !digits.isEmpty, digits.allSatisfy({ isDigit($0, radix: radix) }) else {
            throw ParseError.malformed
        }
        guard let value = Int(text, radix: radix), value <= maxResponseBytes else {
            throw ParseError.tooLarge
        }
        return value
    }

    private static func isDigit(_ byte: UInt8, radix: Int) -> Bool {
        switch byte {
        case 0x30...0x39: return true                                   // 0-9
        case 0x41...0x46, 0x61...0x66: return radix == 16               // A-F, a-f
        default: return false
        }
    }

    /// The decoded body, or nil while the last chunk has not arrived.
    private static func dechunk(_ data: Data, atEOF: Bool) throws -> Data? {
        var body = Data()
        var cursor = data.startIndex
        while true {
            guard let lineEnd = data.range(of: lineTerminator, in: cursor..<data.endIndex) else {
                if atEOF { throw ParseError.truncated }
                return nil
            }
            guard let sizeLine = String(data: data[cursor..<lineEnd.lowerBound], encoding: .ascii) else {
                throw ParseError.malformed
            }
            let sizeText = sizeLine.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false)
                .first.map(String.init) ?? ""
            // `size` is at most `maxResponseBytes` from here on.
            let size = try boundedLength(sizeText.trimmingCharacters(in: .whitespaces), radix: 16)
            let chunkStart = lineEnd.upperBound
            if size == 0 {
                // Trailers are not used; the body is complete at the last chunk.
                return body
            }
            // SUBTRACT, never add: `body.count + size` trapped on a chunk size
            // of 7fffffffffffffff (Int.max) and killed the extension. `body`
            // never exceeds the cap, so the right-hand side cannot underflow.
            if size > maxResponseBytes - body.count { throw ParseError.tooLarge }
            guard data.distance(from: chunkStart, to: data.endIndex) >= size + 2 else {
                if atEOF { throw ParseError.truncated }
                return nil
            }
            let chunkEnd = data.index(chunkStart, offsetBy: size)
            guard data[chunkEnd..<data.index(chunkEnd, offsetBy: 2)] == lineTerminator else {
                throw ParseError.malformed
            }
            body.append(data[chunkStart..<chunkEnd])
            cursor = data.index(chunkEnd, offsetBy: 2)
        }
    }

    private static func isSafeHeaderToken(_ s: String) -> Bool {
        !s.isEmpty && s.unicodeScalars.allSatisfy { $0.value > 32 && $0.value < 127 && $0 != ":" }
    }

    private static func isSafeHeaderValue(_ s: String) -> Bool {
        !s.unicodeScalars.contains { $0 == "\r" || $0 == "\n" || $0 == "\0" }
    }
}
