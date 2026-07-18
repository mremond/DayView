import AppKit
import SwiftUI

/// Applies the AppKit behaviours that SwiftUI's Window scene does not expose.
/// A full-screen application owns its own Space, so floating level alone is not
/// enough to keep the mini visible beside it.
struct MiniWindowBehavior: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView {
        MiniWindowBehaviorView()
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        (nsView as? MiniWindowBehaviorView)?.configureWindow()
    }

    static func dismantleNSView(_ nsView: NSView, coordinator: ()) {
        NSApp.setActivationPolicy(.regular)
    }
}

private final class MiniWindowBehaviorView: NSView {
    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        configureWindow()
    }

    func configureWindow() {
        guard let window else { return }
        NSApp.setActivationPolicy(.accessory)
        var behavior = window.collectionBehavior
        behavior.remove(.moveToActiveSpace)
        behavior.remove(.fullScreenPrimary)
        behavior.remove(.fullScreenNone)
        behavior.remove(.primary)
        behavior.remove(.auxiliary)
        behavior.insert(.canJoinAllSpaces)
        behavior.insert(.fullScreenAuxiliary)
        behavior.insert(.canJoinAllApplications)
        window.collectionBehavior = behavior
        window.level = .screenSaver
        window.hidesOnDeactivate = false
    }
}
