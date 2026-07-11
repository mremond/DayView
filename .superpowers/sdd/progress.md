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

=== REBASE #2 + RE-INTEGRATION (main advanced: calendar net time #13, global goal progress bar #14, installMac) ===
Main EXPANDED the preference surface: goalStartMillis + netTimeSettings(enabled, includedCalendarIds:Set<String>); saveGlobalGoal now 3-arg (+startMillis); +saveNetTimeSettings/loadNetTimeSettings; keys goal_start, net_time_enabled, net_time_calendars ("\n"-joined). Rebased T1-T4 onto main; T4 controller/App conflict resolved by taking main's version then re-applying async transform. Re-integration commit 8434bfd on top:
- DayPreferences.persist() bridge: saveGlobalGoal 3-arg + saveNetTimeSettings.
- DayPreferencesStore: added goal_start/net_time_enabled/net_time_calendars keys+mapping (net cal encoding matches main's split/join "\n" for migration consistency).
- DayViewController: async transform re-applied on main's feature-complete controller (13 persisting setters -> persistState(); closePomodoro atomic; counter guard; toSnapshot covers all 11 persisted fields incl goalStartMillis+netTimeSettings; updateNetTimeData stays transient/no-persist).
- App.kt: rememberCoroutineScope() injected.
- Test fakes (DayViewControllerTest InMemory + DayPreferencesBridgeTest Fake): new interface methods; main's goal-start tests routed through testController(scope).
VERIFIED GREEN: ktlintCheck + desktopTest + testDebugUnitTest = BUILD SUCCESSFUL, 258 tests, 0 failures. Branch tip green on latest origin/main.
Task 4: COMPLETE (post-rebase SHAs: T1=19151c4, T2=92530ee, T3=d3b2f81, T4=941c50b, integration=8434bfd). NOTE: intermediate T2/T3/T4 commits don't individually compile post-rebase (integration commit 8434bfd closes the gap); fine since PRs squash-merge. NOTE: DayPreferencesStoreTest still only round-trips default net-time/goal-start values — consider adding non-default net-time coverage in T3/T8.

=== PAUSED (user directive) at T4 complete ===
Pushed origin/claude/datastore-migration (tip 8434bfd, green on latest main, 258 tests).
DRAFT PR #16 opened: https://github.com/mremond/DayView/pull/16 (foundations T1-T4; T5-T8 remaining, listed in PR body).
RESUME PLAN: re-check origin/main + rebase first (main moves fast), re-verify green, then execute T5 (Android SharedPreferencesMigration) -> T6 (desktop java.util.prefs migration) -> T7 (rewire widget/tile/alarm/notification/MainActivity/desktop Main to snapshots/persist, runBlocking at Android system edges) -> T8 (flip primitives to snapshots/persist, delete old load/save/observe surface + bridges + adapters, switch controller off preferences.snapshot() to injected snapshot, full gate incl createDistributable/DMG). Briefs for T5/T6/T7/T8 per plan doc, extended for goalStart+netTime fields.

=== RESUMED after merge of #16. New branch claude/datastore-migration-consumers off origin/main (d1d0bd9, incl #17 goal-start init backfill). Foundations present. Remaining consolidated to: T5 Android migration, T6 desktop migration, T7 flip+wire+cleanup+gate (absorbs old T7+T8). ===
Task 5: complete (commit 6288e38; base d1d0bd9). androidDayPreferences(context): DayPreferencesStore via PreferenceDataStoreFactory.create(migrations=[SharedPreferencesMigration(ctx,"dayview_preferences")]){ filesDir/datastore/dayview.preferences_pb }. Added androidx-datastore-preferences (Android) + androidx.test:core:1.7.0 (test). Robolectric migration test green; 130 Android tests green; ktlint clean. NOT wired (additive). Reviewer sonnet: Spec OK, Approved, independently verified all 16 keys name+type match, migration symbol confirmed via javap, dep justified.
CARRY to flip task (T7): (1) DataStore throws on 2nd instance per file -> build ONE shared instance (Application-scoped cache). (2) SharedPreferencesMigration.cleanUp() CLEARS legacy dayview_preferences after first DataStore read -> cutover must be ONE-WAY (never run old AndroidDayPreferences + androidDayPreferences concurrently in same process). Same one-way caution for desktop java.util.prefs migration.
Task 6: complete (commit a37b910; base 6288e38). desktopDayPreferences(legacy,file): DesktopPreferences wrapping DayPreferencesStore + monochrome accessors over one DataStore; JavaPrefsMigration copies all java.util.prefs keys (incl desktop-only monochrome_menu_bar_icon) with correct types once (shouldMigrate = DataStore empty && legacy has keys; BackingStoreException-safe). File renamed DesktopDataStore.kt->DesktopPreferences.kt (drop ktlint filename suppress). Migration test isolated (temp node+file). Reviewer sonnet: Spec OK, Approved, 18 keys type-correct, 139 desktop tests green. NOTE: commit was blocked by 1Password SSH signing agent in subagent context; user unblocked, main agent committed a37b910 (signed).

=== T5+T6 pushed; DRAFT PR #19 opened: https://github.com/mremond/DayView/pull/19 (Android + desktop DataStore migrations, additive, green, not wired). ===
REMAINING = THE FLIP (was T7+T8). Consumer inventory captured:
- Android reads/writes via AndroidDayPreferences(context): MainActivity (loadPomodoroEndMillis, loadFocusIntention x2), DayViewFocusTileService (loadPomodoroEndMillis/loadFocusIntention/loadPomodoroMinutes, savePomodoro), FocusAlarm (loadFocusIntention), FocusNotification (savePomodoro, loadPomodoroMinutes, loadFocusIntention), DayViewWidget (loadStartMinutes/loadEndMinutes/loadGoalTitle/loadPomodoroEndMillis/loadFocusIntention).
- Desktop Main: DesktopDayPreferences(), snapshot(), loadMonochromeMenuBarIcon/saveMonochromeMenuBarIcon, saveFocusIntention, savePomodoro x2; App observe wiring.
FLIP DESIGN NOTES:
- Interface -> snapshots/persist only; DayPreferencesStore : DayPreferences; delete bridge; rewrite DefaultDayPreferences to MutableStateFlow-only.
- Android: single process-wide DataStore instance (holder object) — DataStore forbids 2 instances/file. Replace AndroidDayPreferences with DataStore-backed wrapper that refreshes widgets on persist (old notifyWidgets=true writes refreshed; false instances were read-only). Consumers: reads -> runBlocking{ store.snapshots.first() }.field; writes -> runBlocking{ persist(current.copy(...)) }.
- Controller: drop preferences.snapshot() (removed) -> inject initialSnapshot; App.kt: runBlocking{ snapshots.first() } for initial + LaunchedEffect collect snapshots -> onPreferencesChanged (replaces observe).
- Desktop Main: desktopDayPreferences() -> DesktopPreferences (store as DayPreferences + monochrome suspend accessors); wire monochrome.
- Delete AndroidDayPreferences.kt, DesktopDayPreferences.kt + their tests (AndroidDayPreferencesTest, DesktopDayPreferencesTest).
- Full gate: ktlint + all tests + assembleDebug + assembleRelease + createDistributable.
RISK: touches Android widget/tile/alarm/notification (no unit-test coverage) — verify via full build gate + run desktop app; Android widget runtime not verifiable here.
