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
| Detours declare: capture sheet (motif/recent chips/duration), tally, daily total, edit list, forget-category | 9a |
| App icon shared with the JVM build | (main, 89e4c6b) |

## Today screen

| Item | Status | Notes |
|---|---|---|
| Detours: ring bodies (outer lane) + hover motif/times | 🔜 **9b** | Declare/tally/total/list/forget done in 9a; goal halo done in 8; `detourBodiesState` ready. Off-window tag deferred |
| Must-dos (up to 3 planned obligations, complete/free slot) | **PORT** | Small; `:core` logic exists |
| Focus/engaged arcs + "Focus H h MM" total below countdown | **PORT** | Depends on the presence phase (data source) |
| Focus-session detail pop-up (intention, engaged, deep-focus per session) | **PORT** | Records exist since 5a; engaged/deep-focus figures need presence |
| Day-over screen + next-3-days availability | **PORT** | `updateUpcomingData` exists in `:core`; session must feed it (7a deferred it) |
| Hero quotes (day-state message) | **PORT** | Confirmed 2026-07-16; pure copy layer over `:core` slots |
| Ring scrubbing/inspection interaction | **DROP** | Confirmed 2026-07-16: not ported on macOS |
| Auto display scale / font-scale preference | **DROP** | Confirmed 2026-07-16; macOS has system scaling |

## Focus (beyond what's done)

| Item | Status | Notes |
|---|---|---|
| Presence tracking: frontmost-app watcher, on-goal classification, engaged/deep-focus accumulation | **PORT** | `:core` accumulators exist; native needs an `NSWorkspace` frontmost provider — simpler than the JVM's approach |
| Drift nudges: 4-switches rule, 2-min off-goal rule, grace/interval, notification | **PORT** | Same phase as presence |
| On-goal apps settings screen (running-apps picker) | **PORT** | Same phase as presence |
| Dock badge + single Dock bounce while a drift reminder is pending | **PORT** | Trivial once presence exists; JVM MacDockBadge + MacDockBouncer (main e1b122c) are the reference |
| Resume ritual (still-active session found on relaunch/wake) | **PORT** | Own small phase; brings the window to front |
| `sessionOffGoal` feeding the clean-session ledger | **PORT** | Falls out of presence; closes the 5a documented limitation |
| Keyboard shortcuts: ⌘↩ start focus, ←/→ duration, Esc closes dialogs | **PORT** | Cheap; native `.keyboardShortcut` |

## Sounds

| Item | Status | Notes |
|---|---|---|
| Synthesized cues: day start bowl, interval chime, end gong; focus session cues; break reminder | **PORT** | Needs a native `SoundCuePlayer` (AVAudioEngine); scheduler logic may need a `:shared`→`:core` move |
| Sound settings screen (per-cue toggles, interval, volume, preview) | **PORT** | Joins the Settings scene |

## History

| Item | Status | Notes |
|---|---|---|
| Day-rollover archiving wired natively | **PORT** | ⚠️ Verify first: `DayViewNative.create()` passes no history store — native rollover may not archive today. Behavior-layer item |
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
- Visual pass: JVM busy lane is a two-pass glow+core stroke (native is single-pass, reads heavier); tooltip can clip at canvas edges; hit-test hardcodes inset 40 / lineWidth 18 (promote to DayRingCanvas statics when theming).
- `FocusClosureButtons` for a third surface (widget) when it comes.
- Menu-bar icon *option* (text is the identity today; some users may prefer an icon).
- Probe logging in `probeNetTime` (needs a `:core` logging seam).
- Any workflow redesigns ("this feature should work differently") — collect here with a
  one-line rationale, revisit after cutover.

## Suggested sequencing (adjust freely)

1. ~~**7b** busy arcs + hover~~ ✅ merged 2a1520d
2. ~~**Visual identity pass**~~ ✅ merged a4fb363
3. **Detours** (visuals now exist to draw bodies/halo) ← next
4. **Presence & on-goal** (drift nudges, engaged arcs, dock badge, `sessionOffGoal`)
5. **Resume ritual** + keyboard shortcuts (small)
6. **Must-dos** + hero quotes (small)
7. **Sounds**
8. **History** (verify native archiving first — possible existing gap)
9. **Day-over / upcoming availability**
10. **Sync** (largest; spec the `:shared`→`:core` question early)
11. **i18n (French)**
12. **Packaging/CI cutover** — flip this checklist to the release gate
