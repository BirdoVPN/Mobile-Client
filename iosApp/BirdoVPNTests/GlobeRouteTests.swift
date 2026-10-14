import XCTest

/// MR-1100 — pins `GlobeRoute`, what the Home globe draws for a session.
///
/// The globe took ONE focus id, so a Multi-Hop session drew a single leg to
/// the entry node and the exit country appeared nowhere on it. These cases pin
/// the two-leg route, its order, the camera framing and the draw sequence.
/// WorldGlobeView (a SwiftUI Canvas) cannot be compiled into this un-hosted
/// bundle; the coordinates come from the real `CountryCoords` table.
final class GlobeRouteTests: XCTestCase {

    private typealias Coord = GlobeRoute.Coord

    private func coord(_ country: String) -> Coord {
        guard let ll = CountryCoords.forCountry(country) else {
            XCTFail("CountryCoords has no \(country)")
            return Coord(lat: 0, lon: 0)
        }
        return Coord(lat: ll.lat, lon: ll.lon)
    }

    /// WorldGlobeView's fixed "you" anchor (London).
    private let london = Coord(lat: 51.51, lon: -0.13)

    func testASingleHopIsOneLegFromYouToTheServer() {
        let de = coord("DE")
        XCTAssertEqual(GlobeRoute.legs(user: london, entry: de, exit: nil),
                       [GlobeRoute.Leg(from: london, to: de)])
    }

    func testAMultiHopSessionDrawsBothLegsInTravelOrder() {
        let de = coord("DE"), nl = coord("NL")
        XCTAssertEqual(GlobeRoute.legs(user: london, entry: de, exit: nl),
                       [GlobeRoute.Leg(from: london, to: de),
                        GlobeRoute.Leg(from: de, to: nl)])
    }

    func testNoEntryDrawsNothingEvenWithAnExit() {
        XCTAssertEqual(GlobeRoute.legs(user: london, entry: nil, exit: coord("NL")), [])
        XCTAssertNil(GlobeRoute.focus(user: london, entry: nil, exit: coord("NL")))
    }

    func testAnExitInTheEntrysCountryAddsNoZeroLengthLeg() {
        let de = coord("DE")
        XCTAssertEqual(GlobeRoute.legs(user: london, entry: de, exit: de),
                       [GlobeRoute.Leg(from: london, to: de)])
    }

    func testSingleHopFocusIsTheOldMidpoint() {
        // The pre-MR-1100 formula: ((userLat + lat) / 2, (userLon + lon) / 2).
        let de = coord("DE")
        let focus = GlobeRoute.focus(user: london, entry: de, exit: nil)
        XCTAssertEqual(focus?.lat ?? .nan, (london.lat + de.lat) / 2, accuracy: 1e-9)
        XCTAssertEqual(focus?.lon ?? .nan, (london.lon + de.lon) / 2, accuracy: 1e-9)
    }

    func testMultiHopFocusFramesAllThreePoints() {
        let de = coord("DE"), us = coord("US")
        guard let focus = GlobeRoute.focus(user: london, entry: de, exit: us) else {
            return XCTFail("no focus for a two-hop route")
        }
        // Centre of the box around London, Berlin and the US POP.
        XCTAssertEqual(focus.lat, (us.lat + de.lat) / 2, accuracy: 1e-9)
        XCTAssertEqual(focus.lon, (us.lon + de.lon) / 2, accuracy: 1e-9)
        // Every point sits on the visible face (within 90° of the camera).
        for p in [london, de, us] {
            XCTAssertLessThan(abs(GlobeRoute.unwrap(p.lon, near: focus.lon) - focus.lon), 90,
                              "\(p) is round the back of the globe")
        }
    }

    func testTheExitIsUnwrappedAcrossTheAntimeridian() {
        // From New Zealand, via Japan, out of the US: the US longitude is
        // carried past 180 so the camera swings east over the Pacific instead
        // of back across Asia and Europe.
        let nz = coord("NZ"), jp = coord("JP"), us = coord("US")
        guard let focus = GlobeRoute.focus(user: nz, entry: jp, exit: us) else {
            return XCTFail("no focus for a two-hop route")
        }
        XCTAssertEqual(focus.lon, (jp.lon + (us.lon + 360)) / 2, accuracy: 1e-9)
    }

    func testTheLegsDrawOneAfterTheOther() {
        XCTAssertEqual(GlobeRoute.legProgress(0.25, leg: 0, of: 2), 0.5, accuracy: 1e-9)
        XCTAssertEqual(GlobeRoute.legProgress(0.25, leg: 1, of: 2), 0, accuracy: 1e-9)
        XCTAssertEqual(GlobeRoute.legProgress(0.75, leg: 0, of: 2), 1, accuracy: 1e-9)
        XCTAssertEqual(GlobeRoute.legProgress(0.75, leg: 1, of: 2), 0.5, accuracy: 1e-9)
        XCTAssertEqual(GlobeRoute.legProgress(1, leg: 1, of: 2), 1, accuracy: 1e-9)
    }

    func testASingleLegKeepsTheWholeProgress() {
        for p in [0.0, 0.3, 0.99, 1.0] {
            XCTAssertEqual(GlobeRoute.legProgress(p, leg: 0, of: 1), p, accuracy: 1e-9)
        }
        XCTAssertEqual(GlobeRoute.legProgress(0.5, leg: 1, of: 1), 0)
        XCTAssertEqual(GlobeRoute.legProgress(0.5, leg: 0, of: 0), 0)
    }
}
