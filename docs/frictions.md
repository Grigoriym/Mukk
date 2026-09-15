# Frictions

One line per entry, past tense, naming the tool and the surprise. Three occurrences of the
same friction gets promoted to a real fix (a `CLAUDE.md` line, a script, a permissions entry)
and the promoted lines leave this file.

- `./gradlew :composeApp:run` exited clean (code 0, "Another instance is already running,
  notifying it") without launching a new JVM, because the user's packaged `/opt/mukk/bin/Mukk`
  install was already running and holding the `SingleInstance` lock — looked like a successful
  launch in the wrapper output until `ps aux` showed no `MainKt` process. Recurred a second time
  (2026-09-15, NowPlayingPanel resize fix session) — still only two occurrences, not promoted.
