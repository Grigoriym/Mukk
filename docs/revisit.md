# Revisit

Real problems noticed outside the task at hand — not fixed inline, not dropped.

## Active playlist tab reads as the inactive one

Reported 2026-09-15 by the user while manually verifying
`docs/issues/2026-09-15-playlist-switch-lag.md` Part 1, with two playlist tabs visible: the active
tab was harder to pick out than the inactive one — visually backwards from what "selected" should
look like.

`PlaylistTab` (`composeApp/src/jvmMain/kotlin/com/grappim/mukk/ui/PlaylistTabBar.kt:160-165`) gives
the active tab `background = MaterialTheme.colorScheme.surface` and the inactive tab
`MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)`, sitting on a row background that's
also `surfaceVariant` (`:78`, alpha 0.3f). In the app's dark palette
(`MukkTheme.kt:16,18`: `surface = 0xFF16213E`, `surfaceVariant = 0xFF0F3460`), `surfaceVariant` is
the visually *lighter* of the two (perceived luminance ~47 vs. ~33 on a 0-255 scale) — so the
inactive tabs, blended over the equally-`surfaceVariant` row, come out brighter/more prominent than
the active one, which only gets the darker `surface` plus a colored (`primary`) text label to stand
out. The text-color difference is there but reads as too subtle against the background swap working
against it.

Not fixed inline — a color/contrast tweak to `PlaylistTab`'s active/inactive styling, out of scope
for the perf fix in flight. Worth a decision: give the active tab a lighter/raised background than
its neighbors (the conventional direction), e.g. swap which role uses `surface` vs.
`surfaceVariant`, or add a visible accent underline/border instead of relying on background alone.
