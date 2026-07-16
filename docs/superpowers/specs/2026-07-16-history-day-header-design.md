# History day header: date title and History back label

## Problem

`HistoryDayScreen` shows the static title "HISTORY" and reuses `settings_back`
("‹ TODAY") for its back control, although `onBack` returns to the History week
screen. The date of the day being viewed is never displayed.

## Design

1. **Back label** — new string `history_back` = "‹  HISTORY" / "‹  HISTORIQUE"
   (en/fr), used only by `HistoryDayScreen`. `HistoryWeekScreen` keeps
   `settings_back` since its back control really returns to Today.
2. **Date as title** — the top-right title of `HistoryDayScreen` becomes the
   viewed day's date: weekday label + localized date, e.g. "WED. · 2026-07-15"
   (en) / "MER. · 15/07/2026" (fr), composed via a new `history_day_title`
   format string ("%1$s · %2$s"). Reuses the existing `weekdayLabel()` and
   `historyDate()` helpers (the latter becomes shared instead of private to
   `HistoryWeekScreen.kt`). `HistoryDayScreen` gains a `dayKey: Long`
   parameter; the App.kt call site already has the selected key.

This fits `ScreenTopBar` semantics: the back label names where it returns to,
the title names what you are looking at.

## Testing

Desktop Compose UI tests assert the new back label wiring and the visible date
through test tags / seeded day keys (never raw `stringResource` text, which is
unresolved under `runComposeUiTest` on CI).
