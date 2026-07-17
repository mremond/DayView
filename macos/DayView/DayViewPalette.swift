import SwiftUI

/// The DayView palette, mirroring the shared DayViewTheme (dark + light) verbatim.
/// One source of truth for the native product windows; the effective ColorScheme
/// (already honoring the themeMode setting) selects the variant.
struct DayViewPalette {
    let ink: Color
    let panel: Color
    let cloud: Color
    let muted: Color
    let mint: Color
    let amber: Color
    let red: Color
    let glow: Color
    let overlay: Color
    let busy: [Color]

    static func current(for scheme: ColorScheme) -> DayViewPalette {
        scheme == .dark ? dark : light
    }

    static let dark = DayViewPalette(
        ink: hex(0x0B0D12), panel: hex(0x14171E), cloud: hex(0xF3F1EB), muted: hex(0x8B909B),
        mint: hex(0x78E6BD), amber: hex(0xFFB86B), red: hex(0xFF7272), glow: hex(0x171B22),
        overlay: .white,
        busy: [hex(0x6EC6FF), hex(0x6FD8C9), hex(0x8AA6FF), hex(0xB39DFF), hex(0x7FB4CC), hex(0x9FC0E8)]
    )

    static let light = DayViewPalette(
        ink: hex(0xF4F2EC), panel: hex(0xFFFFFF), cloud: hex(0x19201D), muted: hex(0x68716D),
        mint: hex(0x168866), amber: hex(0xB76218), red: hex(0xC74646), glow: hex(0xDCEAE3),
        overlay: hex(0x16211D),
        busy: [hex(0x2C6FA6), hex(0x2E8B84), hex(0x3F52A8), hex(0x6A4FA8), hex(0x34738A), hex(0x4E6E96)]
    )

    func busyColor(_ index: Int) -> Color {
        let safe = ((index % busy.count) + busy.count) % busy.count
        return busy[safe]
    }

    private static func hex(_ value: UInt32) -> Color {
        Color(
            red: Double((value >> 16) & 0xFF) / 255.0,
            green: Double((value >> 8) & 0xFF) / 255.0,
            blue: Double(value & 0xFF) / 255.0
        )
    }
}

/// The shared DayView panel look (rounded, tinted card) used by both product windows'
/// focus/goal sections. Kept here rather than duplicated in RingView/MiniView.
private struct DayViewPanel: ViewModifier {
    let palette: DayViewPalette
    func body(content: Content) -> some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.panel, in: RoundedRectangle(cornerRadius: 15))
            .overlay(RoundedRectangle(cornerRadius: 15).stroke(palette.overlay.opacity(0.06), lineWidth: 1))
    }
}

extension View {
    func dayViewPanel(_ palette: DayViewPalette) -> some View { modifier(DayViewPanel(palette: palette)) }
}
