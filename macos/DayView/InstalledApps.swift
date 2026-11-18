import Foundation
import DayPlanKit

/// Enumerates the installed applications a user may mark as on-goal for an area.
enum InstalledApps {
    private static let excluded: Set<String> = ["fr.dayview.app", "fr.dayview.app.debug"]

    static func scan() -> [OnGoalApp] {
        let fm = FileManager.default
        let home = fm.homeDirectoryForCurrentUser.appendingPathComponent("Applications").path
        let folders = ["/Applications", "/Applications/Utilities", "/System/Applications", home]
        var byID: [String: OnGoalApp] = [:]
        for folder in folders {
            let names = (try? fm.contentsOfDirectory(atPath: folder)) ?? []
            for name in names where name.hasSuffix(".app") {
                guard let app = app(at: URL(fileURLWithPath: folder).appendingPathComponent(name)),
                      !excluded.contains(app.bundleID), byID[app.bundleID] == nil else { continue }
                byID[app.bundleID] = app
            }
        }
        return byID.values.sorted {
            $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
        }
    }

    private static func app(at url: URL) -> OnGoalApp? {
        guard let bundle = Bundle(url: url), let id = bundle.bundleIdentifier, !id.isEmpty else { return nil }
        let info = bundle.infoDictionary ?? [:]
        let name = (info["CFBundleDisplayName"] as? String)
            ?? (info["CFBundleName"] as? String)
            ?? url.deletingPathExtension().lastPathComponent
        return OnGoalApp(bundleID: id, displayName: name.isEmpty ? url.deletingPathExtension().lastPathComponent : name)
    }
}
