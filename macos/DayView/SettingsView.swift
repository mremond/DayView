import SwiftUI
import DayViewKit

/// Standard macOS Settings window (app menu → Settings…, ⌘,). Every control binds
/// get-from-snapshot / set-through-bridge — no local state and no save button; the
/// controller's clamping round-trips back into the pickers via the snapshot.
struct SettingsView: View {
    @ObservedObject var model: TodayModel

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
