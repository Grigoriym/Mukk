# Now-Playing metadata squeezed to nothing on window resize

**Status:** Done
**Updated:** 2026-09-15

## Report

Shrinking the main window's height causes the Now-Playing panel's album art / title /
artist / album area to become effectively invisible, while the lyrics area keeps its size.
Expected: album art + track metadata should stay visible no matter the window size; only
the lyrics area (or the whole panel) should be the thing that shrinks/scrolls.

No repro steps, version, or exact window size given beyond "resize the window" — treated
below as an ordinary height-only shrink via the OS window border, which is the only resize
path that changes the Now-Playing panel's available height (`panel.rightWidth`, the panel's
*width*, is a separate drag handle unaffected by window resize).

## Findings

- `NowPlayingPanel.kt:32-34` hardcodes:
  ```kotlin
  private val MIN_METADATA_HEIGHT = 80.dp
  private val MIN_LYRICS_HEIGHT = 60.dp
  private val DIVIDER_AND_SPACERS = 28.dp
  ```
- `NowPlayingPanel.kt:64-70` computes the split every recomposition from `totalHeight`
  (`maxHeight` of the panel's `BoxWithConstraints`, which is fixed by the parent `Row`'s
  `weight(1f)` slot — i.e. directly tracks window height):
  ```kotlin
  val clampedLyricsHeight = lyricsHeight.coerceIn(
      MIN_LYRICS_HEIGHT,
      (totalHeight - DIVIDER_AND_SPACERS - MIN_METADATA_HEIGHT).coerceAtLeast(MIN_LYRICS_HEIGHT)
  )
  val metadataHeight = (totalHeight - DIVIDER_AND_SPACERS - clampedLyricsHeight)
      .coerceAtLeast(MIN_METADATA_HEIGHT)
  ```
  `lyricsHeight` itself is a persisted, drag-set preference (`nowplaying.lyricsHeight`,
  default 200dp — `PreferencesManager.kt:144-146`), **not derived from window size**. The
  formula always gives `lyricsHeight` first claim on the available space and only fills the
  metadata section with whatever's left, floored at 80dp.
- The metadata section (`NowPlayingPanel.kt:74-161`) renders, top to bottom: a square album
  art image sized to the panel's *width* (`aspectRatio(1f)` + `fillMaxWidth()` —
  `panel.rightWidth` is 150–450dp, default 280dp, `PreferencesManager.kt` /
  `MainLayout.kt:25,57-58`), a 16dp spacer, then up to 5 text lines (title, artist, album,
  year, genre). At the default 280dp panel width that's roughly 280 (art) + 16 + ~90 (text)
  ≈ 390dp of actual content — nothing close to the 80dp floor.
- `MIN_METADATA_HEIGHT` is a flat constant, independent of `panel.rightWidth`. A wider panel
  (up to 450dp) makes the mismatch worse, since the art alone would need 450dp.
- The metadata `Box` (`NowPlayingPanel.kt:74-79`) does have its own `verticalScroll`, so
  nothing is destroyed — but there is no visible affordance that it's scrollable (no
  scrollbar, no cut-off text, no icon), so the practical effect the user sees matches the
  report: the area *looks* gone, not "scroll for more."
- `main.kt` (`Window(...)` call, ~line 72) sets no minimum window size, so a user can shrink
  the window height with no floor stopping them.
- Arithmetic check at a realistic shrink (`totalHeight` = 300dp, e.g. window height ~500dp
  after subtracting the playlist tab bar / transport bar / dividers): lyrics upper bound =
  `max(300-28-80, 60)` = 192dp → `clampedLyricsHeight` = 192dp (user's 200dp default clamped
  down slightly) → `metadataHeight` = `max(300-28-192, 80)` = 80dp. 80dp shows roughly the
  top slice of the album art and nothing else — confirms the report.
- Further shrink exposes a second, sharper bug: once `totalHeight` < `MIN_METADATA_HEIGHT +
  MIN_LYRICS_HEIGHT + DIVIDER_AND_SPACERS` = 168dp, both floors are hit simultaneously and
  their sum exceeds `totalHeight` — the `Column`'s actual content height exceeds the exact
  height constraint the parent `Row` gave it, and nothing in `NowPlayingPanel` clips this
  (no `clipToBounds()`), so content likely overflows past the panel's slot rather than
  scrolling cleanly. Not independently reproduced pixel-for-pixel, but derivable directly
  from the constraint math above.

## Root cause

