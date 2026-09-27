# Revisit

Real problems noticed outside the task at hand — not fixed inline, not dropped.

- **`nextTrack()` / `previousTrack()` pick from the *selected* folder, not the *playing* one**
  (`composeApp/.../MukkViewModel.kt:225-287`, both read `_selectedFolderEntries`). Play a
  track in folder A, then single-click folder B in the tree: "next" plays B's first track
  (`currentIdx < 0 -> 0`), not A's next track. Also affects auto-advance at track end
  (`onTrackFinished = { nextTrack() }`, `:103`). Seen 2026-09-27 while planning the Mukklet
  `next` preview (`docs/issues/2026-09-27-mukklet-display-link.md`, Open question 3), which
  mirrors this behaviour on purpose rather than fixing it. Needs its own investigation: it
  may be intended (AIMP-like "play what you're looking at") or a bug.
