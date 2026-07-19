# macOS Native (Path B) — Parity Checklist

Living document. Tracks what remains between the native SwiftUI app and the shipping
Compose/JVM macOS app, and is the **cutover criterion**: when every PORT item is ✅, the
release chain can switch the macOS artifact from the JVM `.dmg` to the native app.

**The strategy (agreed 2026-07-16):**
1. **Behavior/data: strict parity** — `:core` semantics, persisted fields, sync payloads
   never fork.
2. **Presentation: native idiom wins** — adopt the macOS-native expression of the same
   feature (Settings scene, sheets, menus), but don't invent product behavior mid-port.
3. **Redesigns/new ideas: parked** in the Parking Lot below until after cutover.

**Legend:** ✅ done · 🔜 next · **PORT** required before cutover · **DECIDE** port-or-drop
call needed · **DEFER** explicitly post-cutover · **DROP** not ported (decision recorded).

---

## Done (phases 1–7a, all merged to main)

| Item | Phase |
|---|---|
| Live countdown ring + headline, shared `DayRingCanvas` | 1–2, 5b |
| Reactive state via `DayViewSession`/`TodaySnapshot` bridge | 2 |
| Native persistence (DataStore, `~/Library/Application Support/DayView/`) | 3 |
| Goal title + deadline editing, working-hours label | 3 |
| Focus: intention, duration stepper, start/stop | 3 |
| Menu-bar residency: live title, dropdown, hide-on-close | 4 |
| Focus-closure ritual (Completed/Progressed/Resume later) + break relaunch | 5a |
| Mini always-on-top window, exclusive swaps, three entry points | 5b |
| Settings scene: day window, show-seconds, appearance | 6 |
| Kotlin-computed presentation labels (`secondsLabel`/`focusLine`/`menuBarTitle`) | 6 |
| Net time: K/N EventKit source (primed store, travel time), probe, settings section, `Net X h MM` readout | 7a |
| Busy arcs on the main ring + hover labels (5° margin), 12/24-h clock plumbing | 7b |
| Visual identity: palette (dark+light), layered dial, interior countdown, glow bg, panel cards | 8 |
| Detours: capture/tally/total/edit-list/forget (9a) + ring bodies on outer lane with hover (9b) | 9a–9b |
| Presence foundation: NSWorkspace frontmost feed, PresenceCoordinator, engaged arcs + Focus total, on-goal apps settings, sessionOffGoal → ledger | 10a |
| Attention layer: drift nudges (4-switch / 2-min rules), Dock badge + single bounce, in-app drift banner, resumption ritual with window restore | 10b |
| App icon shared with the JVM build | (main, 89e4c6b) |
| Presence persistence: intervals written on the desktop cadence, seeded at launch so a relaunch mid-session continues the run | 11 |

## Today screen

| Item | Status | Notes |
|---|---|---|
| Must-dos (up to 3 planned obligations, complete/free slot) | **PORT** | Small; `:core` logic exists |
| Focus-session detail pop-up (intention, engaged, deep-focus per session) | **PORT** | Records exist since 5a; engaged/deep-focus figures need presence |
| Day-over screen + next-3-days availability | **PORT** | `updateUpcomingData` exists in `:core`; session must feed it (7a deferred it) |
| Hero quotes (day-state message) | **PORT** | Confirmed 2026-07-16; pure copy layer over `:core` slots |
| Ring scrubbing/inspection interaction | **DROP** | Confirmed 2026-07-16: not ported on macOS |
| Auto display scale / font-scale preference | **DROP** | Confirmed 2026-07-16; macOS has system scaling |

## Focus (beyond what's done)

