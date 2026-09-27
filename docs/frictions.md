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
