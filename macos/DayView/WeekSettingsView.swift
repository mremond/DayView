import SwiftUI

/// Placeholder for the weekly template editor (filled in a later task).
struct WeekSettingsView: View {
    @ObservedObject var plan: PlanModel

    var body: some View {
        Text("Week")
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