| Item | Status | Notes |
|---|---|---|
| Keyboard shortcuts: ⌘↩ start focus, ←/→ duration, Esc closes dialogs | **PORT** | Cheap; native `.keyboardShortcut` |
| `"OVERTIME"` pomodoroStatus value in the Swift status switch | **PORT** | `MiniView.focusCard` and `RingView.focusSection` (`// Task 10/11/12 gives this its real behaviour`) still fold `OVERTIME` into the `"BREAK"` case — same "Break" text and a "Relaunch" button appear while the session is still open, before it has ever been closed |
| Closure sheet with intention field + exit-toll detour capture in native | **PORT** | `FocusClosureButtons` calls `TodayModel.closeFocus(_ outcome:)`, which forwards to `DayViewSession.closeFocus(outcome:)` with no intention or detour text; `TodayModel.stopFocus()` likewise calls `closePomodoro(TO_RESUME)` with no detour category. Since `:core`'s `closePomodoro` silently no-ops an early exit without a named detour (`earlyExitRequiresDetour`), native's Stop button currently does nothing before the term is reached |
| 5-min preset button | **PORT** | `RingView.focusSection`/`MiniView.intentionSheet` still require a non-empty intention before Start (`.disabled(intention.isEmpty)`) and start only at the stepper-set duration; the shared Compose UI's free entry plus one-tap 5-minute preset (56d7896) isn't ported |
| Overtime `"+N min"` in MenuBarContent/MiniView | **PORT** | Partial gap: `focusLine`/`menuBarTitle` already read `"+N min"` for free via the Kotlin-computed snapshot (bf235ff), so `MenuBarContent`'s dropdown line and `RingView.focusSection`'s secondary line are already correct; `MiniView.focusCard`'s primary status row still hardcodes `"Break · …"` for OVERTIME (same gap as the status-switch item above) |
| Resume-ritual copy during overtime | **PORT** | `RingView.swift:246` renders `"\(pomodoroClock) left to stay on track."`, and `pomodoroClock` is `"+12 min"` in OVERTIME — newly reachable now that the resume ritual is no longer gated to the pre-term phase, so it reads "+12 min left to stay on track." The Compose panel got a dedicated `focus_resume_overtime` string in both locales; native needs the equivalent conditional |
| Break anchored on `breakStart` | **PORT** | The progress calculation itself is shared `:core` and `breakStart` already persists through the reused `DayPreferencesStore`, so timing is correct; the gap is the reminder/notification scheduling that keys off the closure-time `breakStart` anchor (desktop 8161833, Android d76d72b) — native has neither the drift/overtime notification plumbing (10b) nor the sound-cue scheduler (Sounds, below) yet |

**New (from the 10b review):** (a) the drift **notification banner** — `UNUserNotificationCenter` needs authorization and does not deliver reliably unsigned, so it waits for the packaging phase (the JVM uses the deprecated `NSUserNotification`); (b) in **menu-bar-only mode** (both windows closed) a resume ritual latches with nothing to surface it — it appears when a window is next opened, but the JVM forces the window visible. Both **PORT** before cutover.

**New (from the Task 13 review, 2026-07-18):** the asymmetric focus lifecycle (free entry, term-as-invitation, overtime counted, exit-toll detours, closure-anchored break) landed in `:core`/Compose across tasks 1–12; the native SwiftUI app intentionally kept minimal stop-gap `OVERTIME` handling (mirroring `BREAK`) so it stays on the old three-outcome closure model without a working early-exit path. The five rows above are that gap, itemized for the native port; none of it was built as part of this review, per the deferred-native-work agreement at the top of this document.

## Sounds

| Item | Status | Notes |
|---|---|---|
| Synthesized cues: day start bowl, interval chime, end gong; focus session cues; break reminder | **PORT** | Needs a native `SoundCuePlayer` (AVAudioEngine); scheduler logic may need a `:shared`→`:core` move |
| Sound settings screen (per-cue toggles, interval, volume, preview) | **PORT** | Joins the Settings scene |

## History

