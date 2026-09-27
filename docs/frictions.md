# Frictions

One line per entry, past tense, naming the tool and the surprise. Three occurrences of the
same friction gets promoted to a real fix (a `CLAUDE.md` line, a script, a permissions entry)
and the promoted lines leave this file.

- `kotlinc` was not installed, so a "throwaway `main` in the scratchpad" (Mukklet Part 2
  plan) could not be compiled directly. Worked around with a temporary `jvmTest` class that
  returned early unless an env var was set, run via `--tests '*Name*' --rerun`, then deleted.
- `xdotool key --window <id> space` did not reach the Compose Desktop window (no play/pause),
  even after `windowactivate --sync`; mouse clicks are the reliable way to drive the app.
- `gh pr edit` failed with a "Projects (classic) is being deprecated" GraphQL error; `gh api -X PATCH repos/<o>/<r>/pulls/<n> -F body=@file` worked.
- `ffprobe -select_streams v` reported no picture stream for MP3s whose ID3 tag did hold art (Acid, "Maniac"); scanning the raw ID3 frames for `APIC`/`PIC` was the reliable "no art" check.
- A background `python3 fake_display.py < fifo` never started until a writer opened the FIFO — the shell blocks on the `<` open, so the display started late and the app connected only on a later backoff retry.
- Setting `playingTrack` in `preferences.properties` before launch did not pick the restored track — startup restored the previous one anyway (cause not checked; likely the active-playlist restore). Driving the GUI with clicks was the working way to play a chosen file.
- A dev `:composeApp:run` and a `fake_display.py` from an earlier session were still running at session start, with classes older than the new build; they had to be killed before testing.
