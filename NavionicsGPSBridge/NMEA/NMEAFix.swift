import Foundation

struct NMEAFix: Equatable, Sendable {
    let timestamp: Date
    let latitude: Double
    let longitude: Double
    let altitudeMeters: Double
    let speedMetersPerSecond: Double
    let courseDegrees: Double
    let horizontalAccuracyMeters: Double
    let isValid: Bool
}
