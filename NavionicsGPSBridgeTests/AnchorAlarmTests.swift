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

    func testSystemDisplayUnitsFollowLocaleMeasurementSystem() {
        XCTAssertEqual(
            DisplayUnitPreference.system.resolvedUnit(locale: Locale(identifier: "en_US")),
            .feet
        )
        XCTAssertEqual(
            DisplayUnitPreference.system.resolvedUnit(locale: Locale(identifier: "en_GB")),
            .metres
        )
        XCTAssertEqual(
            DisplayUnitPreference.metres.resolvedUnit(locale: Locale(identifier: "en_US")),
            .metres
        )
        XCTAssertEqual(
            DisplayUnitPreference.feet.resolvedUnit(locale: Locale(identifier: "fr_FR")),
            .feet
        )
    }

    func testFeetFormattingRoundsAnchorBoundariesAndModeSteps() {
        let formatter = DistanceDisplayFormatter(
            preference: .feet,
            locale: Locale(identifier: "en_US")
        )

        XCTAssertEqual(formatter.format(metres: 3, fractionDigits: 1), "9.8 ft")
        XCTAssertEqual(formatter.format(metres: 50), "164 ft")
        XCTAssertEqual(
            formatter.range(
                minimumMetres: AnchorAlarmMode.shorelined.minimumRadiusMeters,
                maximumMetres: AnchorAlarmMode.shorelined.maximumRadiusMeters,
                stepMetres: AnchorAlarmMode.shorelined.radiusStepMeters
            ),
            "10–164 ft range • 3 ft steps"
        )
        XCTAssertEqual(
            formatter.range(
                minimumMetres: AnchorAlarmMode.freeSwinging.minimumRadiusMeters,
                maximumMetres: AnchorAlarmMode.freeSwinging.maximumRadiusMeters,
                stepMetres: AnchorAlarmMode.freeSwinging.radiusStepMeters
            ),
            "16–656 ft range • 16 ft steps"
        )
    }

    func testGoodGPSPrecisionDoesNotWarn() {
        XCTAssertNil(AnchorGPSQuality.degradedWarning(
            accuracyMeters: 1,
            radiusMeters: 3
        ))
        XCTAssertNil(AnchorGPSQuality.degradedWarning(
            accuracyMeters: 7,
            radiusMeters: 15
        ))
    }

    func testDegradedGPSPrecisionExplainsEffectiveConfirmationDistance() {
        let warning = AnchorGPSQuality.degradedWarning(
            accuracyMeters: 7,
            radiusMeters: 3
        )

        XCTAssertNotNil(warning)
        XCTAssertTrue(warning?.contains("±7 m") == true)
        XCTAssertTrue(warning?.contains("3 m alarm limit") == true)
        XCTAssertTrue(warning?.contains("about 10 m") == true)
    }

    func testInvalidGPSPrecisionDoesNotWarn() {
        XCTAssertNil(AnchorGPSQuality.degradedWarning(
            accuracyMeters: nil,
            radiusMeters: 3
        ))
        XCTAssertNil(AnchorGPSQuality.degradedWarning(
            accuracyMeters: .infinity,
            radiusMeters: 3
        ))
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
