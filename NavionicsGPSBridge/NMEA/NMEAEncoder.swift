import Foundation

enum NMEAEncoder {
    private static let posixLocale = Locale(identifier: "en_US_POSIX")

    static func sentences(for fix: NMEAFix) -> [String] {
        let valid = fix.isValid
            && fix.latitude >= -90 && fix.latitude <= 90
            && fix.longitude >= -180 && fix.longitude <= 180
            && fix.horizontalAccuracyMeters >= 0

        let time = utcTime(fix.timestamp)
        let date = utcDate(fix.timestamp)
        let latitude = coordinate(fix.latitude, isLatitude: true)
        let longitude = coordinate(fix.longitude, isLatitude: false)
        let status = valid ? "A" : "V"
        let mode = valid ? "A" : "N"
        let fixQuality = valid ? "1" : "0"
        let speedKnots = max(0, fix.speedMetersPerSecond) * 1.943_844_492_4
        let speedKilometersPerHour = max(0, fix.speedMetersPerSecond) * 3.6
        let course = normalizedCourse(fix.courseDegrees)
        let courseText = course.map { format("%.1f", $0) } ?? ""

        let gga = [
            "GPGGA", time,
            latitude.value, latitude.hemisphere,
            longitude.value, longitude.hemisphere,
            fixQuality, valid ? "08" : "00", valid ? "0.9" : "9.9",
            format("%.1f", fix.altitudeMeters), "M", "0.0", "M", "", ""
        ].joined(separator: ",")

        let rmc = [
            "GPRMC", time, status,
            latitude.value, latitude.hemisphere,
            longitude.value, longitude.hemisphere,
            format("%.2f", speedKnots), courseText, date,
            "", "", mode
        ].joined(separator: ",")

        let vtg = [
            "GPVTG", courseText, "T", "", "M",
            format("%.2f", speedKnots), "N",
            format("%.2f", speedKilometersPerHour), "K", mode
        ].joined(separator: ",")

        return [line(for: gga), line(for: rmc), line(for: vtg)]
    }

    static func line(for body: String) -> String {
        "$\(body)*\(checksum(for: body))\r\n"
    }

    static func checksum(for body: String) -> String {
        let checksum = body.utf8.reduce(UInt8(0)) { $0 ^ $1 }
        return format("%02X", checksum)
    }

    static func coordinate(_ value: Double, isLatitude: Bool) -> (value: String, hemisphere: String) {
        let maximum = isLatitude ? 90.0 : 180.0
        let clamped = min(max(value, -maximum), maximum)
        let absoluteValue = abs(clamped)
        var degrees = Int(absoluteValue)
        var minutes = (absoluteValue - Double(degrees)) * 60

        minutes = (minutes * 100_000).rounded() / 100_000
        if minutes >= 60 {
            degrees += 1
            minutes = 0
        }

        let degreeWidth = isLatitude ? 2 : 3
        let degreeText = format("%0*d", degreeWidth, degrees)
        let minuteText = format("%08.5f", minutes)
        let hemisphere: String
        if isLatitude {
            hemisphere = clamped < 0 ? "S" : "N"
        } else {
            hemisphere = clamped < 0 ? "W" : "E"
        }
        return (degreeText + minuteText, hemisphere)
    }

    private static func utcTime(_ date: Date) -> String {
        let components = utcCalendar.dateComponents([.hour, .minute, .second, .nanosecond], from: date)
        let hundredths = max(0, min(99, (components.nanosecond ?? 0) / 10_000_000))
        return format(
            "%02d%02d%02d.%02d",
            components.hour ?? 0,
            components.minute ?? 0,
            components.second ?? 0,
            hundredths
        )
    }

    private static func utcDate(_ date: Date) -> String {
        let components = utcCalendar.dateComponents([.day, .month, .year], from: date)
        return format(
            "%02d%02d%02d",
            components.day ?? 1,
            components.month ?? 1,
            (components.year ?? 0) % 100
        )
    }

    private static func normalizedCourse(_ course: Double) -> Double? {
        guard course.isFinite, course >= 0 else { return nil }
        let normalized = course.truncatingRemainder(dividingBy: 360)
        return normalized >= 0 ? normalized : normalized + 360
    }

    private static var utcCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    private static func format(_ format: String, _ arguments: CVarArg...) -> String {
        String(format: format, locale: posixLocale, arguments: arguments)
    }
}
