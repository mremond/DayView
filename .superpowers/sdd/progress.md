# Progress ledger — calendrier temps net

Plan: docs/superpowers/plans/2026-07-11-calendrier-temps-net.md
Base commit: 6a14868

## Tasks
- Task 1: complete (feat(net): modèle et fusion des plages occupées — CalendarNetTimeTest GREEN; inline execution, review at end)
- Task 2: complete (feat(net): calcul du temps net — 4 tests GREEN; fixed plan test bug: busyRemaining expected 1h30 not 2h30)
- Task 3: complete (feat(net): projection en arcs — 6 tests GREEN)
- Task 4: complete (feat(net): réglages + persistance — DayPreferencesTest + DesktopDayPreferencesTest GREEN)
- Task 5: complete (feat(net): contrat CalendarSource + Noop + expect/actual — desktopTest GREEN, Android compile OK)
- Task 6: complete (feat(net): lecture Android via Calendar Provider — assembleDebug OK; manual device verification deferred)
- Task 7: complete (feat(net): helper EventKit macOS — desktopTest GREEN, swiftc EventKit binary built arm64; runtime EventKit grant verification deferred)
- Task 8: complete (feat(net): arcs gris sur le cercle — desktop compile OK; visual verification deferred to final /run)
- Task 9: complete (feat(net): overlay au survol — desktop+Android compile OK; added tested helpers formatClockHm/angleToMillis; hover visual verification deferred)
- Task 10: complete (feat(net): panneau réglages + ligne temps net — desktopTest full GREEN, assembleDebug OK; added tested helpers nextIncludedCalendars/formatDurationHm; Android calendar permission launcher wired via onRequestCalendarPermission hook)

## Final status
- All 10 tasks complete + README section. 84 desktop tests pass (0 failures), assembleDebug OK, EventKit helper builds arm64, desktop app launches clean.
- Deferred (needs real device/calendar + GUI): visual arc/hover/permission checks on Android device and macOS with a real calendar.

## Minor findings (for final review)
