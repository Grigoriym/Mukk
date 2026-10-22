# 2026-10-22 — Dragging a playlist tab past two neighbors oscillates

**Status:** Done
**Link:** none (reported in chat)   **Updated:** 2026-10-22

## Report
The owner reported this in chat on 2026-10-22, on the installed v1.1.2. Their words:

> I want to move the 3rd from the end to the end, I start moving it, it moves one playlist
> forward and then when it on its way to change the last playlist it moves to the 3rd position
> and if i keep moving it to the end, it will just swap places with the 2nd playlist. How it
> looks: ..... A,B,C; ....B,A,C; .... A,B,C; .... B,A,C;

So the symptom is this. Drag tab `A` to the right in `A,B,C`. The first swap works (`B,A,C`).
After that, more drag to the right swaps the first two tabs back and forth. `A` never reaches
the end.

A drag over a single neighbor works. The bug needs two or more swaps in one drag.

## Findings
1. **Tabs are composed by position, not by id.** `PlaylistTabBar` renders
   `order.forEach { playlist -> PlaylistTab(...) }` with no `key(playlist.id)`
   (`composeApp/src/jvmMain/kotlin/com/grappim/mukk/ui/PlaylistTabBar.kt:88`). Compose matches
   each tab's state to its slot index in the `Row`.
2. **The drag gesture lives in a slot, and its callbacks follow the slot.** The gesture detector
   is `pointerInput(Unit)` and reads its callbacks through `rememberUpdatedState`
   (`PlaylistTabBar.kt`, `playlistTabGestures`). This was the Part 4 fix in
   `docs/issues/2026-09-15-playlists-and-queue.md`. It keeps the gesture coroutine alive
   across recompositions. But it also means: after a swap, the running gesture in slot 0 calls
   the callbacks that slot 0 now holds, which belong to `B`.
3. **Trace of the reported case**, with `order = A,B,C` and a drag to the right that starts on
   `A` (slot 0):
   - `onDragStart` for `A` sets `draggingId = A`.
   - `onDrag` for `A`: `reorderedAfterDrag(order, A, ...)` swaps `A` with `B` → `B,A,C`, and
     subtracts `B`'s width from `dragOffsetX`.
   - Recomposition: slot 0 now shows `B`. The gesture is still in slot 0, so its
     `currentOnDrag` is now `B`'s lambda.
   - `onDrag` for `B`: `reorderedAfterDrag(order, B, ...)` finds `B` at index 0 and swaps it with
     index 1 (`A`) → `A,B,C`.
   - Slot 0 shows `A` again, so the next swap gives `B,A,C`, and so on. This is exactly the
     reported sequence.
4. **The offset is drawn on the wrong tab, too.** `dragOffsetX` is applied where
   `playlist.id == draggingId` (`PlaylistTabBar.kt:91`). After the first swap that is `A` in
   slot 1, but the pointer drives slot 0. Inference: this is why the dragged tab looks
   detached from the mouse.
5. **Not reproduced by automation.** `xdotool` drags do not trigger Compose Desktop drag
   gestures (see CLAUDE.md, "GUI-driving the running app"). The evidence is the code trace
   plus the owner's reproduction, which matches it step by step.

## Root cause
`PlaylistTabBar.kt:88` composes tabs without `key(playlist.id)`. When the drag reorders `order`,
each slot gets a different playlist, but the running drag gesture stays in its old slot. Through
`rememberUpdatedState`, that gesture then calls the callbacks of whichever playlist now holds the
slot. Every drag event after the first swap therefore moves the neighbor, not the dragged tab.

## Impact
Every user who drags a tab more than one position. The workaround is to drag one position at a
time (release, then drag again). Nothing is lost: the final order is still saved correctly by
`onDragEnd`.

## Open questions
None that block a decision.

## Options
**A. Wrap each tab in `key(playlist.id) { PlaylistTab(...) }` (recommended).**
- Changes: one wrapper in `PlaylistTabBar.kt`. Compose then moves the tab's composition, and
  its `pointerInput` coroutine, together with the playlist when `order` changes. The gesture
  keeps calling the dragged tab's own callbacks.
- Pros: fixes the cause. It also fixes Finding 4, because the offset and the pointer are on the
  same tab again. It is the standard Compose fix for reordered lists.
- Cons: a moved node keeps its layout position until the next frame. Inference: the drag
  should still feel continuous, because `reorderedAfterDrag` already subtracts the swapped
  width from `dragOffsetX`. Only a manual drag can confirm this.
- Risk / blast radius: low. Only the tab strip. `isEditing` already uses `remember(playlist.id)`,
  so its behavior does not change.

**B. Track the dragged id outside the slot.** Make `onDrag` ignore its slot's playlist and
always use `draggingId`.
- Pros: also stops the oscillation.
- Cons: the gesture still runs in the wrong slot. The pointer and the drawn offset still
  disagree (Finding 4). It works around the cause and does not fix it.

**C. Do nothing.** One-step drags work. Cons: the main use of reorder, moving a tab more than one
step, stays broken.

Recommendation: **A**.

## Decision
Option A, approved by the owner in chat on 2026-10-22 ("Please address two bugs").

## Implementation plan

### Part 1 — Key each tab by playlist id [x]
- In `PlaylistTabBar.kt`, wrap the `PlaylistTab(...)` call inside `order.forEach` in
  `key(playlist.id) { ... }`.
- **Verify:** `./gradlew composeApp:jvmMainClasses detekt`. Then **manual** (the owner, since
  `xdotool` drags don't reach Compose): with tabs `A,B,C`, drag `A` to the end in one drag.
  Expected: `B,C,A`, with no back-and-forth, and the order survives a restart.
- **Landed:** as planned. `composeApp:jvmMainClasses detekt` pass. The owner confirmed the
  multi-step drag works in a packaged build of this branch on 2026-10-22.
