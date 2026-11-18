import SwiftUI
import DayPlanKit

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
    let detours: [Color]
    let areas: [Color]

    static func current(for scheme: ColorScheme) -> DayViewPalette {
        scheme == .dark ? dark : light
    }

    static let dark = DayViewPalette(
        ink: hex(0x0B0D12), panel: hex(0x14171E), cloud: hex(0xF3F1EB), muted: hex(0x8B909B),
        mint: hex(0x78E6BD), amber: hex(0xFFB86B), red: hex(0xFF7272), glow: hex(0x171B22),
        overlay: .white,
        busy: [hex(0x6EC6FF), hex(0x6FD8C9), hex(0x8AA6FF), hex(0xB39DFF), hex(0x7FB4CC), hex(0x9FC0E8)],
        detours: [hex(0xFFB86B), hex(0xE7CE6B), hex(0xF2856D), hex(0xE58FB6), hex(0xB48EE0), hex(0xD9B08C)],
        areas: [hex(0xFF8A80), hex(0xFFD166), hex(0x9BE564), hex(0x4DD6A0), hex(0x56C2E6), hex(0x7C9CFF), hex(0xC792EA), hex(0xFF8FC7)]
    )

    static let light = DayViewPalette(
        ink: hex(0xF4F2EC), panel: hex(0xFFFFFF), cloud: hex(0x19201D), muted: hex(0x68716D),
        mint: hex(0x168866), amber: hex(0xB76218), red: hex(0xC74646), glow: hex(0xDCEAE3),
        overlay: hex(0x16211D),
        busy: [hex(0x2C6FA6), hex(0x2E8B84), hex(0x3F52A8), hex(0x6A4FA8), hex(0x34738A), hex(0x4E6E96)],
        detours: [hex(0xB76218), hex(0x8F7A1C), hex(0xB0492F), hex(0xA34D74), hex(0x6E4AA3), hex(0x8A6844)],
        areas: [hex(0xC8453C), hex(0xA87A00), hex(0x5C8A12), hex(0x1E8A5C), hex(0x1A82A6), hex(0x3E5FD0), hex(0x8A4FC0), hex(0xC23B85)]
    )

    func busyColor(_ index: Int) -> Color {
        let safe = ((index % busy.count) + busy.count) % busy.count
        return busy[safe]
    }

    func areaColor(_ index: Int) -> Color {
        areas[AreaPalette.normalized(index)]
    }

    func detourColor(_ index: Int) -> Color {
        let safe = ((index % detours.count) + detours.count) % detours.count
        return detours[safe]
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
