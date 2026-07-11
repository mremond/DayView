import AppKit

private let quitCommand = "__DAYVIEW_QUIT__"

final class FocusStatusDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.button?.toolTip = "Temps de focus restant"
        statusItem = item

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            while let line = readLine() {
                DispatchQueue.main.async {
                    if line == quitCommand {
                        NSApplication.shared.terminate(nil)
                    } else {
                        self?.statusItem?.button?.title = line
                    }
                }
            }
            DispatchQueue.main.async {
                NSApplication.shared.terminate(nil)
            }
        }
    }
}

let application = NSApplication.shared
let delegate = FocusStatusDelegate()
application.delegate = delegate
application.setActivationPolicy(.accessory)
application.run()
