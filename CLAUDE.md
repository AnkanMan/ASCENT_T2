# ASCENT — Android app

A personal, Solo Leveling–inspired life RPG for one player. An AI "System" sets
quests across three realms (Body, Mind, Self); real activity becomes XP, stats,
skills and rank. The player plans with Claude in the Claude app (on their own
Claude plan) and brings Claude's proposals into ASCENT through the share
bridge. There is no API key and no server.

**SPEC.md is the source of truth.** If this file and SPEC.md disagree, SPEC.md
wins — point out the conflict instead of guessing.

## Project files

| File | Purpose |
| --- | --- |
| `SPEC.md` | Full design: mechanics, numbers, screens, architecture, roadmap |
| `TODO.md` | Checklist for the current phase. One task at a time, in order |
| `DEVLOG.md` | Append-only session log: what changed, decisions, open issues |
| `CLAUDE.md` | This file: how to work in this repo |
| `docs/ASCENT-System-Project-Instructions.md` | Instructions for the player's Claude Project; defines the `ascent` block format the app must import |
| `docs/lore-bible.md` | Lore canon and voice rules; every lore line (seed JSON or Claude's) must follow it. Also a file in the Claude Project |
| `docs/sounds/SOUNDS.md` | Sources, licences and alternates for the CC0 sound effects |

## How we work

1. **Start of every session:** read this file, `TODO.md`, the last 3 entries of
   `DEVLOG.md`, and only the SPEC.md sections the current task needs.
2. **Plan before code.** For a new phase or any task touching more than a few
   files, write the plan (files, steps, risks) and wait for approval.
3. **One task per request.** Do the next unchecked item in `TODO.md` only.
4. **A task is done when** `./gradlew test assembleDebug` passes. Then say
   exactly what to check on the phone or in Compose Preview.
5. **End of session:** tick finished items in `TODO.md` and append a `DEVLOG.md`
   entry (date, what changed, decisions made, open issues). Suggest a commit
   message; don't commit unless asked.
6. **When stuck after two failed attempts**, stop and explain the problem
   instead of trying a third variation.

### Ask before you

- add, remove or upgrade any dependency or Gradle plugin
- change `minSdk`, `targetSdk`, the package name or the app ID
- write a Room migration that could drop or rewrite player data
- delete or move files outside the current task
- change any number or rule defined in SPEC.md (XP curve, difficulty
  controller, safety floors, rank thresholds)

## Commands

```bash
./gradlew assembleDebug          # build
./gradlew test                   # all unit tests
./gradlew :core:engine:test      # engine tests only (fast)
./gradlew installDebug           # build and install on the connected phone
adb logcat -s AndroidRuntime     # crash stack traces
```

## Stack

Kotlin · Jetpack Compose (Material 3, heavily themed) · single activity +
Navigation Compose · MVVM with `StateFlow` · Hilt · Room + DataStore ·
WorkManager · kotlinx.serialization · Health Connect (Phase 6) · Android
share sheet and clipboard for the Claude bridge (Phase 2). No network
dependencies.

## Architecture

```
:app                      entry point, navigation, DI wiring
:core:engine              pure Kotlin: XP, levels, ranks, difficulty, effects, rewards
:core:data                Room entities/DAOs, DataStore, repositories
:core:designsystem        colour tokens, typography, HoloPanel, animations, sounds
:integrations:bridge      ASCENT STATUS export, share intent + clipboard import,
                          `ascent` block parsing and validation
:integrations:health      Health Connect (Phase 6, after the Release)
:feature:<name>           one module per screen group: status, quests, console,
                          gates, inventory, skills, codex, assessment
```

Create a module only when the current phase needs it.

**Rules**

- `:core:engine` has **no Android imports**. Every engine change ships with
  unit tests. All game math lives here and nowhere else.
- Composables render state and emit events. No business logic, no repository
  calls, no XP math inside composables.
- One immutable `UiState` per screen, exposed as `StateFlow` from its ViewModel.
- Bonuses from skills, gear and class are data (JSON effects) folded by
  `RewardCalculator`, never special-cased in code.
- Every screen-level composable has a `@Preview` with sample data.

## Invariants — never break these

- **Claude never writes to the quest log.** Its proposals arrive as an
  `ascent` code block the player imports; the player applies or dismisses each
  action. Validate every op (unknown ids, bad JSON, missing fields) and clamp
  targets with the adaptive engine before showing a card. Never crash on a
  malformed block: show what could be read and say what couldn't.
- **Safety floors hold at every difficulty and every strictness setting:**
  Body targets rise at most 10% per week; at least one rest day per week; sleep
  targets never below 7 h; no fasting or calorie-restriction quests.
- **The player never loses a level.** Penalties cost HP or streak only.
- **Player data is never lost.** No destructive Room migrations
  (`fallbackToDestructiveMigration` is banned). Export schemas and write a
  migration test for every schema change.
- **The app works fully offline.** Daily quests come from templates plus the
  adaptive engine; the Claude bridge is optional. Manual add and check-off
  always work.
- **Parked:** no Git repository watching or GitHub integration. Do not add it.

## Claude bridge (no API)

- The app makes **no calls** to Anthropic or any AI service. Do not add an
  API client, API key, proxy or `INTERNET`-based AI feature.
- **Out:** "Ask the System" builds an `ASCENT STATUS` text (date, level, streak,
  difficulty per realm, goals and quests with ids, last 7 days, Dossier notes)
  and opens the Android share sheet, with a copy-to-clipboard fallback.
  Include only aggregates, never raw Health Connect records.
- **In:** ASCENT is a share target for plain text and has an "Import from
  Claude" button that reads the clipboard. Find the last ```` ```ascent ```` block,
  parse it with kotlinx.serialization, and show proposal cards.
- The block format is defined in `docs/ASCENT-System-Project-Instructions.md`.
  If you change the format, update that file and SPEC.md in the same task.

## Visual identity

Dark void, blue holographic System panels. Use only the tokens in
`:core:designsystem` — no raw hex values in feature code. See SPEC.md
"Visual and audio identity" for the palette, type and animations.

- Fonts: Rajdhani (headers), JetBrains Mono (numbers, stats), Inter (body).
- System window headers may be uppercase (`◆ QUEST ISSUED`); everything else
  is sentence case.
- Respect the system "Remove animations" setting; touch targets ≥ 48 dp; every
  icon-only button has a `contentDescription`.

## Originality

Inspired by Solo Leveling in mood only. Do **not** use names, characters,
places, skill names or dialogue from Solo Leveling or any other franchise.
All lore, titles, skills and System text are original (see SPEC.md).

## Code style

- Kotlin official style; Android Studio's default formatter.
- Prefer `val`, data classes, sealed interfaces for events and results.
- Coroutines with structured concurrency; no `GlobalScope`; no blocking calls
  on the main thread.
- Small files, clear names, comments only where the *why* isn't obvious.
- User-facing text in `strings.xml`.
