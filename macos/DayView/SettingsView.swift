import SwiftUI
import DayViewKit

/// Standard macOS Settings window (app menu → Settings…, ⌘,). Every control binds
/// get-from-snapshot / set-through-bridge — no local state and no save button; the
/// controller's clamping round-trips back into the pickers via the snapshot.
struct SettingsView: View {
    @ObservedObject var model: TodayModel
    @State private var apps: [AppRef] = []
    @State private var selected: Set<String> = []

    var body: some View {
        Form {
            Section("Day") {
                DatePicker(
                    "Start",
                    selection: minutesBinding(get: { $0.startMinutes }, set: { model.setDayStart(minutes: $0) }),
                    displayedComponents: .hourAndMinute
                )
                DatePicker(
                    "End",
                    selection: minutesBinding(get: { $0.endMinutes }, set: { model.setDayEnd(minutes: $0) }),
                    displayedComponents: .hourAndMinute
                )
            }
            Section("Display") {
                Toggle(
                    "Show seconds",
                    isOn: Binding(
                        get: { model.snapshot.showSeconds },
                        set: { model.setShowSeconds($0) }
                    )
                )
                Picker(
                    "Appearance",
                    selection: Binding(
                        get: { model.snapshot.themeMode },
                        set: { model.setThemeMode($0) }
                    )
                ) {
                    Text("System").tag("SYSTEM")
                    Text("Light").tag("LIGHT")
                    Text("Dark").tag("DARK")
                }
            }
            Section("Net time") {
                Toggle(
                    "Net time calculation",
                    isOn: Binding(
                        get: { model.snapshot.netTimeEnabled },
                        set: { model.setNetTimeEnabled($0) }
                    )
                )
                Text("Subtracts busy slots from your calendar and greys them out on the circle.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if model.snapshot.netTimeEnabled {
                    if !model.snapshot.calendarPermission {
                        Button("Grant calendar access") { model.requestCalendarAccess() }
                        Text("macOS will ask you to allow DayView to read your calendars.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        if model.snapshot.calendarReadError {
                            Text("Calendar could not be read.")
                                .font(.caption)
                                .foregroundStyle(.orange)
                        }
                        ForEach(model.snapshot.calendars, id: \.id) { calendar in
                            Toggle(
                                calendar.displayName,
                                isOn: Binding(
                                    get: { calendar.included },
                                    set: { model.setCalendarIncluded(id: calendar.id, included: $0) }
                                )
                            )
                        }
                        Text("Only events marked busy (excluding all-day events) are subtracted.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            Section("On-goal apps") {
                Text("Apps that count as working toward your goal during a Focus.")
                    .font(.caption).foregroundStyle(.secondary)
                ForEach(apps, id: \.bundleId) { app in
                    Toggle(app.displayName, isOn: Binding(
                        get: { selected.contains(app.bundleId) },
                        set: { on in
                            if on {
                                selected.insert(app.bundleId)
                                model.addOnGoalApp(bundleId: app.bundleId, name: app.displayName)
                            } else {
                                selected.remove(app.bundleId)
                                model.removeOnGoalApp(bundleId: app.bundleId)
                            }
                        }
                    ))
                }
            }
            .onAppear {
                // Union of the stored (possibly not-running) apps and the currently running
                // ones, so a configured app that isn't running right now stays visible and
                // removable instead of disappearing from the list.
                var byBundleId: [String: AppRef] = [:]
                for app in model.onGoalApps() { byBundleId[app.bundleId] = app }
                for app in model.runningApps() { byBundleId[app.bundleId] = app }
                apps = byBundleId.values.sorted { $0.displayName < $1.displayName }
                selected = Set(model.onGoalBundleIds)
            }
        }
        .formStyle(.grouped)
        .frame(width: 360)
        .preferredColorScheme(preferredScheme(for: model.snapshot.themeMode))
    }

    // Binds a minutes-from-midnight preference to an hour-and-minute DatePicker. Uses a
    // fixed anchor day and round-trips through Calendar components, so DST on any real
    // day cannot corrupt the minutes.
    private func minutesBinding(
        get: @escaping (TodaySnapshot) -> Int64,
        set: @escaping (Int32) -> Void
    ) -> Binding<Date> {
        Binding(
            get: {
                let minutes = Int(get(model.snapshot))
                var components = DateComponents()
                components.year = 2001
                components.month = 1
                components.day = 1
                components.hour = minutes / 60
                components.minute = minutes % 60
                return Calendar.current.date(from: components) ?? Date()
            },
            set: { newValue in
                let components = Calendar.current.dateComponents([.hour, .minute], from: newValue)
                set(Int32((components.hour ?? 0) * 60 + (components.minute ?? 0)))
            }
        )
    }
}
