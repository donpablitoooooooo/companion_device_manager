# CLAUDE.md

Project-specific guidance for Claude Code working on this repository.

## Debugging philosophy

- **Do not blame battery optimization, OEM background restrictions, or Doze
  mode for bugs.** Not Xiaomi/Redmi, not Samsung, not anyone. If the user
  reports a failure, assume it's a code problem in this repo until proven
  otherwise. Only revisit OS-level restrictions once the code is _perfect_
  — and the bar for "perfect" is the user's, not yours.
- Root-cause every failure inside the plugin, the example app, or the
  Flutter/Android integration before pointing fingers outward.
