import XCTest
@testable import NavionicsGPSBridge

final class AnchorAlarmTests: XCTestCase {
    func testDistanceAtSamePositionIsZero() {
        XCTAssertEqual(
            AnchorAlarmGeometry.distanceMeters(
                firstLatitude: 37,
                firstLongitude: 23,
                secondLatitude: 37,
                secondLongitude: 23
            ),
            0,
            accuracy: 0.01
        )
    }

    func testDistanceCrossesLongitudeWraparound() {
        XCTAssertEqual(
            AnchorAlarmGeometry.distanceMeters(
                firstLatitude: 0,
                firstLongitude: 179.9999,
                secondLatitude: 0,
                secondLongitude: -179.9999
            ),
            22.2,
            accuracy: 0.2
        )
    }

    func testAccuracyProtectsMarginalBoundaryFix() {
        let assessment = AnchorAlarmGeometry.assess(
            referenceLatitude: 0,
            referenceLongitude: 0,
            currentLatitude: 0.001,
            currentLongitude: 0,
            currentAccuracyMeters: 15,
            radiusMeters: 100
        )

        XCTAssertFalse(assessment.isDefinitelyOutside)
    }

    func testNearestLikelyPointOutsideRadiusBreaches() {
        let assessment = AnchorAlarmGeometry.assess(
            referenceLatitude: 0,
            referenceLongitude: 0,
            currentLatitude: 0.001,
            currentLongitude: 0,
            currentAccuracyMeters: 5,
            radiusMeters: 100
        )

        XCTAssertTrue(assessment.isDefinitelyOutside)
    }

    func testShorelinedRadiusUsesFineYachtScaleSteps() {
        XCTAssertEqual(AnchorAlarmMode.shorelined.minimumRadiusMeters, 3)
        XCTAssertEqual(AnchorAlarmMode.shorelined.maximumRadiusMeters, 50)
        XCTAssertEqual(AnchorAlarmMode.shorelined.radiusStepMeters, 1)
        XCTAssertEqual(AnchorAlarmMode.shorelined.adjustedRadius(7.4), 7)
    }

    func testFreeSwingingRadiusIsCappedAtTwoHundredMetres() {
        XCTAssertEqual(AnchorAlarmMode.freeSwinging.maximumRadiusMeters, 200)
        XCTAssertEqual(AnchorAlarmMode.freeSwinging.adjustedRadius(500), 200)
    }

    func testPlotOffsetUsesNorthAndEastMetres() {
        let north = AnchorAlarmGeometry.plotOffsetMeters(
            referenceLatitude: 0,
            referenceLongitude: 0,
            pointLatitude: 0.001,
            pointLongitude: 0
        )
        XCTAssertEqual(north.eastMeters, 0, accuracy: 0.01)
        XCTAssertEqual(north.northMeters, 111.2, accuracy: 0.2)

        let east = AnchorAlarmGeometry.plotOffsetMeters(
            referenceLatitude: 0,
            referenceLongitude: 0,
            pointLatitude: 0,
            pointLongitude: 0.001
        )
        XCTAssertEqual(east.eastMeters, 111.2, accuracy: 0.2)
        XCTAssertEqual(east.northMeters, 0, accuracy: 0.01)
    }

    func testPlotOffsetCrossesLongitudeWraparound() {
        let offset = AnchorAlarmGeometry.plotOffsetMeters(
            referenceLatitude: 0,
            referenceLongitude: 179.9999,
            pointLatitude: 0,
            pointLongitude: -179.9999
        )
        XCTAssertEqual(offset.eastMeters, 22.2, accuracy: 0.2)
    }
}
