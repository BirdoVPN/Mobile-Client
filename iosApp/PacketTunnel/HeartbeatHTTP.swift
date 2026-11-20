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
        var reader = ResponseReader()
        reader.append(buffer)
        return try reader.response(atEOF: atEOF)
    }

    /// One response, read as its bytes arrive (the transport keeps one per
    /// connection; `parse` is a reader fed everything at once).
    ///
    /// It remembers where the search for the end of the head stopped and,
    /// once the head is complete, the parsed head itself. So an answer that
    /// arrives a few bytes per read is searched once overall, not once per
    /// read (`headBytesSearched`, pinned by HeartbeatHTTPTests).
    ///
    /// The head is read strictly (RFC 9112), because the answer decides
    /// whether a tunnel is torn down:
    ///   - a field name is a token, with NO whitespace before its colon (this
    ///     also refuses an obsolete folded line);
    ///   - a second Content-Length or Transfer-Encoding is malformed, and so is
    ///     a response carrying both;
    ///   - Transfer-Encoding means chunked only when its FINAL coding is
    ///     exactly `chunked`; any other final coding is read until the close.
    struct ResponseReader {
        /// Everything received so far. Offsets below index it directly: it
        /// starts empty, so its indices start at 0.
        private(set) var received = Data()
        /// Bytes compared while looking for the end of a head, over the
        /// reader's whole life.
        private(set) var headBytesSearched = 0
        private var headStart = 0
        private var searchFrom = 0
        private var interim = 0
        private var head: Head?

        init() {}

        mutating func append(_ data: Data) {
            received.append(data)
        }

        /// The response, or nil while more bytes are needed.
        mutating func response(atEOF: Bool) throws -> Response? {
            if received.count > maxResponseBytes { throw ParseError.tooLarge }
            while head == nil {
                let from = searchFrom
                guard let end = received.range(of: headerTerminator, in: from..<received.count) else {
                    headBytesSearched += received.count - from
                    // The terminator can straddle this read and the next:
                    // resume just short of its length from the end.
                    searchFrom = max(headStart, received.count - (headerTerminator.count - 1))
                    if atEOF { throw received.isEmpty ? ParseError.truncated : ParseError.malformed }
                    return nil
                }
                headBytesSearched += end.upperBound - from
                let parsed = try HeartbeatHTTP.head(received[headStart..<end.lowerBound], bodyStart: end.upperBound)
                if parsed.isInterim {
                    // An interim 1xx answer (never asked for, but legal) is skipped.
                    interim += 1
                    if interim > maxInterimResponses { throw ParseError.malformed }
                    headStart = end.upperBound
                    searchFrom = end.upperBound
                    continue
                }
                head = parsed
            }
            guard let head else { return nil }
            let rest = received[head.bodyStart...]
            switch head.framing {
            case .chunked:
                guard let body = try dechunk(rest, atEOF: atEOF) else { return nil }
                return Response(statusCode: head.status, body: body)
            case .length(let length):
                // `length` was bounded by `boundedLength`, and `rest.count` by
                // `maxResponseBytes` above.
                if rest.count >= length {
                    return Response(statusCode: head.status, body: Data(rest.prefix(length)))
                }
                if atEOF { throw ParseError.truncated }
                return nil
            case .empty:
                return Response(statusCode: head.status, body: Data())
            case .untilClose:
                return atEOF ? Response(statusCode: head.status, body: Data(rest)) : nil
            }
        }
    }

    // MARK: - Private

    private struct Head {
        let status: Int
        let framing: Framing
        let bodyStart: Int
        let isInterim: Bool
    }

    private enum Framing {
        case chunked
        case length(Int)
        case empty
        case untilClose
    }

    /// One head (status line and fields, without the blank line).
    private static func head(_ bytes: Data, bodyStart: Int) throws -> Head {
        guard let text = String(data: bytes, encoding: .utf8) else { throw ParseError.malformed }
        let lines = text.components(separatedBy: "\r\n")
        let status = try statusCode(lines[0])
        var contentLength: String?
        var transferEncoding: String?
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { throw ParseError.malformed }
            // RFC 9112 5.1: no whitespace between the name and the colon. A
            // name is a token, so a leading space (an obsolete folded line)
            // fails here too.
            let name = line[..<colon]
            guard isToken(name) else { throw ParseError.malformed }
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            switch name.lowercased() {
            case "content-length":
                // RFC 9112 6.3: differing (or repeated) lengths are an error.
                guard contentLength == nil else { throw ParseError.malformed }
                contentLength = value
            case "transfer-encoding":
                guard transferEncoding == nil else { throw ParseError.malformed }
                transferEncoding = value
            default:
                break
            }
        }
        if (100...199).contains(status) && status != 101 {
            return Head(status: status, framing: .empty, bodyStart: bodyStart, isInterim: true)
        }
        if let transferEncoding {
            // RFC 9112 6.1: both at once "ought to be handled as an error".
            guard contentLength == nil else { throw ParseError.malformed }
            // RFC 9112 6.3: only a FINAL coding of chunked frames the body;
            // otherwise the body runs until the server closes.
            let lastCoding = transferEncoding.split(separator: ",", omittingEmptySubsequences: false).last
                .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
            return Head(status: status, framing: lastCoding == "chunked" ? .chunked : .untilClose,
                        bodyStart: bodyStart, isInterim: false)
        }
        if let contentLength {
            return Head(status: status, framing: .length(try boundedLength(contentLength, radix: 10)),
                        bodyStart: bodyStart, isInterim: false)
        }
        if status == 204 || status == 304 {
            return Head(status: status, framing: .empty, bodyStart: bodyStart, isInterim: false)
        }
        return Head(status: status, framing: .untilClose, bodyStart: bodyStart, isInterim: false)
    }

    /// RFC 9110 5.6.2 `token`: visible ASCII except the delimiters.
    private static func isToken(_ name: Substring) -> Bool {
        !name.isEmpty && name.utf8.allSatisfy { byte in
            switch byte {
            case 0x30...0x39, 0x41...0x5A, 0x61...0x7A: return true          // 0-9 A-Z a-z
            default: return "!#$%&'*+-.^_`|~".utf8.contains(byte)
            }
        }
    }

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
