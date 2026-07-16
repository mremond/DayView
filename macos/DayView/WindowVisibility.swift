import SwiftUI

/// Tracks which of the two windows is open. SwiftUI cannot query window visibility, and
/// the menu-bar items change with it; each window's root content reports itself via
/// onAppear/onDisappear (set in DayViewApp's scenes).
final class WindowVisibility: ObservableObject {
    @Published var isMainOpen = false
    @Published var isMiniOpen = false
}
