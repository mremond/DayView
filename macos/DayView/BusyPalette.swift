import SwiftUI

/// Per-calendar busy colors, cribbed verbatim from the JVM DayViewTheme (colors.busy).
/// Temporary home: the visual-identity pass will fold these into the full native theme.
enum BusyPalette {
    private static let dark: [Color] = [
        rgb(0x6EC6FF), // sky
        rgb(0x6FD8C9), // teal
        rgb(0x8AA6FF), // periwinkle
        rgb(0xB39DFF), // violet
        rgb(0x7FB4CC), // slate cyan
        rgb(0x9FC0E8), // steel
    ]
    private static let light: [Color] = [
        rgb(0x2C6FA6), // sky
        rgb(0x2E8B84), // teal
        rgb(0x3F52A8), // periwinkle
        rgb(0x6A4FA8), // violet
        rgb(0x34738A), // slate cyan
        rgb(0x4E6E96), // steel
    ]

    static func color(for index: Int, scheme: ColorScheme) -> Color {
        let palette = scheme == .dark ? dark : light
        let safe = ((index % palette.count) + palette.count) % palette.count
        return palette[safe]
    }

    private static func rgb(_ hex: UInt32) -> Color {
        Color(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0
        )
    }
}
