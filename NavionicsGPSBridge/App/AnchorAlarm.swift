import Foundation

enum AnchorAlarmMode: String, CaseIterable, Identifiable, Sendable {
    case shorelined
    case freeSwinging

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .shorelined: "Shorelined"
        case .freeSwinging: "Free swinging"
        }
    }

    var defaultRadiusMeters: Double {
        switch self {
        case .shorelined: 15
        case .freeSwinging: 50
        }
    }

    var minimumRadiusMeters: Double {
        switch self {
        case .shorelined: 3
        case .freeSwinging: 5
        }
    }

    var maximumRadiusMeters: Double {
        switch self {
        case .shorelined: 50
        case .freeSwinging: 200
        }
    }

    var radiusStepMeters: Double {
        switch self {
        case .shorelined: 1
        case .freeSwinging: 5
        }
    }

    func adjustedRadius(_ radiusMeters: Double) -> Double {
        let clamped = min(maximumRadiusMeters, max(minimumRadiusMeters, radiusMeters))
        return (clamped / radiusStepMeters).rounded() * radiusStepMeters
    }

    var setupDescription: String {
        switch self {
        case .shorelined:
            "For a bow anchor with the stern tied ashore. Arming records the boat's current tied position and watches a tight circle around it."
        case .freeSwinging:
            "For a boat free to swing. Tap Drop anchor at the anchor-drop point, then arm after paying out the rode."
        }
    }
}

struct AnchorAlarmAssessment: Equatable, Sendable {
    let distanceMeters: Double
    let minimumLikelyDistanceMeters: Double
    let isDefinitelyOutside: Bool
}

struct AnchorPlotOffset: Equatable, Sendable {
    let eastMeters: Double
    let northMeters: Double
}

struct AnchorTrackPoint: Identifiable, Equatable, Sendable {
    let id: Int
    let eastMeters: Double
    let northMeters: Double
    let accuracyMeters: Double
}

enum AnchorAlarmGeometry {
    private static let earthRadiusMeters = 6_371_008.8

    static func assess(
        referenceLatitude: Double,
        referenceLongitude: Double,
        currentLatitude: Double,
        currentLongitude: Double,
        currentAccuracyMeters: Double,
        radiusMeters: Double
    ) -> AnchorAlarmAssessment {
        let distance = distanceMeters(
            firstLatitude: referenceLatitude,
            firstLongitude: referenceLongitude,
            secondLatitude: currentLatitude,
            secondLongitude: currentLongitude
        )
        let accuracy = currentAccuracyMeters.isFinite ? max(0, currentAccuracyMeters) : 0
        let minimumLikelyDistance = max(0, distance - accuracy)
        return AnchorAlarmAssessment(
            distanceMeters: distance,
            minimumLikelyDistanceMeters: minimumLikelyDistance,
            isDefinitelyOutside: minimumLikelyDistance > max(1, radiusMeters)
        )
    }

    static func distanceMeters(
        firstLatitude: Double,
        firstLongitude: Double,
        secondLatitude: Double,
        secondLongitude: Double
    ) -> Double {
        let firstLatitudeRadians = firstLatitude * .pi / 180
        let secondLatitudeRadians = secondLatitude * .pi / 180
        let latitudeDelta = secondLatitudeRadians - firstLatitudeRadians
        let longitudeDelta = (secondLongitude - firstLongitude) * .pi / 180
        let sinLatitude = sin(latitudeDelta / 2)
        let sinLongitude = sin(longitudeDelta / 2)
        let haversine = sinLatitude * sinLatitude
            + cos(firstLatitudeRadians) * cos(secondLatitudeRadians)
            * sinLongitude * sinLongitude
        let angularDistance = 2 * atan2(
            sqrt(haversine),
            sqrt(max(0, 1 - haversine))
        )
        return earthRadiusMeters * angularDistance
    }

    static func plotOffsetMeters(
        referenceLatitude: Double,
        referenceLongitude: Double,
        pointLatitude: Double,
        pointLongitude: Double
    ) -> AnchorPlotOffset {
        let referenceLatitudeRadians = referenceLatitude * .pi / 180
        let pointLatitudeRadians = pointLatitude * .pi / 180
        let latitudeDelta = pointLatitudeRadians - referenceLatitudeRadians
        let longitudeDeltaDegrees = (pointLongitude - referenceLongitude + 540)
            .truncatingRemainder(dividingBy: 360) - 180
        let longitudeDelta = longitudeDeltaDegrees * .pi / 180
        let meanLatitude = (referenceLatitudeRadians + pointLatitudeRadians) / 2
        return AnchorPlotOffset(
            eastMeters: earthRadiusMeters * longitudeDelta * cos(meanLatitude),
            northMeters: earthRadiusMeters * latitudeDelta
        )
    }
}