| Item | Status | Notes |
|---|---|---|
| Day-rollover archiving wired natively | **PORT** | ⚠️ Verify first: `DayViewNative.create()` passes no history store — native rollover may not archive today. Behavior-layer item. When this lands, presence seeding must switch to the JVM's shape at the same time: seed the stored intervals raw and let the accumulators reset themselves on the day-key change, otherwise the archived record for the previous day carries no presence intervals |
| Week screen (mini rings) + day screen (date label, back nav) | **PORT** | After the visual pass (reuses ring rendering) |

## Sync

| Item | Status | Notes |
|---|---|---|
| E2EE sync (server transport, crypto, recovery phrase, first-sync choice, auto-retry) | **PORT** | Required for cutover (Mac↔Android is in active use). The biggest open question: sync code lives in `:shared` — needs a `:shared`→`:core` migration or a native client. Spec carefully |
| Sync settings screen | **PORT** | With the sync phase |

## Settings & system

| Item | Status | Notes |
|---|---|---|
| Launch at login | **PORT** | `SMAppService` — much simpler natively than the JVM path |
| Monochrome menu-bar icon toggle | **DROP** | Confirmed 2026-07-16; the native menu bar shows live text, no icon |
| 12/24-hour clock handling | ✅ (7b) | Detected at launch via the NSDateFormatter "j"-probe, threaded through the snapshot; hover labels honor it — future wall-clock surfaces inherit the plumbing |
| French localization of the native UI | **PORT** | JVM ships FR; native is hardcoded EN. Required for parity — one cross-cutting phase near the end (also revisits the Kotlin-computed labels) |

## Packaging / release (the cutover itself)

| Item | Status | Notes |
|---|---|---|
| Developer ID signing + hardened runtime + `com.apple.security.personal-information.calendars` entitlement | **PORT** | Recorded in the 7a spec; same root cause as the JVM app |
| Notarization + DMG for the native app | **PORT** | |
| Release CI: `v*` workflow builds/attaches the native DMG instead of `:shared:packageDmg` | **PORT** | The final gate; Linux keeps the Compose chain |
| Stable bundle identity across reinstalls (TCC survival) | **PORT** | `fr.dayview.app` for release (debug stays `.debug`) |

## Explicitly post-cutover (DEFER)

- macOS Widget (shares `:core`) — new feature, not parity.
- Accessory / Dock-hidden mode.
- Calendar change-push observation (`observeChanges`) — minute cadence is parity.
- JVM adoption of the shared `probeNetTime` helper.

## Parking Lot (improvement ideas noted during porting — do not do mid-port)

- Debounce the Settings time pickers if mid-typing clamping feels fighty (Phase 6 note).
- Extract a shared seconds/net-line view if a third surface appears (Phase 6/7a).
- Ring hover tooltip can clip at the canvas edges near the top/right (busy + detour lanes). Cosmetic. (Glow+core stroke and the hit-test statics were resolved in phases 8/9b.)
- `FocusClosureButtons` for a third surface (widget) when it comes.
- Menu-bar icon *option* (text is the identity today; some users may prefer an icon).
- Probe logging in `probeNetTime` (needs a `:core` logging seam).
- Any workflow redesigns ("this feature should work differently") — collect here with a
  one-line rationale, revisit after cutover.

## Suggested sequencing (adjust freely)

1. ~~**7b** busy arcs + hover~~ ✅ merged 2a1520d
2. ~~**Visual identity pass**~~ ✅ merged a4fb363
3. ~~**Detours**~~ ✅ merged 6d5025b (9a) + bc36539 (9b)
4. ~~**Presence & on-goal (10a + 10b)**~~ ✅ merged 066c385 + b2f4846
5. **Resume ritual** + keyboard shortcuts (small)
6. **Must-dos** + hero quotes (small)
7. **Sounds**
8. **History** (verify native archiving first — possible existing gap)
9. **Day-over / upcoming availability**
10. **Sync** (largest; spec the `:shared`→`:core` question early)
11. **i18n (French)**
12. **Packaging/CI cutover** — flip this checklist to the release gate
