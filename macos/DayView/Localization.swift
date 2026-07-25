import Foundation

/// Keeps dynamic labels on the same localization path as SwiftUI's literal Text values.
func L(_ key: String) -> String {
    NSLocalizedString(key, comment: "")
}
func LF(_ key: String, _ arguments: CVarArg...) -> String {
    String(format: L(key), locale: Locale.current, arguments: arguments)
}
