import XCTest
@testable import NavionicsGPSBridge

final class NMEAEncoderTests: XCTestCase {
    func testChecksumMatchesPublishedRMCExample() {
        let body = "GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W"
        XCTAssertEqual(NMEAEncoder.checksum(for: body), "6A")
    }

    func testCoordinateFormattingUsesNMEADegreesAndMinutes() {
        let latitude = NMEAEncoder.coordinate(37.9838, isLatitude: true)
        let longitude = NMEAEncoder.coordinate(23.7275, isLatitude: false)
        let southernLatitude = NMEAEncoder.coordinate(-33.8688, isLatitude: true)

        XCTAssertEqual(latitude.value, "3759.02800")
        XCTAssertEqual(latitude.hemisphere, "N")
        XCTAssertEqual(longitude.value, "02343.65000")
        XCTAssertEqual(longitude.hemisphere, "E")
        XCTAssertEqual(southernLatitude.value, "3352.12800")
        XCTAssertEqual(southernLatitude.hemisphere, "S")
    }

    func testValidFixProducesThreeChecksummedCRLFTerminatedSentences() throws {
        let timestamp = try XCTUnwrap(
            ISO8601DateFormatter().date(from: "2026-08-03T12:34:56Z")
        )
        let fix = NMEAFix(
            timestamp: timestamp,
            latitude: 37.9838,
            longitude: 23.7275,
            altitudeMeters: 42,
            speedMetersPerSecond: 5.144_444,
            courseDegrees: 123.4,
            horizontalAccuracyMeters: 4,
            isValid: true
        )

        let sentences = NMEAEncoder.sentences(for: fix)

        XCTAssertEqual(sentences.count, 3)
        XCTAssertTrue(sentences[0].hasPrefix("$GPGGA,123456.00,3759.02800,N,02343.65000,E,1,08,0.9,42.0,M,0.0,M,,*"))
        XCTAssertTrue(sentences[1].hasPrefix("$GPRMC,123456.00,A,3759.02800,N,02343.65000,E,10.00,123.4,030826,,,A*"))
        XCTAssertTrue(sentences[2].hasPrefix("$GPVTG,123.4,T,,M,10.00,N,18.52,K,A*"))
        XCTAssertTrue(sentences.allSatisfy { $0.hasSuffix("\r\n") })
        XCTAssertTrue(sentences.allSatisfy(hasValidChecksum))
    }

    func testInvalidFixMarksGGAAndRMCInvalid() {
        let fix = NMEAFix(
            timestamp: Date(timeIntervalSince1970: 0),
            latitude: 0,
            longitude: 0,
            altitudeMeters: 0,
            speedMetersPerSecond: -1,
            courseDegrees: -1,
            horizontalAccuracyMeters: 20,
            isValid: false
        )

        let sentences = NMEAEncoder.sentences(for: fix)

        XCTAssertTrue(sentences[0].contains(",0,00,9.9,"))
        XCTAssertTrue(sentences[1].contains("$GPRMC,000000.00,V,"))
        XCTAssertTrue(sentences[1].contains(",010170,,,N*"))
        XCTAssertTrue(sentences[2].contains("$GPVTG,,T,,M,0.00,N,0.00,K,N*"))
    }

    private func hasValidChecksum(_ sentence: String) -> Bool {
        let trimmed = sentence.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.first == "$", let star = trimmed.lastIndex(of: "*") else { return false }
        let body = String(trimmed[trimmed.index(after: trimmed.startIndex)..<star])
        let supplied = String(trimmed[trimmed.index(after: star)...])
        return supplied == NMEAEncoder.checksum(for: body)
    }
}
