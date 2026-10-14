import Foundation

/// MR-1100 — the PURE half of what the Home globe draws for a connection.
///
/// A single-hop session is one great-circle leg, "you" -> server. A Multi-Hop
/// session is TWO: "you" -> entry, then entry -> exit. The globe used to take
/// one focus id, so a Multi-Hop session drew only the first leg and the exit
/// country appeared nowhere on the globe.
///
/// Foundation-only on purpose: the BirdoVPNTests unit bundle compiles this file
/// (and CountryCoords.swift) directly, so the leg list, the camera focus and the
/// draw order are pinned by tests. WorldGlobeView itself is a SwiftUI Canvas and
/// cannot be.
enum GlobeRoute {
    struct Coord: Equatable, Sendable {
        let lat: Double
        let lon: Double
    }

    struct Leg: Equatable, Sendable {
        let from: Coord
        let to: Coord
    }

    /// The legs to draw, in travel order. Empty with no entry (nothing
    /// focused, nothing to draw). A leg whose two ends share a coordinate
    /// (entry and exit in the same country) is dropped: it has no length to
    /// draw, and the renderer would skip it anyway.
    static func legs(user: Coord, entry: Coord?, exit: Coord?) -> [Leg] {
        guard let entry else { return [] }
        var legs = [Leg(from: user, to: entry)]
        if let exitPoint = exit {
            legs.append(Leg(from: entry, to: exitPoint))
        }
        return legs.filter { $0.from != $0.to }
    }

    /// Where the camera settles: the centre of the box around every point of
    /// the route, with each longitude first moved to within 180° of the user's
    /// so the arcs sweep across the visible face rather than round the back.
    ///
    /// For a single hop this is exactly the old midpoint of (user, server), so
    /// a single-hop session frames as it always has. Nil with no entry.
    static func focus(user: Coord, entry: Coord?, exit: Coord?) -> Coord? {
        guard let entry else { return nil }
        var points = [user, entry]
        if let exitPoint = exit { points.append(exitPoint) }
        let lons = points.map { unwrap($0.lon, near: user.lon) }
        let lats = points.map(\.lat)
        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLon = lons.min(), let maxLon = lons.max() else { return nil }
        return Coord(lat: (minLat + maxLat) / 2, lon: (minLon + maxLon) / 2)
    }

    /// How far along leg `index` of `count` is, for an overall arc progress
    /// in 0...1. The legs draw one after the other — the second starts where
    /// the first lands — so the route reads in the order traffic takes it.
    static func legProgress(_ progress: Double, leg index: Int, of count: Int) -> Double {
        guard count > 0, index >= 0, index < count else { return 0 }
        let scaled = progress * Double(count) - Double(index)
        return min(max(scaled, 0), 1)
    }

    /// `lon` moved by ±360 so it lies within 180° of `reference`.
    static func unwrap(_ lon: Double, near reference: Double) -> Double {
        let d = lon - reference
        if d > 180 { return lon - 360 }
        if d < -180 { return lon + 360 }
        return lon
    }
}