`MIN_METADATA_HEIGHT` (`NowPlayingPanel.kt:32`) is a value picked without regard to what the
metadata section actually contains — a square album-art image whose real height equals the
panel's own width (150–450dp) plus ~90dp of text. The split formula
(`NowPlayingPanel.kt:64-70`) treats `lyricsHeight` (a user-set, persisted preference that has
no relationship to current window size) as the higher-priority claim on space and only gives
the metadata section the remainder, re-floored at the too-small 80dp constant. As soon as the
window gets short enough that the remainder drops to that floor, the metadata section renders
at 80dp — a small fraction of its ~390dp of actual content — with no visible scroll
affordance, so the user sees album art/artist/album as simply gone.

## Impact

Hits anyone who resizes the main window shorter than roughly (art height + text + divider +
lyrics floor) — no minimum window height is enforced, so this is easy to trigger by accident
with an ordinary window-border drag. No workaround is discoverable from the UI: the metadata
box does scroll, but nothing signals that, and the divider between metadata/lyrics looks like
an internal resize handle, not a hint that the *window* is the thing to grow back.

## Open questions

- Should the fix scale `MIN_METADATA_HEIGHT` off the actual measured content height (album
  art + text, which changes with `panel.rightWidth` and which metadata fields are
  non-empty), or should the metadata section simply never be capped — i.e. always take its
  natural (`wrapContentHeight`) size, leaving lyrics (and, if that's also insufficient, the
  whole panel) to absorb the shrink via scrolling? The user's framing ("visible no matter
  what") points at the second.
