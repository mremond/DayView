import SwiftUI

/// Maps the snapshot's themeMode string to SwiftUI. "SYSTEM" (or anything unknown) → nil,
/// which lets the window follow the OS appearance.
func preferredScheme(for themeMode: String) -> ColorScheme? {
    switch themeMode {
    case "LIGHT": return .light
    case "DARK": return .dark
    default: return nil
    }
}
