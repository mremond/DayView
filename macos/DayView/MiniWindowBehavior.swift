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
}

private final class MiniWindowBehaviorView: NSView {
    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        configureWindow()
    }

    func configureWindow() {
        guard let window else { return }
        var behavior = window.collectionBehavior
        behavior.remove(.moveToActiveSpace)
        behavior.insert(.canJoinAllSpaces)
        behavior.insert(.fullScreenAuxiliary)
        window.collectionBehavior = behavior
    }
}
