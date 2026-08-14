import Foundation

enum ReleaseLinks {
    private enum Key: String {
        case privacy = "AnchorWatchPrivacyURL"
        case support = "AnchorWatchSupportURL"
        case terms = "AnchorWatchTermsURL"
    }

    static var privacy: URL? { configuredURL(for: .privacy) }
    static var support: URL? { configuredURL(for: .support) }
    static var terms: URL? { configuredURL(for: .terms) }

    private static func configuredURL(for key: Key) -> URL? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: key.rawValue) as? String,
              let url = URL(string: value),
              url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased(),
              !host.hasSuffix(".invalid") else {
            return nil
        }
        return url
    }
}
