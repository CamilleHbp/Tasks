import Foundation

enum Language: String, CaseIterable, Identifiable {
    case system, en, fr
    var id: String { rawValue }
}

/// Follows the system language by default, falls back to English, and honours the
/// persistent in-app override.
enum Localization {
    static var language: Language {
        get { Language(rawValue: UserDefaults.standard.string(forKey: "language") ?? "") ?? .system }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: "language"); bundle = load() }
    }

    static var resolved: String {
        switch language {
        case .en: return "en"
        case .fr: return "fr"
        case .system: return Locale.preferredLanguages.first?.hasPrefix("fr") == true ? "fr" : "en"
        }
    }

    private(set) static var bundle = load()

    private static func load() -> Bundle {
        Bundle.main.path(forResource: resolved, ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main
    }
}

func L(_ key: String, fallback: String? = nil) -> String {
    let value = Localization.bundle.localizedString(forKey: key, value: "\u{0}", table: nil)
    return value == "\u{0}" ? (fallback ?? key) : value
}

func LF(_ key: String, _ arguments: CVarArg...) -> String {
    String(format: L(key), locale: Locale(identifier: Localization.resolved), arguments: arguments)
}