- Is a minimum window height also wanted (`Window`'s underlying AWT `window.minimumSize`),
  or is a purely layout-level fix (metadata never shrinks, lyrics/panel scrolls instead)
  sufficient on its own?

## Options

**A. Raise `MIN_METADATA_HEIGHT` to a size derived from real content.**
Compute the floor from `maxWidth` (album art height) + a fixed text budget instead of a flat
80dp, so the metadata section can shrink but never below what's needed to show art + text.
- Pros: smallest change, keeps the existing split/drag model.
- Cons: still a "floor" that can theoretically be out-shrunk if the window gets short enough
  that even `MIN_METADATA_HEIGHT` (now bigger) + `MIN_LYRICS_HEIGHT` + divider don't fit —
  same overflow bug as today, just at a smaller window size. Also has to grow/shrink with
  `panel.rightWidth` changes, adding a bit of cross-dependency between two independently
  draggable dimensions that don't otherwise know about each other.

**B. Make metadata size to its own content; lyrics (then the whole panel) absorbs the shrink.**
Give the metadata section `wrapContentHeight()` instead of a computed/floored `height(...)`,
so it always renders at its natural size. Lyrics keeps `weight(1f)` off whatever's left, with
its own small floor. If even that isn't enough room, wrap the outer `Column` in a
`verticalScroll` so the whole panel scrolls rather than any single section overflowing or
getting clipped.
- Pros: directly matches "visible no matter what" — metadata is never squeezed, only
  lyrics/the panel as a whole gives way. Removes the 168dp overflow edge case entirely
  (scrolling replaces overflow).
  Simpler mental model: one thing (a document) that scrolls, not two competing floors.
- Cons: on a very short window, the lyrics area could shrink to near-nothing or the whole
  panel becomes scroll-only, which is a UX tradeoff in the other direction — lyrics becomes
  the thing that "disappears" (behind a scroll) instead of metadata. Loses the current
  "always show at least `MIN_LYRICS_HEIGHT` of lyrics without scrolling" guarantee unless
  that's deliberately kept as a secondary floor.

**C. Enforce a minimum window height.**
Set `window.minimumSize` (AWT) to a value tall enough that the current split math never hits
its floors.
- Pros: trivial, one line, doesn't touch the panel's layout code at all.
- Cons: doesn't fix the underlying layout — it just prevents the user from finding the edge
  case. Any future change to panel content (a new metadata field, a wider default panel)
  silently reopens the same bug at a different threshold. Also caps the whole window, not
  just this panel, which may be an unwanted side effect for a user who wants a short, wide
  window for the track list.

## Recommendation

**B.** It's the only option that satisfies "visible no matter what" literally — metadata
stops being a variable the shrink math can squeeze — and it also removes the second bug (the
168dp overflow case) as a side effect, since scrolling replaces the failure mode instead of
patching its threshold. A can still be layered in later as a secondary safety net for the
`MIN_LYRICS_HEIGHT` floor, but by itself A only moves the failure point rather than removing
it.

## Decision

Option B approved by user (chat, 2026-09-15). Metadata section renders at its natural
content size and is never shrunk; the panel as a whole scrolls (metadata first, so it's
always visible at the top) when metadata + divider + lyrics height exceed the available
panel height.

## Implementation plan

### Part 1 — Metadata always full size; panel scrolls instead of squeezing it [ ]
`NowPlayingPanel.kt`:
- Remove `MIN_METADATA_HEIGHT`, `MIN_LYRICS_HEIGHT`, `DIVIDER_AND_SPACERS` and the
  `totalHeight`/`clampedLyricsHeight`/`metadataHeight` clamp math — none of it is needed
  once metadata is never capped.
- Replace the outer `BoxWithConstraints` with a plain `Box` (its `maxHeight` was only used
  by the removed math).
- Drop the metadata section's own fixed `height(metadataHeight)` + `verticalScroll` wrapper
  — let it size to its content (album art + text) directly.
- Wrap the whole content `Column` (metadata + divider + lyrics) in `verticalScroll`, so if
  it doesn't fit the panel's height, the panel scrolls instead of any section being clipped
  or squeezed. Metadata renders first, so it's visible without scrolling by default.
- Give the lyrics section (`Text` and its "No lyrics available" placeholder) an explicit
  `height(lyricsHeight)` instead of `weight(1f)` (weight requires a bounded parent, which a
  scrollable `Column` no longer provides) — its own `verticalScroll` for long lyrics text
  stays, so long lyrics still scroll within their own area before the whole panel needs to.

**Verify:** Manual — run `:composeApp:run`, play a track, shrink the window height with the
OS window border down to a small size, confirm album art/title/artist/album stay fully
visible (no scrolling needed for them) and the lyrics area is what scrolls/shrinks instead.
Then confirm a normal-sized window still looks unchanged (metadata + divider + lyrics
positioned the same as before, lyrics-height drag handle still works and still persists).

**Landed:** Verified via `wmctrl`/`xdotool`/`import` screenshots. At a moderate shrink
(window height 700→500) the full metadata block (art + title/artist/album/year) now stays
completely visible with room to spare — previously this same shrink would have squeezed
metadata to ~142dp per the old formula, cutting off text.

**Correction after initial landing:** the first version wrapped the whole content `Column`
in `verticalScroll`, per the plan above (fallback for "even the album art alone doesn't
fit"). This broke mouse-wheel scrolling over the lyrics text — instead of scrolling only the
lyrics `Text`'s own internal `verticalScroll`, wheel events were captured by the outer scroll
first, dragging the entire panel (including album art) instead. Reported by the user as "if
the lyrics are big I start scrolling, then the whole right panel scrolls."

Fixed by removing the outer `verticalScroll` entirely and relying on plain `Column`
semantics instead: metadata has no explicit height, so as the *first, non-weighted* child it
always gets measured at its full natural size first; the lyrics `Box`/`Text` (explicit
`.height(lyricsHeight)`, still user-draggable) is a later non-weighted child, so Compose's
own sequential remaining-space allocation clamps it down to whatever's left — no manual
floor/clamp math, no scroll wrapper, and no nested-scroll conflict, since there's only ever
one scrollable (the lyrics text itself) in the tree at a time. The only remaining edge case —
window so short even metadata alone can't fit — degrades to Compose's normal
constraint-clamping (metadata itself gets compressed, same as any layout would), not a
special-cased scroll.

Also added a plain (non-interactive) `HorizontalDivider` between metadata and lyrics per
user request, separate from the functional drag handle, which stays at the lyrics box's
actual bottom edge (`ui/NowPlayingPanel.kt`, `ui/MainLayout.kt`'s `onLyricsHeightDrag` sign
flipped to match: dragging the handle down grows the box above it).

**Second correction:** the drag handle itself is now removed entirely. User pointed out that
once metadata never shrinks, there's nothing left to trade the drag against — the two-bar
appearance (static divider + drag handle, both visually identical, sitting close together
when lyrics is empty) read as a stray duplicate rather than two purposeful controls. Lyrics
now fills whatever space is left via plain `Modifier.weight(1f)` (no manual sizing, no
persisted height) below the one remaining static divider. Removed along with it:
`onLyricsHeightDrag`/`onLyricsHeightDragEnd` params and the `lyricsHeight` state from
`MainLayout.kt`, `MIN_LYRICS_HEIGHT`/`MAX_LYRICS_HEIGHT` constants (now unused), the
`DraggableHorizontalDivider` composable, and the `nowPlayingLyricsHeight` preference
(`PreferencesManager.kt`, `nowplaying.lyricsHeight` key — dropped from `CLAUDE.md`'s
preferences table). Verified via `composeApp:jvmMainClasses` + `detekt` (both clean); visual
check left to the user.
