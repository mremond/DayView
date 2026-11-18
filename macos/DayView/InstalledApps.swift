import Foundation
import DayPlanKit

/// Enumerates the installed applications a user may mark as on-goal for an area.
enum InstalledApps {
    private static let excluded: Set<String> = ["fr.dayview.app", "fr.dayview.app.debug"]

    static func scan() -> [OnGoalApp] {
        let fm = FileManager.default
        let home = fm.homeDirectoryForCurrentUser.appendingPathComponent("Applications").path
        let folders = ["/Applications", "/Applications/Utilities", "/System/Applications",
                       "/System/Applications/Utilities", home]
        var byID: [String: OnGoalApp] = [:]
        for folder in folders {
            for url in appURLs(in: URL(fileURLWithPath: folder), depth: 1) {
                guard let app = app(at: url),
                      !excluded.contains(app.bundleID), byID[app.bundleID] == nil else { continue }
                byID[app.bundleID] = app
            }
        }
        return byID.values.sorted {
            $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
        }
    }

    /// `.app` bundles directly in `folder`, plus those in its non-bundle subfolders down to `depth`
    /// levels (vendor suite folders, Setapp).
    private static func appURLs(in folder: URL, depth: Int) -> [URL] {
        let entries = (try? FileManager.default.contentsOfDirectory(
            at: folder, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]
        )) ?? []
        var result: [URL] = []
        for url in entries {
            if url.pathExtension == "app" {
                result.append(url)
            } else if depth > 0, (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true {
                result += appURLs(in: url, depth: depth - 1)
            }
        }
        return result
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
