# Engaged time, deep focus and overtime for the Swift Focus — design

Date: 2026-11-18
Status: approved by the user

## Intent

When the Focus moved from the Kotlin core to Swift (day-plan stage 4), two things the user relied on were lost:
- **Engaged time** (the Focus window minus logged detours) and **deep focus** (time with on-goal apps in front), shown per session and as arcs on the ring.
- **Overtime**: in the Kotlin version, reaching a Focus's term did not end it. It stayed open and counted until the user closed it.

This change restores both on the Swift Focus, matching the Kotlin semantics.

## Kotlin semantics being restored

- Engaged = the session window `[start, effective end]` with declared detours carved out (`deriveEngagedIntervals`, FocusIntervals.kt).
- Deep focus = on-goal presence intervals clipped to the session (`PresenceAccumulator`, with a bridge of 60 s and a minimum stretch of 120 s).
- Overtime (`Pomodoro.kt`):
  - A session stays open past its term until a conscious closure; overtime counts.
  - One discreet closure suggestion when the overtime reaches the planned duration (`OvertimeReminderScheduler`, at most once, with the late-alert rule).
  - The break starts at closure.

## Decisions (approved)

1. **Overtime for manual Focus only.**
   - A manual Focus past its term shows "+12:34" and keeps counting until Stop.
   - The closing card appears on Stop. When the overtime equals the planned duration: one notification "Still on Writing? Close this focus when you're ready." plus a soft chime, once per Focus.
   - The break starts at closure.
   - **Planned Focus still ends at its block's end** (clock-fixed plan).
   - A block starting while a manual Focus is in overtime still takes over (plan wins); the overtime up to the takeover counts.
2. **Engaged time and deep focus per Focus.**
   - Engaged = the record's focused time (meeting pauses already excluded for planned) minus the overlap of logged detours with the Focus window. It **replaces "Focus" time everywhere** (This week).
   - Deep focus = on-goal presence during the Focus, built from the drift sampler's per-second classification, with the 60 s bridge and the 2 min minimum. It is only measured when the area has on-goal apps; otherwise there is no deep figure (not 0).
3. **Display.**
   - **Ring:** the dim band per Focus, as today, plus bright mint arcs for deep stretches, as the Kotlin ring had.
   - **Detail sheet:** clicking a Focus band opens one showing area, intention, time range, engaged, deep and outcome.
   - **This week:** each area line reads "Planned 4:00 · Focus 2:50 · Deep 1:40".
4. **Storage.** New SwiftData entities only (no change to existing models), schema V5: per-session metrics and deep-focus stretches, written when a Focus ends (not when it is closed), so unclosed Focus sessions have them too.

## Approaches considered

- **A. Port into Swift with new entities (chosen).** Pure rules in DayPlanKit, two new entities, detours read from the Kotlin snapshot (`TodaySnapshot.detours`, epoch millis).
- B. Drive the Kotlin presence accumulator and Pomodoro again. Rejected: it contradicts the move to Swift and re-couples the Focus to Kotlin.
- C. Add fields to `FocusRecordEntry`. Rejected for now: changing an existing model requires first freezing the versioned schemas (V1–V4 list the live classes).

## Left to the builder

The exact visual style of the overtime state and the detail sheet, within the existing palette.
