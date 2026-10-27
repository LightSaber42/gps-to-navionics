import Foundation

enum DisplayUnitPreference: String, CaseIterable, Identifiable, Sendable, Hashable {
    case system
    case metres
    case feet

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .system: String(localized: "System Default")
        case .metres: String(localized: "Metres")
        case .feet: String(localized: "Feet")
        }
    }

    func resolvedUnit(locale: Locale = .current) -> DisplayDistanceUnit {
        switch self {
        case .system:
            locale.measurementSystem == .us ? .feet : .metres
        case .metres:
            .metres
        case .feet:
            .feet
        }
    }

    func detail(locale: Locale = .current) -> String {
        guard self == .system else { return displayName }
        let resolvedName = resolvedUnit(locale: locale).displayName
        return String(localized: "\(displayName) (\(resolvedName))")
    }
}

enum DisplayDistanceUnit: Sendable, Equatable {
    case metres
    case feet

    private static let feetPerMetre = 3.280_839_895_013_123

    var symbol: String {
        switch self {
        case .metres: "m"
        case .feet: "ft"
        }
    }

    var displayName: String {
        switch self {
        case .metres: String(localized: "Metres")
        case .feet: String(localized: "Feet")
        }
    }

    func value(metres: Double) -> Double {
        switch self {
        case .metres: metres
        case .feet: metres * Self.feetPerMetre
        }
    }
}

struct DistanceDisplayFormatter: Sendable {
    let unit: DisplayDistanceUnit
    let locale: Locale

    init(
        preference: DisplayUnitPreference,
        locale: Locale = .current
    ) {
        unit = preference.resolvedUnit(locale: locale)
        self.locale = locale
    }

    func format(metres: Double, fractionDigits: Int = 0) -> String {
        "\(number(metres: metres, fractionDigits: fractionDigits)) \(unit.symbol)"
    }

    func formatAccuracy(metres: Double) -> String {
        "±\(format(metres: metres))"
    }

    func number(metres: Double, fractionDigits: Int = 0) -> String {
        let digits = min(3, max(0, fractionDigits))
        return String(
            format: "%.\(digits)f",
            locale: locale,
            unit.value(metres: metres)
        )
    }

    func range(
        minimumMetres: Double,
        maximumMetres: Double,
        stepMetres: Double
    ) -> String {
        let minimum = number(metres: minimumMetres)
        let maximum = number(metres: maximumMetres)
        let step = format(metres: stepMetres)
        return String(localized: "\(minimum)–\(maximum) \(unit.symbol) range • \(step) steps")
    }
}
