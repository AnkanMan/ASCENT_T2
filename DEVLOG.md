# DEVLOG

Append-only. Newest entry at the bottom.

---

## 2026-11-11 — Phase 1 planning

**Changed**
- Wrote `TODO.md` for Phase 1 (Foundations): 23 tasks covering the skeleton,
  engine, design system, static Status Window and exit review.
- `CLAUDE.md`: font line now matches SPEC (Rajdhani headers, JetBrains Mono
  numbers and stats, Inter body).

**Decisions**
- App ID `com.tirthankar.ascent`; minSdk 28; compile/target SDK latest stable.
- Project created with Android Studio's Empty Activity wizard.
- Phase 1 dependencies approved (list in `TODO.md` §0). Hilt, Room, DataStore,
  Navigation and WorkManager deferred to Phase 2.
- Fonts bundled as OFL `.ttf` in `res/font`, downloaded from google/fonts.
- No Git repository for now.
- Engine rules SPEC leaves open:
  - `xp_mult` effects stack additively.
  - Initial completion EMA = 0.80.
  - Rounding: reps 5, minutes 15, steps 500, pages 5, km 0.5, other 1.
- Safety overrides (Fatigue, Body cap, deload, re-entry, floors) move to
  Phase 2.

**Open issues**
- SPEC's pacing ("~3 weeks to Lv 10, ~3.8 years to S") assumes no streak
  bonus and d = 1. My estimate is that with the streak bonus maxed, S comes in
  ~2.5 years. Task 11 will measure it; any change to SPEC numbers needs approval.
- AI proxy token storage (`BuildConfig` in CLAUDE.md vs. Android Keystore in
  SPEC) is unresolved; deferred until the proxy is used.

---

## 2026-11-11 — Task 1: Android project created

**Changed**
- Generated with the Empty Activity wizard in `../ascent-gen`, because the
  wizard won't create a project inside the folder that's open. Then moved
  into this folder (`app/`, `gradle/`, wrapper, settings, `.gitignore`,
  `local.properties`).
- Template: AGP 9.5.1 (built-in Kotlin), Kotlin 2.2.10, Compose BOM
  2026.02.01, compileSdk/targetSdk 37, minSdk 28, Java 11.
- `MainActivity`: the template greeting is replaced by a centred placeholder
  showing `R.string.app_name`, with a Preview.
- `./gradlew test assembleDebug` passes.

**Decisions**
- Kept the template's extra libraries (lifecycle-runtime-ktx, ui-graphics,
  instrumented-test libraries).
- Kept the template's library versions (core-ktx 1.10.1, lifecycle 2.6.1,
  activity-compose 1.8.0). Upgrading them needs approval.

**Open issues**
- The shell has no Java on PATH. Gradle needs
  `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- `../ascent-gen` is left over (just `.gradle` and `.idea`). Delete it by hand.
- The template `ui/theme` (raw hex `Color.kt`) stays until Task 12 replaces
  it with `:core:designsystem`.
- The IDE still has this folder open as a plain folder. Reopen it so Android
  Studio syncs it as a Gradle project.

---

## 2026-11-11 — Task 2: `:core:engine` module

**Changed**
- New pure-Kotlin JVM module `:core:engine` (package
  `com.tirthankar.ascent.engine`), Java/JVM target 11 like `:app`.
- Version catalog: `kotlin-jvm` plugin and `kotlin-test` library, both at
  the template's Kotlin version (2.2.10). Approved in §0.
- `Realm` (BODY, MIND, SELF), `Stat` (each with its realm), `Tier` (base XP
  20/40/80/150/300), plus `DomainEnumsTest` (3 tests).
- `./gradlew :core:engine:test` and `./gradlew test assembleDebug` pass.

**Decisions**
- `:app` doesn't depend on `:core:engine` yet. That link comes with the
  Status Window (Task 20).
- The separately declared `kotlin-jvm` plugin works alongside AGP 9's
  built-in Kotlin. No version clash.

**Open issues**
- "Timeouts" on file edits meant the IDE was waiting for the user to accept
  the diff. They weren't failures.
- Android Studio suggests Kotlin 2.4.21. Not upgraded (needs approval).

---

## 2026-11-11 — Task 3: XP curve and level progress

**Changed**
- `XpCurve`: `xpToNext(n) = floor(100 · n^1.3)`, `totalXpToReach(level)`,
  `levelProgress(totalXp)` → `LevelProgress(level, xpIntoLevel, xpForNext)`
  with a `fraction` for the XP bar. All totals are `Long`.
- `XpCurveTest`: 9 tests (first ten values, exactness at n = 1024, strictly
  increasing, cumulative totals, exact boundaries, round-trip over levels
  1..200, invalid input).
- TODO Task 3 check: corrected level-10 total from 7,694 to **7,691**
  (hand-arithmetic slip; values cross-checked with Python).

**Decisions**
- No level cap: SPEC defines none, and levels past 100 just keep going up
  the curve.
- `levelProgress` walks the curve level by level. That's fast enough for
  the levels players will reach (a few hundred at most), so no lookup table.

**Reference numbers** (total XP to reach a level): Lv 10 = 7,691;
Lv 25 = 68,102; Lv 45 = 268,802; Lv 70 = 749,555; Lv 100 = 1,710,984.

---

## 2026-11-11 — Task 4: Rank thresholds

**Changed**
- `Rank` enum E, D, C, B, A, S, SIGMA with `minLevel` 1/10/25/45/70/100/null.
- `RankRules.rankUnlockedBy(level)`: highest rank the level qualifies for.
  Never returns SIGMA.
- `RankRules.availableTrial(currentRank, level)`: the Rank Trial currently
  open, or null.
- `RankRulesTest`: 7 tests. Engine total: 19 tests, all passing.

**Decisions**
- A rank is *earned* by clearing its trial; the level only opens the trial
  (SPEC). So the player's rank is stored state (Phase 2, Room), not derived
  from level.
- Trials go one rank at a time: an E-rank player at level 80 faces the D
  trial first. SPEC doesn't say this outright; I took it as the natural
  reading.
- The Σ trial needs the thesis raid (Phase 3+). For now `availableTrial`
  returns null at S.
- Rank aura colours are a design-system concern, so they're kept out of the
  engine.

---

## 2026-11-11 — Tasks 5–22: rest of Phase 1 (engine, design system, Status Window)

You asked me to finish Phase 1 in one go, which overrides the
"one task per request" rule for this session. I still built and tested after
each group.

**Changed: build**
- Catalog: `android-library` and `kotlin-serialization` plugins,
  `kotlinx-serialization-json` 1.9.0 (built for Kotlin 2.2),
  `lifecycle-viewmodel-compose` and `lifecycle-runtime-compose` (at the
  template's lifecycle 2.6.1). All on the approved §0 list.
- New modules `:core:designsystem` and `:feature:status` (Android libraries,
  minSdk 28, compileSdk 37, Java 11). `:app` depends on both.

**Changed: engine** (63 tests)
- `StatBlock`, `DerivedStats` (max HP and MP, crit, cooldown reduction),
  `Mastery.masteryToNext`.
- `QuestXp`: SPEC formula, rounded half-up, with a streak cap and bonus hook.
- `Effect` (sealed, kotlinx.serialization, discriminator `kind`): `xp_mult`
  (all/realm/stat), `mastery_mult`, `streak_cap`, `crit_bonus`. `EffectCodec`
  skips and reports unknown kinds and invalid values; it never throws.
- `RewardCalculator`: additive stacking, highest streak cap wins, crit
  chance clamped to [0, 1], injected `Random`.
- `DifficultyController`: SPEC update rule, d in [0.5, 3.0], starts at
  d = 1.0 and c = 0.80; `updateAll` leaves realms with no objectives
  unchanged.
- `TargetUnit` + `TargetScaler`: the agreed rounding steps, half-up, never
  below one step; `fromLabel` maps "min", "reps" and so on.
- `PacingTest`, at SPEC's assumptions: D on day 20, C day 126, B day 369,
  A day 783, S day 1,394 (3.82 years). This matches the SPEC table.

**Changed: design system**
- `AscentColors` (the SPEC palette; the only hex in code) and `ascent_void`
  for the window background.
- `AscentTheme` (dark Material 3 scheme mapped from tokens).
- Fonts: Rajdhani Medium, SemiBold and Bold, plus Inter and JetBrains Mono
  as variable fonts, downloaded from google/fonts (OFL).
- `AscentTypography`, plus `AscentTextStyles` (SystemHeader, SystemLabel,
  Number, NumberLarge).
- `LocalReducedMotion` follows the system "Remove animations" setting live
  through a ContentObserver.
- `HoloPanel` (glass, 1 dp border, corner brackets, scanlines, `◆ HEADER`)
  and `SystemBackground` (hex grid).
- `HoloGlow` (RenderEffect blur on API 31+, drawn rings below).
- `Materialize` (220 ms line-to-full with two flickers; instant with reduced
  motion).
- `SystemProgressBar` (XP/HP/MP; TalkBack reads it as "HP 180 of 250").
- Previews for all of the above in `DesignSystemPreviews.kt`.

**Changed: Status Window**
- `StatusUiState`, `PlayerSnapshot`, `StatusUiStateMapper` (all numbers come
  from the engine), `SamplePlayer` (level 10, 1,309 / 1,995 XP, HP 180/250,
  MP 120/164).
- `StatusScreen` + Preview; `StatusViewModel` (static `StateFlow`);
  `StatusRoute`.
- `MainActivity` shows it inside `AscentTheme` and `SystemBackground`.
- Window theme is now dark with a Void background. Template `ui/theme` files
  and template colours are removed.

**Decisions**
- In Android modules, tests use `org.junit.Test` with `kotlin.test`
  assertions, because `kotlin.test.Test` doesn't resolve to JUnit under
  AGP 9's built-in Kotlin. The JVM engine module keeps `kotlin.test.Test`.
- HP bar uses Penalty Red, MP uses System Blue, XP uses Glow Cyan.
- Task 14: no debug label. The live check is Materialize: with "Remove
  animations" on, the Status Window appears instantly.
- Rank is shown as plain text in System Blue for now. Per-rank aura colours
  come with avatar evolution (Phase 4). E-rank's "faint grey-blue" isn't a
  token yet.
- Stat-scoped `xp_mult` applies only when the quest lists that stat. SPEC's
  "class gives +10% XP to its two stats" needs quests to carry stats.
  Revisit when quests exist (Phase 2).

**Open issues**
- Phone checks not done yet: install, materialize, "Remove animations",
  TalkBack, and font scaling.
- Launcher icon is still the wizard's green Android (the only hex outside
  the tokens). Replace it with an original ASCENT icon later.
- OFL licence texts for the bundled fonts aren't in the repo yet. Fine for
  a private app, but add them before sharing it.
- Template library versions (lifecycle 2.6.1, activity 1.8.0, core-ktx
  1.10.1) are old. Upgrading needs approval.

---

## 2026-11-11 — Design change: Claude share bridge replaces the API

**Changed**
- `SPEC.md` now matches `SPEC copy.md` (verified with `diff`). ASCENT makes
  no AI calls. "Ask the System" shares an ASCENT STATUS text to the Claude
  app. Claude replies with an `ascent` block (`{"v":1,"actions":[...]}`),
  which you share back or import from the clipboard.
- The proxy, API key, Keystore token and `propose_changes` tool are gone from
  SPEC. Phase 2 now includes Ask the System and Import from Claude. Phase 6
  is reworded.
- `CLAUDE.md` is updated from `CLAUDE copy.md`: a new "Claude bridge (no
  API)" section replaces "Secrets", and `:integrations:ai` becomes
  `:integrations:bridge`. The invariants and stack are reworded. The font
  line stays as Rajdhani / JetBrains Mono / Inter, because the copy still
  had the old line that contradicts SPEC.
- Moved `ASCENT-System-Project-Instructions.md` into `docs/`, where both SPEC
  and CLAUDE.md say it lives. It is the contract for the `ascent` block.

**Decisions**
- Resolved: the proxy-token storage question (BuildConfig vs. Keystore) no
  longer applies.
- Phase 1 code needed no changes. Nothing in it used the network or an AI.

**Open issues**
- These need settling before the Phase 2 bridge work:
  - Daily quest mix: SPEC's Daily Quest table says 4 objectives (1–2 Body,
    1 Mind, 1 Self). The Project instructions say 3–5 per day (typically
    1 Body, 1–2 Mind, 1–2 Self). SPEC's goal hierarchy also says 3–5.
  - Tiers: the block uses easy/normal/hard. The engine also has Trivial and
    Extreme, which Claude can't request. Map easy/normal/hard directly.
  - Field names: the block's `recurring` field appears in the Project
    instructions but not in SPEC's example. Treat it as optional.
- The Player section of the Project instructions still has blanks (research
  projects, deadlines, rest day) for you to fill in before pasting it into
  Claude.
- `CLAUDE copy.md` and `SPEC copy.md` are still in the repo root. Delete them
  when you're happy.

---

## 2026-11-11 — Bridge decisions settled

**Changed**
- SPEC (approved rule change): the Daily Quest is now **3–5 objectives
  (1 Body, 1–2 Mind, 1–2 Self)**, replacing 4 (1–2 Body, 1 Mind, 1 Self).
  Updated in the Quest system intro, the quest-type table and the AI-layer
  table.
- SPEC, under the `ascent` block example: Claude's tiers easy/normal/hard map
  to Easy/Normal/Hard, and Trivial and Extreme aren't available to Claude.
  `recurring` and `goal` are optional (defaults false and null).
- `docs/ASCENT-System-Project-Instructions.md`: added the `recurring` rule
  (optional, default false, true = repeats every day). The block format
  itself is unchanged.

**Notes**
- SPEC's "a full Daily Quest pays about 300 × (1 + 0.05 L)" and the
  PacingTest are unchanged. With 3–5 objectives a full day now pays roughly
  240–400 × (1 + 0.05 L) at Normal tier, so 300 is a mid-range figure.
  Revisit when real quest data exists.
- Fatigue's "cut the Daily Quest to three objectives" still fits the
  3–5 range.

**Open issues**
- `CLAUDE copy.md` and `SPEC copy.md` still need deleting by hand. My
  delete was blocked by the permission guard. Both are fully merged.

---

## 2026-11-11 — Phase 2 plan and decisions D1–D10

**Changed**
- `TODO.md` is now the Phase 2 plan: 40 tasks in sections A–K, plus
  decisions D1–D10. All ten decisions are answered and ticked.
- SPEC (approved new rules):
  - Character creation: the starting-stat formula
    shift = round(10 × min(current / goal, 1) − 5). The 1 km time uses
    goal / current. STR is the mean of push-ups and squats. FOC starts at 10.
  - How stats grow: mastery per quest is Trivial 2, Easy 5, Normal 10,
    Hard 20, Extreme 40 (× mastery effects), paid to the quest's stat.
    Overflow carries over. Quests from Claude or added by hand default to
    the realm's first stat (STR, INT, WIS).
  - Quest system: the Daily's objectives are the quests issued for today.
    The streak rises only when all are done. Your own quests pay XP but
    don't count.
  - Goal hierarchy: the week runs Sunday to Saturday and turns over at the
    Sunday reset. This fits the existing "Weekly planning: Sunday" and
    "Weekly System Assessment: Sunday reset".
  - Adaptive engine: a "Baseline targets" list (2 × max set, plank 2 × max
    hold, steps + 1,000, walk 20 min, deep work 60 or 90 min above 4 h a
    day, reading pages per week ÷ 7 with at least 10, lights out 7 h before
    the day starts).

**Decisions**
- D1: add Room, KSP, Hilt (+ navigation-compose, work), Navigation Compose,
  DataStore, WorkManager, coroutines-test and Robolectric, and upgrade
  core-ktx, lifecycle and activity-compose.
- D2: migration and DAO tests run on the JVM with Robolectric under
  `./gradlew test`.
- D3: streak +1 only when every Daily objective is done. A miss resets it
  and costs 15 HP.
- D7: in Phase 2, HP changes only from the Daily: −15 on a miss, +30 for a
  rest-day Daily, recovery day at 0. Sleep HP comes with Health Connect in
  Phase 5. SPEC's rule itself is unchanged.
- D8: bottom bar Status · Quests · Console.
- D9: the week starts Sunday, not the Monday I'd recommended.
- D10: level-up animation in Compose only; no Lottie.

**Open issues**
- I accidentally overwrote this file with a placeholder while appending this
  entry. It was rebuilt from the session transcript (the original write plus
  all seven recorded edits).
- Task 1 is next: the dependencies and upgrades from D1.

---

## 2026-11-11 — Phase 2 Tasks 1–39 in one batch

You asked for the tasks in a batch, which overrides "one task per request" for
this session. I built and tested after each section.

**Changed: build** (D1)
- Upgraded core-ktx 1.19.1, lifecycle 2.11.0, activity-compose 1.13.0.
- Added Room 2.8.5 (and its Gradle plugin for schema export), KSP 2.3.13,
  Hilt 2.60.1, androidx.hilt 1.4.0 (navigation-compose, work),
  Navigation 2.10.2, DataStore 1.3.0, WorkManager 2.12.0, coroutines-test
  1.11.0, Robolectric 4.17, androidx.test core 1.7.0. Hilt and KSP work with
  AGP 9's built-in Kotlin; no fallback was needed.
- New modules: `:core:data`, `:integrations:bridge`, `:feature:awakening`,
  `:feature:quests`, `:feature:console`.

**Changed: engine** (124 tests)
- New: `StartingStats`, `Leveling`, `MasteryGrowth` (with an exact
  `remove` for undo), `DayResolver`, `Fatigue`, `ReEntry`, `SafetyRules`,
  `GameClock`, `Baselines`, `TemplateLibrary` (31 templates),
  `TemplatePlanner`, `ProposalLimits`, `TemplateMatcher`.
- `TargetUnit` gains SECONDS (step 15, for the plank) and HOURS (0.5), plus
  a `fineStep` per unit.

**Changed: data** (21 Robolectric tests, D2)
- Room v1: player, attribute, realm_difficulty, baseline, body_target, quest,
  goal, daily_snapshot. The schema is exported to `core/data/schemas`.
  `MigrationTest` validates v1 and that player rows survive opening.
- DataStore settings: reset time, wake time, rest days, quiet hours, Dossier
  notes, plus flags for the permission prompt and the reminder.
- Repositories: Player, Quest (complete and undo in one transaction), Goal,
  History. `DailyCycle` runs the reset.

**Changed: bridge** (19 tests)
- `AscentBlockParser`: last ```ascent block, or a bare `{"v":…}`. Tolerates
  trailing commas and a missing closing fence, reads each action on its own,
  and never throws.
- `ProposalValidator` produces READY, ADJUSTED or BLOCKED cards.
  `ProposalService` builds the status, reviews replies and applies cards.
- `StatusTextBuilder`: the format is documented in
  `docs/ASCENT-System-Project-Instructions.md`.

**Changed: UI and app**
- Design system: `HexagonCheck`, `StatRadar`, `XpFloat`, `LevelUpOverlay`
  (Compose only, D10). The XP and HP/MP bars now ease to new values. All
  respect reduced motion; previews are in `Phase2Previews.kt`.
- Screens, each with Previews:
  - Awakening: detection, identity, measurement, rhythm, confirm.
  - Status: real data, point allocation, radar.
  - Quest Log: Today and This week, check-off, add-quest and add-goal
    dialogs.
  - Console: Ask the System, Import from Claude, proposal cards.
- App: Hilt, a bottom bar Status · Quests · Console (D8), and the level-up
  overlay above every screen.
- `DailyResetWorker` and `ReminderWorker` run through Hilt; the app catches
  up on start and reschedules when the reset time changes.
- Notifications use the System channel and are posted silently during quiet
  hours.
- Share target: plain text to MainActivity. There is still no INTERNET
  permission; the merged manifest was checked.

**Decisions (new rules SPEC doesn't define: please confirm or change)**
- **Claude's targets:**
  - When a quest matches a template, it may range from 0.5× to 1.5× the
    engine's own target for today. Body quests never go above the engine's
    target, which already carries the +10% weekly cap.
  - Unmatched quests get only absolute caps: 300 reps, 120 min (Body) or
    240 min, 25,000 steps, 150 pages, 25 km, 600 s, 12 h.
- **+10% per week:** applied per Body template. When the next friendly step
  would jump more than 10%, a finer step is used (for example, 20 → 22
  push-ups, not 25).
- **Deload:** a "rising week" is one where the Body multiplier at the Sunday
  reset is above last week's. The fourth rising week issues 60% of the capped
  target, and the week after resumes from the pre-deload level.
- **Re-entry:** triggers once, when the third Daily in a row is missed:
  each multiplier becomes 0.8 × its current value.
- **Catch-up:** up to 60 missed days are replayed. Each gets a template
  Daily, resolved as a miss (−15 HP). Older days are skipped without penalty.
- **Undo:** same game day (or week) only, and only while no level has been
  gained since. This keeps "never lose a level" with an exact reversal.
- **Template Daily:** 4 objectives on odd days and 5 on even days, never
  more than 5. Training rotates strength, cardio, strength, mobility,
  strength, cardio. Strength alternates A/B and is never on consecutive days.
- **Claude on rest days:** Body quests are eased to Easy. A Hard quest on
  the muscle group trained hard yesterday is eased to Normal.
- **Claude's daily quests:** they join the Daily Quest. Template quests are
  replaced only by explicit `remove` ops; the Project instructions now ask
  Claude to send them.
- **Awakening defaults:** rest day Saturday, wake 06:30, quiet hours
  22:30–06:30, reset 04:00. You change them in the Rhythm step.

**Open issues**
- There's no Settings screen yet. The reset time, rest days, quiet hours and
  Dossier notes can only be set in the Awakening. That makes Task 28's
  phone check awkward. A small Settings screen should come before the exit
  week.
- Check-off quests show "1 done" as their target. It's cosmetic.
- `realm_*` and `tier_*` strings are duplicated in `:feature:quests` and
  `:feature:console`. They're identical, so the merge is harmless.
- The phone checks listed at the top of TODO.md are still open.

---

## 2026-11-14 — Phase 3 spec merged, TODO written

**Changed**
- SPEC.md: merged from `SPEC_UPDATE_PHASE3.md`:
  - the new "Depth mechanics (Phase 3)" section, before the AI layer;
  - the Phase 3 roadmap line;
  - the Draft Wyrm milestone (now the hard-rod current fluctuations work).

  The update file was based on the pre-Phase 2 SPEC. The Phase 2 rules were
  kept: D5 starting stats, mastery per tier, the 3–5 objective Daily, the
  Sunday–Saturday week, Claude's tier and `recurring` defaults, and the
  baseline targets. Apart from those, SPEC.md now matches the update file.
- TODO.md: rewritten for Phase 3 (37 tasks in the SPEC build order).
  Phase 2's Task 40, its phone checks and the Phase 2 rules still awaiting
  confirmation are carried over at the top.

**Decisions**
- None made yet. D1–D13 in TODO.md are open, each with a recommendation.
  The conflicts found:
  - Penalty and Emergency quests are in the roadmap but not in the build
    order.
  - The Monday Gate board doesn't match the Sunday–Saturday week.
  - Boss phases: the overview says 66%/33% HP; the Phase 3 section says
    finishing a phase.
  - Item sources differ between the overview and the achievements table.
  - AI-written lore conflicts with the no-AI-calls rule.
  - Some gear effects depend on Phase 4 systems.
  - Several achievements need Phase 5 data.

**Open issues**
- Phase 3 shouldn't start until the Phase 2 exit week is done.
- `SPEC_UPDATE_PHASE3.md` is now redundant. It's yours to keep or delete.
- `docs/ASCENT-System-Project-Instructions.md` is unchanged. It moves to
  block v2 in Tasks 20 and 35.

---

## 2026-11-14 — Phase 3 decisions D1–D13

Walked through D1–D13 one at a time. Every answer took the recommendation.

**Decisions**
- D1: no new dependencies or plugins.
- D2: Penalty and Emergency quests are built in Phase 3 with side quests.
  - Penalty: +10 HP back; the streak stays reset.
  - Emergency: about 2 per week inside active hours, a 2 h window.
  - A new drop-table row for Emergency quests: a 20% roll, Rare 85 /
    Epic 15.
- D3: the Gate board spawns at the Sunday reset, not Monday.
- D4: normal quests count toward Gates. Targets are the normal-week amount
  × (1 + rank extra).
- D5: the board uses your stored rank; at E it offers E, E and D.
- D6: the Phase 3 section wins, and I edited the overview to match:
  - boss phases unlock by finishing a phase, not at 66% and 33% HP;
  - the old fixed rewards are now flavour examples;
  - the item sources follow the achievements table;
  - side quests pay 0.75× XP.
- D7: Insight and cooldown affixes don't roll until Phase 4. Named items
  and sets that use Phase 4 systems drop with that part dormant.
- D8: until Phase 5, achievements count logged quests, and counters
  backfill from history.
- D9: lore is hand-written JSON. I draft about 25 lines for your review.
- D10: four tabs (Status · Quests · Console · Gates). New module
  `:feature:inventory`, added to CLAUDE.md's module list.
- D11: the bridge v2 rules:
  - ids `a1`, `b1`, `t1`, `gt1`;
  - `complete` accepts boss-task ids;
  - v1 still accepted;
  - STATUS v2 contents.
- D12: the avatar is drawn in Canvas with slot overlays. Sounds are CC0
  files that you pick; until then, haptics only.
- D13: the small rules (side-quest expiry, Gate fraction, the Red Gate's
  timing, the boss hours clamp, Trial pay, dismantling, global upgrade
  caps, SplitMix64 with a per-install seed, Ergodic and Equipartition) are
  starting values.

**Changed**
- SPEC.md:
  - overview: the quest table, the Gate and Boss notes, the equipment
    sources and the lore codex;
  - Phase 3 section: the quest structure rows, arc completion, the side
    quest rules, a new penalty and emergency subsection, Gate progress and
    rank, the payout fraction, the Red Gate's timing, boss limits and
    Trials, the Emergency drop row, the seed and generator, dormant
    affixes, dismantling, the avatar, achievement definitions and data
    sources, the bridge v2 ids, and a short screens and sound subsection;
  - build order steps 3 and 5.
- CLAUDE.md: `inventory` added to the feature module list.
- TODO.md: D1–D13 ticked; Task 21 now says the Sunday reset.

**Open issues**
- Phase 3 still waits for the Phase 2 exit week.
- `docs/ASCENT-System-Project-Instructions.md` stays at v1 until Task 35.

---

## 2026-11-14 — Lore and sound assets (ahead of Tasks 5 and 36)

You asked me to write the lore, to let Claude write lore when it's needed
during play, and to pick and download the sounds.

**Changed**
- `docs/lore-bible.md`: the canon (lattice, ground state, excitation,
  Observer, fluctuations, fixed points, Trials, Essence, Σ), the voice rules,
  a word denylist, and where lines appear. Meant to be added to the Claude
  Project as a file as well.
- `core/data/src/main/assets/content/lore.json`: 35 original seed lines, as
  JSON v1 with unlock kinds:
  - the Awakening and levels 5, 10 and 20;
  - a chapter completed, and arc ends per realm;
  - the Iron Discipline and Quiet Hours finales;
  - Gate breaks per realm, and a Red Gate cleared;
  - boss phases 2, 3, 4 and final; boss defeat, stirs and enrage;
  - a Trial won or failed;
  - the 12 achievements.

  Checked: unique ids, every line at most 200 characters, and the denylist
  is clean. Task 3 may adjust the schema.
- SPEC.md:
  - bridge v2 gains a sixth action, `add_lore`: Claude writes lines for
    your own arcs, bosses and Gates, applied as cards and frozen once
    unlocked;
  - the lore codex, data model and build-order text now mention it;
  - the sound line names the files.
- Sounds:
  - Seven Kenney CC0 sounds are in `core/designsystem/src/main/res/raw/`
    (about 120 KB): `sfx_system_chime`, `sfx_quest_complete`,
    `sfx_level_up`, `sfx_penalty`, `sfx_boss_hit`, `sfx_chest_open` and
    `sfx_unlock`.
  - 14 alternates are in `docs/sounds/alternates/` (not packaged).
  - Sources and licences are in `docs/sounds/SOUNDS.md`.
  - kenney.nl refused automated downloads (403), so the files come from
    Kenney's own CC0 uploads on OpenGameArt.
- CLAUDE.md: the project files table lists the lore bible and SOUNDS.md.
- TODO.md: notes on D9, D12 and Tasks 5, 34 and 36.
- `./gradlew assembleDebug` passes. The APK contains `assets/content/lore.json`
  and the seven `res/raw` files.

**Decisions**
- Lore comes from two sources: seed lines are hand-written in the repo, and
  Claude writes player-specific lines through `add_lore`. The app still
  makes no AI calls.
- The Digital SFX files are MP3 (SoundPool plays MP3); the rest are OGG.

**Open issues**
- I picked the sounds by name and length without hearing them. You need to
  audition them.
- The lore needs your review.
- `add_lore` goes into `docs/ASCENT-System-Project-Instructions.md` with
  the rest of v2 in Task 35. Until then, Claude can use the lore bible for
  flavour in its replies only.

---

## 2026-11-15 — Phase 3 Tasks 1–36 in one batch

You asked for sections A to L in one batch, which overrides "one task per
request" for this session. Phase 2's exit week hadn't happened yet; you chose
to go ahead.

**Changed: engine** (168 tests, 44 new)
- `Rng.kt`: SplitMix64 and `Seeds` (an FNV-1a event key mixed with a
  per-install seed).
- `Loot.kt`: rarities, slots, affixes, drop tables, `LootRoller`, and
  `GearRules` (upgrades, Essence, folding gear into effects, set bonuses,
  dormant Phase 4 parts).
- `RewardCalculator`: gear effects held under the caps (+30% XP per realm,
  25% total crit); tags on `xp_mult` and `mastery_mult`; quest-kind
  multipliers.
- New effects: `hp_regen`, `gate_reward`, `chest_luck`.
- New rule files: `SideQuests.kt` (side, penalty, emergency), `Arcs.kt`,
  `Gates.kt`, `Bosses.kt`, `Achievements.kt`.
- `Content.kt`: content models, `ContentCodec`, `ContentValidator`, and
  `LoreRules` (200 characters, franchise denylist).
- `ContentTest` validates the shipped JSON files.

**Changed: content** (`core/data/src/main/assets/content/`)
- `loot.json`: the SPEC tables plus the Emergency row.
- `items.json`: 20 hand-written items and 3 sets.
- `gates.json`: 9 Gates, 3 per realm, 3 objectives each.
- `quests.json`: 4 penalty and 7 emergency tasks.
- `achievements.json`: the 12 achievements.
- `arcs.json`: the 4 SPEC arcs.

**Changed: data** (34 tests)
- Room v2 with 13 new tables.
- New columns:
  - player: essence, rollSeed, lastBoardWeek;
  - quest: kind, expiresDay, expiresAtMillis, xpFactor, completedDay,
    lootItemId, hpAwarded;
  - goal: objectiveId.
- `MIGRATION_1_2`:
  - additive only, with SQL copied from the exported `2.json`;
  - fills `completedDay` for done Phase 2 quests (for backfill);
  - gives existing players a random roll seed.
- `MigrationTest`: the exact schema match, plus a v1 database full of
  Phase 2 rows that all survive.
- New repositories: Content, Inventory, Lore, Achievement, Arc, Boss, Gate,
  SideQuestService.
- `QuestRepository.complete`: gear, tags, side and emergency factors, loot,
  and penalty HP. `DailyCycle`:
  - Gate expiry and the Sunday board;
  - boss stirs and enrage;
  - a missed penalty (−10 HP) and gear HP regen;
  - balance and penalty quests at the reset;
  - lore for levels;
  - achievements after every run.
- `Phase3DataTest`: 12 flow tests (board, Gates clear and break, side,
  penalty, boss defeat with custom lore, limits, Trials, arcs, gear,
  emergency).

**Changed: bridge** (28 tests)
- Block v2: side quests and `expires_days`, plus `add_arc`, `add_gate`,
  `add_boss`, `add_boss_task` and `add_lore`. `complete` takes t… ids.
  v1 still reads.
- Validator: the Gate rank is corrected from measured effort; the Body cap,
  the Gate, boss and side limits, the hours clamp, phases, and lore rules
  are all checked.
- STATUS v2 adds Side quests, Arcs, Gates and Bosses sections.
- `docs/ASCENT-System-Project-Instructions.md` is updated to v2 in the same
  task, and SPEC notes it.

**Changed: UI**
- Design system:
  - `RarityColors`, `ChestRevealOverlay`, `SystemNoticeOverlay`;
  - `BossHpBar` (6 dp shake, the chunk breaks off);
  - `AvatarFigure` (Canvas, slot overlays, Mythic aura);
  - `ObjectiveProgress`;
  - `SystemSounds` (SoundPool plus haptics; silent on silent);
  - Previews in `Phase3Previews.kt`.
- Screens, each with Previews:
  - Status: avatar, plus links to Inventory, Codex and Settings.
  - Settings: rhythm, rest days, Dossier, sound, haptics.
  - Quest Log: penalty and emergency cards, a Side section with bounties,
    and an Arcs tab with templates, logging, goal linking and an arc form.
  - New `:feature:gates`: the board, open Gates, bosses, Trials, and the
    Boss Arena.
  - New `:feature:inventory`.
  - New `:feature:codex`.
  - Console: cards for the six new actions.
- App:
  - four tabs and new routes;
  - chest and notice overlays queued after the level-up;
  - sounds per event;
  - `EmergencyWorker`;
  - reset notices posted as notifications;
  - a new notification channel (`system_v2`) with the chime as its sound;
  - the `VIBRATE` permission (install-time, no prompt).

**Decisions (new rules SPEC doesn't define: please confirm or change)**
- **Boss tasks pay XP:** 0.5 × damage × (1 + 0.05 L), ×2 on a crit, −25%
  when enraged. SPEC gives tasks XP but no amount.
- **The first board** spawns at once for a player who has never had one;
  after that, at each Sunday reset.
- **Body headroom** on the board is judged at the Sunday reset against last
  week's cap. Mid-week, a template already at its cap leaves no room, so a
  first mid-week board may show only Mind and Self Gates.
- **Claude's Gates:** the rank is measured against a normal week of matching
  templates. Frequencies: strength 1.5, cardio 2, mobility 1, deep work and
  sleep 7, Mind outputs and Self habits 3.5. Objectives the app can't
  measure keep Claude's rank.
- **Undo** isn't offered for a completion that dropped an item.
- **Gear details:**
  - HP regen restores that fraction of max HP at each reset.
  - A Rare or Epic drop is a named item 25% of the time.
  - Set pieces are Epic.
  - The Hamiltonian Blade is +10% Mind XP plus a unique +5% (SPEC's 15%),
    and its free Insight is dormant.
  - Equip with no slot chosen: an empty matching slot first.
- **Trials:** a win pays Hard-tier mastery (20) to the template's stat.
  Hard-Rod Gauntlets double it.
- **Equipartition** is earned at once when starting stats are all equal.
- **Generic lore lines** (Gate break, boss phases) show as a notice every
  time, but enter the Codex once.
- **Bounties** count toward the three-visible limit.

**Open issues**
- None of this has run on a phone yet; the checks are listed at the top of
  TODO.md.
- The bridge tests use replies written from the documented format. Add real
  Claude replies to `BridgeV2Test`.
- A preview render showed a stale-class error for the Quest Log; the Gradle
  build is green, so it's the IDE preview cache.
- `equippedTitle` from the plan became the existing `title` column plus an
  `earned_title` table.
- Task 37 (exit review plus the real-week exit) and the Phase 2 exit week
  are still open.

---

## 2026-11-15 — Phase 4 Task 0: SPEC amendments, and the Phase 4 TODO

**Changed**
- SPEC.md: the amendments A1–A16 are written in where they apply:
  - the bonus cap (A10, A11, A16) after the quest XP formula;
  - the class bonus (A13), and class choice and re-choice (A5, A14);
  - Overload in the class table (A2);
  - the Observer tree shown locked (A15);
  - the gate stat (A4);
  - in the skill table: Momentum and Focus Chain (A3), Limit Break (A1),
    Unbroken and Streak Shield (A9), Hyperfocus (A6), Insight and Mentor's
    Eye (A7), Eigenstate Collapse (A8);
  - the Phase 4 gear effects (A12);
  - a pointer to `PHASE4.md` in the roadmap.
- TODO.md: rewritten for Phase 4, 29 tasks from PHASE4.md section 11. Task
  0 is ticked. Phase 3's Task 37 and phone checks, and the Phase 2 exit week,
  are carried over.

**Decisions** (approved 2026-11-15)
- A1–A10 as written in PHASE4.md.
- A11–A16, which I found while checking PHASE4.md against SPEC:
  - A11: the ×1.75 cap applies to the added gear, skill and class bonuses
    (+75%). The streak multiplier stays separate, so Phase 3 gear isn't cut
    back for long streaks.
  - A12: the dormant Phase 3 gear wakes up. Insight charges add to
    1 + ⌊WIS/20⌋; Iron Lattice gives one extra shielded miss per month;
    Regalia's −25% stacks with FOC, together at most −40%.
  - A13: a class also gives +10% mastery to its two stats.
  - A14: class re-choice at each rank-up, decided before any evolution.
  - A15: the Observer tree is shown locked with an "A-rank" hint.
  - A16: Overload's ×2 sits on top of the cap, like the single-target
    powers; crit is applied last.

**Open issues**
- Clarity's journal text stays on the phone, but the quests it creates
  appear in STATUS by their titles.
- Limit Break has no headroom in a template's first week, or after its
  target has already risen to the weekly cap.
- Phase 4 starts only after the Phase 3 exit week (SPEC); Task 1 is next.

---

## 2026-11-15 — Phase 4 section B (Tasks 1–3): content

You asked for sections B to N as sequential batches, each checked before I ask
to go on. This is section B.

**Changed**
- Engine:
  - `ProgressionContent.kt`: models for skills (tree, tier, kind, prerequisites,
    MP, limit, effects, handler, locked), classes (stats, evolutions, bonus,
    unique skill), rank trials and the avatar. Also `PowerHandlers` and
    `KnownTags`.
  - New effect kinds, as data only (rules come in section C): `chain_xp`,
    `rest_hp_mult`, `dual_mastery`, `streak_shield`, `tag_hp`, `rest_restore`,
    `insight_charges`, `cooldown_reduction`, `resonance`.
  - Lore: new unlock kinds (rank, class, evolution stage, skill, and others),
    and a `LoreCategory` for the Record filters. Each line's category comes
    from its kind unless the JSON sets one.
  - `ProgressionValidator`, part of `ContentValidator`, checks:
    - tree shapes and prerequisite chains;
    - that actives have handlers and limits;
    - the six classes with the A13 bonus;
    - that rank trials match SPEC's levels, the Gate efforts and the chests;
    - avatar coverage;
    - that every Phase 4 lore trigger has a line.
- Content (`core/data/src/main/assets/content/`):
  - `skills.json`: 21 realm skills plus 3 locked Observer skills.
  - `classes.json`: 6 classes.
  - `rank_trials.json`: D to S, with 4 objective templates per realm.
  - `avatar.json`: an aura token per rank, and cloak and sigil ids per
    class.
  - `lore.json`: +33 lines from the PHASE4.md appendix (68 in total).
- `docs/lore-bible.md`: the Phase 4 triggers added to "Where lines appear".
- Tests: `ProgressionContentTest` (10 new). `./gradlew test assembleDebug`
  passes with 250 tests: engine 178, data 34, bridge 28, other modules 4.

**Decisions**
- Lore ids carry the `lore_` prefix used by the Phase 3 lines (for example
  `lore_rank_d`).
- Avatar colours are tokens (`AuraToken`), not hex, so feature code stays
  hex-free.
- Gear for A12 (Insight charges, Iron Lattice, Regalia) is still flagged
  dormant in `items.json`; Task 7 switches it on together with the rules.

**Open issues**
- Section C (engine, Tasks 4–9) is next.

---

## 2026-11-15 — Phase 4 section C (Tasks 4–9): engine rules

**Changed** (pure Kotlin; 197 engine tests, 19 new)
- `Periods.kt`:
  - day, week and month keys from the game day;
  - uses per period;
  - hour cooldowns, with reduction from FOC plus gear, at most 40% (A12).
    Per-period limits are never reduced.
- `SkillRules.kt`:
  - points: one per even level, plus one per boss;
  - unlock checks, giving the reason when one fails: prerequisite, gate stat
    (the higher of the tree's two, A4), boss, points, locked;
  - respec, with a token first, else 50 Essence;
  - passive effects.
- `RewardCalculator`:
  - skill and class bonuses plus gear, together at most +75% (A10, A11);
    gear stays within +30% per realm;
  - the streak multiplier stays separate;
  - `powerMultiplier` sits on top (A16), and the crit is applied last.
- `PassiveRules.kt`:
  - chains, with rest days neutral (A3);
  - Momentum and Focus Chain bonuses, and Resonance;
  - Literature Sense's dual mastery, Bonds' HP, Recovery Aura;
  - Equilibrium;
  - Insight charges (1 + ⌊WIS/20⌋ + gear) and free charges.
- `PowerRules.kt`:
  - cast checks: unlocked, MP, limit or cooldown, rest day, headroom,
    target, a miss to restore;
  - Limit Break's target, within the week's cap (A1);
  - multipliers: Limit Break and Collapse ×3, Hyperfocus and Overload ×2;
  - Stillness and Long Walk trigger by themselves.
- `ShieldRules`: the weekly shield first, then Iron Lattice's monthly
  extra (A9, A12); Unbroken only the day after an unshielded miss.
- `HyperfocusRules` and `ClarityRules`.
- `ClassRules.kt`: scores, the top two scores with ties, Polymath (A5),
  awakening, stages and names at B and S, effects (A13), and re-choice
  validity (A14).
- `RankTrialRules.kt`:
  - availability, one rank at a time, and the retry wait;
  - per-realm effort: Body up to its headroom, the rest split over Mind and
    Self, keeping 3 × effort in total;
  - 2 seeded objectives per realm;
  - pass and fail, fail XP, and the retry day.
- `items.json` (A12): the Hamiltonian Blade's free Insight, both lenses'
  +1 charge, Iron Lattice's monthly shield and Observer's Regalia's −25%
  Hyperfocus cooldown now carry real effects and are no longer dormant.
- Tests:
  - `PeriodsAndSkillsTest`: resets at 04:00 and 05:30, the Sunday and month
    boundaries.
  - `CapPropertyTest`: 5,000 random gear, skill, class and streak mixes stay
    under ×1.75 and +30%; 5,000 Limit Break cases stay within the cap.
  - `PowerAndClassTest`: two misses in a week, classes, rank trials.
  - Updated: one `LootTest` and one `Phase3DataTest` assertion (the blade is
    no longer dormant).
- `./gradlew test assembleDebug`: 263 tests (engine 197, data 34, bridge 28,
  other modules 4).

**Decisions**
- Class choices are every class holding one of the two highest scores, so a
  tie can show three or more. Polymath is added when eligible.
- Rank-trial efforts: Body takes min(effort, headroom); Mind and Self each
  take (3 × effort − Body) / 2. Total effort stays 3 × effort.
- Insight's free charges (Hamiltonian Blade) are spent before paid ones.
- Clarity splits the third line on `;`, `,` and "and", drops forbidden
  items, and keeps at most three.
- Insight and Cooldown affixes still don't roll on random drops; only the
  named items carry them. Rolling them can come later if you want it.

**Open issues**
- Section D (Room v3, repositories, daily cycle, Tasks 10–11) is next.

## 2026-11-15 — Phase 4 section D (Tasks 10–11): data

**Changed**
- Room v3 and `MIGRATION_2_3`. The migration is additive only.
  - player:
    - class: `classId`, `classChosenAt`, `classRechoice`;
    - `respecTokens`, `bonusSkillPoints` (debug panel);
    - Shield counters per week and per month;
    - `lastMissDay` and `lastMissShielded`, for Unbroken;
    - `equilibriumDay`.
  - New tables:
    - `player_skill`, with an `unlocked` flag so a respec keeps the use
      record;
    - `power_cast`, with an index on `status`.
  - Other new columns:
    - quest: `tags`;
    - gate: `kind`, `targetRank`, `attempt`, `retryAt`;
    - lore unlock: `category`, `readAt`.
  - `gate.kind` is set to `RED` where `isRed`; old lore entries are marked
    read.
- `MigrationTest` gains two cases:
  - 2 → 3 matches the exported `3.json`;
  - a v1 → v2 → v3 database full of Phase 3 data keeps every row.
- New repositories:
  - `SkillRepository`: points, unlock, respec, and the Phase 4 effects
    (passives, class bonus, Resonance).
  - `ClassRepository`: the choices, choosing, keeping at a re-choice, and
    evolution lore.
  - `PowerRepository`:
    - the cast check (with the target each power accepts), MP, the period
      use record, and the `power_cast` log;
    - first-cast lore;
    - power multipliers on completion;
    - a refund when the target is removed the same day;
    - restore on undo; expiry at the reset.
  - `RankTrialRepository`:
    - opens a trial once you qualify (including players already past a
      threshold), with 2 seeded objectives per realm, Body within the cap;
    - passes early at 100%, or closes at expiry;
    - pass: rank, chest, respec token, lore (plus the A-rank glimpse and the
      Σ hint) and the class re-choice;
    - fail: "Measurement noise" and the 7-day retry.
- `QuestRepository.complete`:
  - skill and class passives under the +75% cap: chains with rest days
    neutral, Resonance, Literature Sense's second stat, Bonds;
  - Equilibrium: finishing the rest-day Daily fills HP and MP and gives
    +10% XP the next day;
  - power multipliers on top of the cap;
  - quest tags;
  - the trial clear check after each completion.
- Undo reverses the second stat's mastery, clears Equilibrium's next day and
  puts used-up casts back. `remove` refunds a same-day cast.
- `DailyCycle`:
  - Streak Shield (week first, then Iron Lattice's monthly), with the miss
    recorded for Unbroken;
  - Recovery Aura (×1.5 rest-day HP);
  - cast expiry;
  - trial close and open.
  - `SideQuestService` issues no penalty quest after a shielded miss.
- `NewQuest.tags` keeps only known tags. Repeating quests keep their tags.
- `LoreRepository`:
  - stores each line's Record category;
  - new: `unlockOnce`, `markRead`, `unreadCount`.
- `GateRepository.bodyHeadroomFor` is shared by the board and the trials.
- Six new notice kinds: skill acquired, class awakened, rank trial open,
  rank up, rank trial closed, streak shielded. Their strings are in
  `strings.xml`.
- New tests: `Phase4DataTest` (9 Robolectric flows).
- `./gradlew test assembleDebug`: 274 tests (engine 197, data 45, bridge
  28, other modules 4).

**Decisions**
- **Skill points are computed, not stored.** Points = ⌊level / 2⌋ +
  bosses defeated − spent + `bonusSkillPoints`. Bosses beaten in Phase 3
  count at once, so no backfill write is needed. The old `skillPoints`
  column is left alone.
- **A passed trial also pays XP**, by the same Gate payout rule as a fail
  (at 100% cleared). PHASE4 lists XP only for a fail; a pass paying nothing
  for 100% when a fail pays for 60% looked like an omission. Say if a pass
  should pay no XP.
- **Re-choice comes before evolution (A14).** After a B or S pass, the
  evolution lore fires when you choose or keep a class. Keeping the current
  class is always allowed at a re-choice.
- **Equilibrium applies at completion**, not at the reset. MP already
  refills at every reset, so "fully restores MP" only means something at
  once. Undo doesn't take the MP back.
- **Respec keeps each skill's use record**, so cooldowns and period uses
  are not reset. It locks every tree skill; class skills stay.
- **Rank trials span every realm.** The `realm` column holds BODY as a
  placeholder; each objective's templates carry the real realm.
- **Power handlers aren't built yet.** A cast is logged and its multiplier
  is paid, but the handlers' own effects (Second Wind's swap, Limit Break's
  new target, the shares) come in section H.

**Open issues**
- A trial opens on the next `ensureUpToDate` (each app open and reset), not
  at the moment of the level-up.
- Next: section E, the debug panel (Task 12).

## 2026-11-15 — Phase 4 section E (Task 12): debug panel

**Changed**
- The `TimeSource` binding moved from `:core:data` to `:app`, per build
  type:
  - `src/release`: `SystemTimeSource`, plus empty `DebugMenuEntry` and
    `DebugPanelRoute` stubs;
  - `src/debug`: `DebugClock`, the real clock plus an offset saved in
    `ascent_debug` prefs.
- `:core:data` `src/debug`: `DebugActions`.
  - Raise level (+1, +5, +10; XP is lifted to that level's threshold).
  - Set rank; stats ±5; fill or empty MP.
  - Grants: +1 skill point (`bonusSkillPoints`), +3 free points, +50
    Essence, +1 respec token.
  - Offer the class re-choice; clear the open rank trial.
- `app/src/debug`: `DebugPanelViewModel`, `DebugPanelScreen` (with a
  Preview) and debug-only strings.
  - Fast-forward +1 day or +1 week, then run the daily cycle.
  - Raising the level also runs the cycle, so level lore and a due rank
    trial appear at once.
- Settings gained an `extra` slot at the bottom. The app fills it with the
  debug entry; in release the slot is empty.
- Checked: the release APK has no `DebugPanelViewModel`, `DebugActions`,
  `DebugClock` or debug strings. The debug APK has all of them.
- `./gradlew test assembleDebug` and `:app:assembleRelease` pass. 274 tests,
  unchanged.

**Decisions**
- **Debug code lives in `debug` source sets, not behind `BuildConfig.DEBUG`.**
  It is then absent from release builds, not just unreachable.
- **Debug actions sit in `:core:data`.** Room isn't on the app's classpath,
  and putting them there avoided adding a dependency.
- **The clock only moves forward.** Going back would leave game days in the
  future of the clock. To start over, reinstall the debug build.
- **Levels only go up, even in the panel**, keeping "the player never loses
  a level". Rank can be set freely, for testing evolutions.

**Open issues**
- Next: section F, the Skills tab (Task 13).

## 2026-11-15 — Phase 4 section F (Task 13): Skills tab

**Changed**
- New module `:feature:skills`, listed in CLAUDE.md. No new dependencies.
  - `SkillGraphLayout`: Body, Mind and Self as columns, each with two
    chains of three, the ultimate centred below, and the Observer tree at
    the right edge. Covered by `SkillGraphLayoutTest` (3 tests).
  - `SkillGraph`:
    - drag to pan, pinch to zoom (×0.5–×1.6);
    - glowing cyan links between held skills;
    - nodes: cyan when held, gold when ready to unlock, dimmed when locked;
    - Observer nodes show "?" and "A-rank" only (A15);
    - each node is a 112×76 dp button with a content description.
  - `SkillsScreen`:
    - skill points, the class skill, and respec with its cost (token or
      50 Essence) behind a confirm dialog;
    - tree chips that jump the view, plus Reset view;
    - a node bottom sheet: kind, tier, description, MP and limit, the
      one-line reason it can't be unlocked yet, and Unlock;
    - for held actives, the cast check and Cast, with a second tap to
      confirm.
  - `SkillsViewModel` with one `SkillsUiState`. Previews: the screen, a
    locked-node sheet, a cast sheet.
- `SkillRepository.overview`: every skill's state in one call (flow and
  suspend), plus `SkillStatus` and `RespecCost`.
- The app gained a fifth tab: Status · Quests · Console · Gates · Skills
  (glyph ✦).
- `./gradlew test assembleDebug`: 277 tests (engine 197, data 45, bridge
  28, skills 3, other modules 4).

**Decisions**
- **Casting from the sheet is untargeted only for now.** Targeted powers
  say "Cast it on a quest from the Quests tab". Section G adds that path,
  the confirm card and the sigil animation; until then a two-tap confirm
  guards MP.
- **Unlock notices reuse the System notice overlay** ("Skill acquired").

**Open issues**
- The header panel takes about a third of a phone screen. If the graph
  feels cramped on the phone, it can move into a collapsible strip.
- Next: section G, casting (Task 14).

## 2026-11-15 — Phase 4 section G (Task 14): casting

**Changed**
- `PowerRepository`:
  - `optionsFor(target)`: the held powers that take a quest or a boss
    task, each with its check, MP cost and Limit Break's new target.
  - `WIRED`: only powers whose handler is built can be cast. The others
    say "Not available yet", so MP is never spent for nothing. Built now:
    Eigenstate Collapse and Overload; section H adds the rest.
  - Eigenstate Collapse on a boss task pays ×3 XP (damage unchanged), and
    undoing the hit puts the cast back.
  - Every cast emits `GameEvent.PowerCast`.
- Design system:
  - `SigilRing`, and `CastSigilOverlay`: the ring draws in 600 ms, a
    triangle locks inside, the name shows, then it fades. It never blocks
    touches, and is skipped with reduced motion.
  - `CastConfirmCard`: cost, effect and target, or the one-line reason
    in place of Confirm.
- `:feature:skills`:
  - `CastPickerSheet`, used by Quests and Gates;
  - `CastTexts`, the shared reason and cost lines.
- Quests tab:
  - long-press an open quest to open the picker; TalkBack gets a "Cast
    a power on this quest" action;
  - cards show "◈ …" for powers active on them.
- Boss Arena: the same long-press and TalkBack action on an unlocked task.
- App shell:
  - the sigil ring plays after any cast;
  - sounds: cast, skill unlocked, class awakened, rank up.
  - A cast from the Skills sheet closes the sheet so the ring shows.
- Sounds: four picks copied from the CC0 alternates already downloaded
  (nothing new was fetched). They are listed in `docs/sounds/SOUNDS.md`:
  `sfx_cast` (zapThreeToneUp), `sfx_skill_unlock` (maximize_001),
  `sfx_class` (glass_001), `sfx_rank_up` (threeTone2).
- Tests:
  - the cast tests are split: limit and payout, same-day refund, and
    Overload ×2 with reset expiry plus an unbuilt power refused;
  - Unbroken's check is now `NotCastable` until Task 20.
- `./gradlew test assembleDebug`: 279 tests (engine 197, data 47, bridge
  28, skills 3, other modules 4).

**Decisions**
- **Quests and Gates depend on `:feature:skills`** for the shared picker.
  The design system has no engine dependency, so engine reasons can't be
  turned into text there. One feature depending on another is a small
  smell; it can move to a `:core:ui` module if more sharing appears.
- **The ring plays centred on screen, not around the card.** It runs in
  the app shell for every cast source. Drawing it around the card itself
  would need each card's position.
- **A refund never takes MP above max.**

**Open issues**
- Next: section H, the powers in order (Tasks 15–20).

## 2026-11-15 — Phase 4 section H (Tasks 15–20): powers

**Changed**
- New `PowerEffects` (data). It casts through `PowerRepository` and applies
  each effect in the same transaction; if the effect can't land, the cast
  rolls back and no MP is spent.
  - **15. Second Wind:** the Body daily keeps its place and still counts,
    but becomes Mobility 15 min (Easy) from the `body_mobility` template.
    The original quest is kept in the cast note. Refused on rest days.
  - **16. Clarity:**
    - three lines, kept in the cast note: on the phone, never in STATUS;
    - `ClarityRules` turns the third line into up to 3 extra quests for
      tomorrow: the matching template's realm and stat, else Mind / INT,
      Normal, target 1;
    - `journal()` lists past entries.
  - **17. Limit Break:** the quest rises to `limitBreakTarget` (within the
    week's cap, A1). The old target is in the note. ×3 on completion.
  - **18. Insight and Mentor's Eye:** the cast only; the Skills screen then
    opens the share sheet.
    - Insight sends `Request: Stuck: …`; its charges are
      1 + ⌊WIS / 20⌋ + gear.
    - Mentor's Eye sends `Request: Review` with `Last 28 days:`.
  - **20. Unbroken:**
    - yesterday's missed objectives become done at 50% XP;
    - the streak is restored (the day before's streak + 1), +15 HP, and
      misses are reset;
    - today's penalty quest is removed, and yesterday's snapshot is marked
      complete.
  - `WIRED` now has every handler except Hyperfocus (Task 21) and First
    Principles and Parallel Runs (Task 23).
- **19. Eigenstate Collapse:**
  - the Quests tab (Today and Week) shows only the target until it's done,
    with a "Show all" button;
  - the skill sheet has "Ask the System which one", which shares
    `Request: Collapse: which one task?` without casting (A8).
- Bridge:
  - `PowerRequests` (REVIEW, COLLAPSE, `stuck()`);
  - `StatusSnapshot.historyDays` and `statusText(request, historyDays)`.
- `docs/ASCENT-System-Project-Instructions.md`: the `Last 28 days:` note
  and the Collapse request. The fuller STATUS v3 update is Task 28.
- Skills screen: Clarity and Insight input dialogs, and the share sheet.
  `:feature:skills` now depends on `:integrations:bridge`.
- Quests and the Boss Arena cast through `PowerEffects`.
- Tests:
  - 5 new flows: Second Wind (including the rest-day refusal), Limit Break
    (within the cap, ×3), Clarity (3 quests tomorrow, journal, daily
    limit), Insight charges plus Mentor's Eye weekly, Unbroken (streak, HP,
    XP, penalty, snapshot, monthly limit);
  - the STATUS test checks the 28-day header and the Review and Stuck
    lines.
- `./gradlew test assembleDebug`: 284 tests (engine 197, data 52, bridge
  28, skills 3, other modules 4).

**Decisions**
- **Second Wind edits the quest in place** rather than adding a new one.
  The Daily keeps 3–5 objectives, and any link to a Gate or goal stays.
- **Clarity's journal lives in the cast's note**, which avoids a schema
  change. STATUS never reads cast notes.
- **Clarity's quests default to Mind / INT, Normal**, unless a template
  matches the title.
- **Unbroken doesn't rerun the difficulty controller** for yesterday; only
  the streak, HP, XP, penalty and snapshot change.
- **Eigenstate Collapse on a boss task doesn't collapse the Quests tab.**
  Only quest targets collapse it.

**Open issues**
- If an effect rolls back (its target vanished mid-cast), the sigil ring
  may still play once. Rare and harmless.
- Next: section I, the Focus Session and Hyperfocus (Task 21).

## 2026-11-15 — Phase 4 section I (Task 21): Focus Session and Hyperfocus

**Changed**
- Hyperfocus is castable (`WIRED`): 30 MP, a 12 h cooldown (FOC
  shortens it), cast on an open Mind quest by long-press.
- `PowerEffects.focusSession`: the running session. It's an active
  Hyperfocus cast with no note yet, so it survives restarts and expires at
  the reset.
- `PowerEffects.endFocus`:
  - **Full 90 min:** the note becomes `full` and the cast stays active.
    Untimed quests and quests of 90 min or less complete at once; a longer
    timed quest gets the bonus when you tick it today.
  - **Ended early:** the note becomes `m=NN` and the cast completes. If the
    minutes cover a timed quest's target, it completes without the bonus.
    MP isn't refunded.
- `PowerRepository.consumeBoost` replaces `consumeMultiplier`, returning
  XP and mastery multipliers. A full Hyperfocus gives ×2 XP and ×2
  mastery when the quest's stat is INT.
- Focus Chain counts early-ended session minutes per day
  (`PowerRepository.focusMinutes`). A full session's minutes count through
  the quest it completes.
- `FocusSessionHost` and `FocusSessionScreen` (`:feature:skills`), placed
  in the app shell:
  - a full-screen dark timer with a pulsing ring (static with reduced
    motion), mm:ss and the linked quest;
  - End early, behind a confirm ("the minutes count, without the bonus;
    MP isn't refunded");
  - Claim at 90:00;
  - touches don't reach the app beneath.
- Do Not Disturb (`FocusDnd`):
  - asked once, at the first session (then never again);
  - on while a session runs if access is granted, and your previous mode
    is restored after, even across a restart;
  - sessions work the same without it;
  - manifest: `ACCESS_NOTIFICATION_POLICY`.
- Settings DataStore: `focus_dnd_asked`, `focus_dnd_previous`.
- Tests: a full session (quest done, ×2 XP, ×2 INT mastery, cooldown), and
  an early end (40 min counted for Focus Chain, quest still open, MP kept
  spent). The unbuilt-power check now uses First Principles.
- `./gradlew test assembleDebug`: 286 tests (engine 197, data 54, bridge
  28, skills 3, other modules 4).

**Decisions**
- **"2× INT mastery" is read literally:** it doubles mastery on INT quests
  only. A FOC deep-work quest gets ×2 XP and normal mastery.
- **No progress counter on quests.** A full session completes its quest
  (if 90 min or less) rather than adding "+90 min" to a partial total.
  Quests have no partial progress, and shrinking targets would undercount
  Gate and trial minutes.
- **Sessions only start through Hyperfocus.** A plain timer without the
  power isn't built (A6: minimal; Phase 5 adds verification).

**Open issues**
- Next: section J, Class Awakening and the unique class skills (Tasks
  22–23).

## 2026-11-15 — Phase 4 sections J–N (Tasks 22–28) in one batch

**Changed**
- **J. Classes (22–23)**
  - `ClassAwakeningHost` and `ClassAwakeningScreen` (`:feature:skills`),
    shown from the app shell whenever a choice is due:
    - at level 10: the six stats rise as columns, the top two ignite gold,
      and the sigil ring forms; skipped with reduced motion;
    - after a rank-up, the re-choice (A14) offers "Keep …" first;
    - each card shows the class name at your rank, its +10% stats or
      Resonance, and its unique skill; "Later" hides it until the next
      app open.
  - First Principles: cast on an open Mind daily with a name; it becomes
    "Derivation: …", same tier, INT, tagged `derivation`, and still
    counts.
  - Parallel Runs: cast on one open timed Mind quest and pick the partner
    in the confirm card. Completing the longer one completes both.
  - Stillness: automatic, once a day; a `meditation` quest refills 25% of
    max MP.
  - Long Walk: automatic, once a day; a `walk` quest of 30 min or more also
    pays Normal INT mastery.
  - Overload and Resonance were already in place.
  - Template tags (`TemplateTags`): Breathing → meditation, Easy walk →
    walk, the paper summary → paper and writing, and others. Class skills
    and passives now work on the app's own quests too.
  - Status:
    - the class line under the title ("Theorist · +10% INT/WIS");
    - "Powers ready": one tap casts Overload or Unbroken, the others open
      Skills.
- **K. Rank trials (24–25)**
  - The trial card on Status and at the top of the Gates tab: days left,
    n/6, each objective, and +1 logging (which can pass it at once).
  - Claude can't target a trial: it never appears in the Gates Claude sees.
  - `RankUpOverlay` (design system), driven by the RANK_UP notice:
    - twelve lines of light converge, the rank glyph forms and flares,
      then the avatar in its new aura and Continue;
    - about 3.5 s; instant with reduced motion.
  - The class re-choice follows. The Gate board already uses the stored
    rank.
- **L. Avatar (26)**
  - `AvatarRules` (engine): particles = min(8 + ⌊L/2⌋, 60); one ring per
    10 levels, at most 10; the aura per rank.
  - `AvatarRepository.look` (data).
  - `LayeredAvatar` and `AvatarSpec` (design system), back to front:
    - aura glow, drifting particles, orbit rings;
    - silhouette with six original cloak cuts and six geometric sigils;
    - evolution stage brightens the class layer;
    - gear, then eyes.
    - Static with reduced motion.
  - New aura tokens: `AuraGreyBlue`, `AuraBlaze`, `AuraInverted`, plus
    `AuraPalette`.
  - Shown on Status, in Inventory and in the rank-up sequence. One Preview
    covers E→S with different classes.
- **M. The Record (27)**
  - `LoreLine` carries a category (old lines fall back to their seed's)
    and an unread flag; `LoreRepository.record` gives "n of m recorded"
    (seed lines; Claude's are extra).
  - Codex:
    - "The Record" with category filter chips;
    - "23 of 68 recorded", with no titles for locked or hidden lines;
    - unread entries carry a ●; tapping one marks it read.
  - The Status Codex button shows a ● while records are unread.
  - Lore notices are now a `RecordToast`: a "New record" panel at the top
    that types itself out (instant with reduced motion), doesn't block
    touches, and dismisses itself.
- **N. Bridge (28)**
  - `ASCENT STATUS · v3`. After `Stats:` come three lines, each `none` when
    empty:
    - `Class:` (name at your rank, stats, bonus; Polymath shows Resonance);
    - `Powers:` (unlocked actives you cast: `ready`, `n/m` charges,
      `cooldown N h`, `used today|this week|this month`);
    - `Rank trial:`.
  - Mentor's Eye's `Last 28 days:` was added in section H.
  - Block v3: `add_quest` takes `stat` and `tags`.
    - Unknown tags are dropped silently; the card shows `#tag`s kept.
    - A bad `stat` is a BAD_VALUE problem on that action only.
    - v1 and v2 blocks still read; v4 is reported as unsupported.
  - `PowerRepository.standings()` feeds the Powers line.
  - Docs updated in the same task:
    - `docs/ASCENT-System-Project-Instructions.md`: the v3 STATUS example,
      the three lines, the `stat` and `tags` fields with the tag list, "you
      may suggest a power by name; never assume it has been cast", and
      that rank trials are the app's;
    - SPEC.md: a Phase 4 bridge paragraph.
- Tests:
  - engine: `AvatarRulesTest` (4);
  - data: First Principles, Parallel Runs, Stillness plus Long Walk;
  - bridge: a v3 parser and validator test, the STATUS golden test (v3
    header and lines), and the old "unknown version" test moved to v4.
- `./gradlew test assembleDebug`: 294 tests (engine 201, data 57, bridge
  29, skills 3, other modules 4).

**Decisions**
- **The Powers line lists only unlocked actives you cast yourself**, per
  the PHASE4 rule. The example's "Eigenstate Collapse locked" isn't shown;
  automatic skills (Stillness, Long Walk) are left out.
- **Undo doesn't take back Stillness MP or Long Walk INT mastery**, and
  their once-a-day use stays spent. Same-day undo is a convenience;
  tracking these would need new columns.
- **Parallel Runs: completing the shorter quest first does nothing
  special.** Only the longer (or an equal) one carries its partner.
- **The class line uses the class name at your rank** (evolved at B, final
  at S). Your worn title is unchanged.
- **"Later" on Class Awakening only hides it for this app session.** The
  choice returns at the next open.

**Open issues**
- Phone checks are still pending for D–N: casting, Focus Session with and
  without Do Not Disturb, Class Awakening via the debug panel (raise to
  level 10), a rank trial (debug "Clear the rank trial") with the rank-up
  sequence, animations on and off, the Record toast, and STATUS v3 sent to
  Claude.
- Paste the updated `docs/ASCENT-System-Project-Instructions.md` into the
  Claude Project.
- Next: the Phase 4 exit checks (section O).

## 2026-11-16 — Health check after J–N

**What changed**
- Ran a full check: every unit test forced to rerun (294 pass: engine 201,
  data 57, bridge 29, skills 3, status 2, app 1, designsystem 1), debug
  and release builds, and `lintDebug`.
- Lint failed on `NonObservableLocale` in two Phase 1 screens
  (`AwakeningScreen`, `SettingsScreen`): the rest-day chips read
  `Locale.getDefault()`, so their labels wouldn't follow a language change.
  Both now read `LocalConfiguration.current.locales[0]`. (Lint suggests
  `LocalLocale`, but this Compose version doesn't have it.)
- Verified: the release APK has no INTERNET permission and no debug panel,
  DebugClock or DebugActions code (only the empty release stub); Room
  schemas 1–3 are exported and MIGRATIONS lists 1→2 and 2→3; no
  `fallbackToDestructiveMigration`; no raw hex in feature modules; every
  screen has a Preview.

**Open issues**
- Unchanged from the J–N entry. No device was connected, so nothing was
  run on a phone.

## 2026-11-16 — Phase 5 Task 0: section 0 applied

**What changed**
- PHASE5.md section 0 (D1–D8) approved and folded into SPEC.md as a new
  section, "Durability and honest metrics (Phase 5)". The XP bonus-cap
  paragraph and the quest rules mention the grind guard.
- Roadmap: Phase 5 is now Durability and honest metrics. The old Phase 5
  (Integrations: Health Connect, focus-timer verification, widget) and Phase
  6 (AI: ritual notifications, smarter templates, boss decomposition,
  Assessment) are marked shelved. Their mentions in Verification, the
  achievements' logged-data note, the Settings row, "Outside the app", the
  tech table and the Git parking line now say shelved.
- CLAUDE.md: Health Connect is "shelved" in Stack and the module list (the
  user approved touching CLAUDE.md).
- TODO.md rewritten for Phase 5 from PHASE5.md section 7; unfinished
  Phase 4 exit and older checks carried over.

**Decisions (approved with section 0)**
- **Partial credit waits.** Quests have no progress column, so x is 1 or 0.
  The engine keeps x = min(progress / target, 1) for later.
- **A new `xp_event` table** in the v4 migration logs every XP grant from
  then on (source, amount, multipliers). `xp_log.csv` rebuilds earlier
  history from quest rows only; the README says so.
- **`paidXp` is the existing `xpAwarded`**: undo already refunds it and the
  mastery exactly. Only `guardMultiplier` is added.
- **Series:** template quests by template key; recurring quests by a
  `seriesId` carried forward by the daily copy. Backfill from exact matches
  only (template key; recurring by title, realm, scope and source).
- **Second Wind** gives that day to the Mobility series; the swapped-out
  series is not updated.
- **Guard slot number** = slots used by today's completed self-added
  (`MANUAL`) quests plus this quest's own. Undo frees slots; nothing is
  repaid, so XP can't be gained by cycling.
- **STATUS v4, block stays v3** (no new actions).
- **Restore stages the files and restarts the app**; the swap happens
  before Room and DataStore open (DataStore can't be reopened in a live
  process).
- **No new dependencies:** `java.util.zip`, `MessageDigest` and
  `DocumentsContract` cover backup, hash and folder access.

**Open issues**
- Phase 4 exit (Task 29) and the Phase 2–4 phone checks are still open;
  carried over in TODO.md.
- Next: Task 1, the strength engine.

## 2026-11-16 — Phase 5 Tasks 1–3: strength engine, grind guard, Room v4

**What changed**
- **Task 1, engine `HabitStrength.kt`:**
  - α_day and α_week (D1, D2);
  - `PeriodOutcome` (NotIssued, Neutral, Issued with a `Forgiveness` that
    is ignored, D3);
  - x = min(progress / target, 1);
  - fold, strength from stored history (sorted, one row per key), and
    half-up percent;
  - labels and `summarize` (streak, best, count, since);
  - `HabitPeriods` keys (epoch day of the game day, or of the week's
    Sunday) and `HabitSeries` ids (`t:<templateKey>`, `r:<publicId>`).
  - `HabitStrengthTest`: 14 tests from PHASE5 section 6 (30/60/90 days,
    weeks 1/4/8 and the exact 1 − 0.2^(7k/30), one miss, 31 days back to
    99%, gaps, D3, partial credit, idempotent edit, rounding, the 04:00 and
    05:30 resets, the Sunday boundary, a time-zone change).
- **Task 2, engine `GrindGuard.kt`** and `content/guard.json`:
  - m(n) = max(0.5^(n − 3), 0.1) past 3 free slots;
  - slots 1/1/1/2/3 by tier, paid at the last slot;
  - `QuestOrigin` (only `SELF_ADDED` counts);
  - no loot past the free slots;
  - `GuardDay` (complete, undo, day boundary) and mastery scaling;
  - `RewardCalculator` gains `guardMultiplier`, which scales the base;
  - the content validator checks the guard file (and that a higher tier
    never uses fewer slots).
  - `GrindGuardTest`: 10 tests, including the property test.
- **Task 3, Room v4** (`MIGRATION_3_4`, `4.json`):
  - quest gains `seriesId` (indexed) and `guardMultiplier`;
  - daily snapshot gains `neutral`;
  - new tables `habit_observation`, `habit_series_cache` and `xp_event`;
  - `HabitDao`;
  - the backfill fills `seriesId` (template dailies by key, recurring
    chains by exact title, realm, scope and source) and inserts
    observations for closed periods.
  - New quests get a series in `QuestRepository.insert`; the daily copy
    carries it; editing a quest to recurring starts one.
  - `HabitRepository`: summary from the cache, recompute on a miss,
    invalidate.
  - `BackupPrefs` in a separate DataStore file `ascent_backup`.
  - Tests:
    - `MigrationTest` gains a 3 → 4 schema check and a Phase 4 data test
      (class, skills, a Clarity journal, tags, template, recurring daily
      and weekly chains, rest day, removed, open day and week, one-offs,
      Claude and side quests);
    - `Phase5DataTest`: 5 flow tests.
- `./gradlew test assembleDebug`: 325 tests (engine 225, data 64, bridge
  29, skills 3, status 2, app 1, designsystem 1).
- SPEC: the v4 data model lists the extra cache and XP-event columns, the
  separate backup DataStore file, `guard.json` and the backfill rules.

**Decisions**
- **The guard property** is "total XP held for the quests completed now
  never exceeds what those quests pay when completed fresh, in the order of
  their latest completion". A stricter "never more than the first time
  this set was reached" is false and harmless: complete A, B, C, then X
  (slot 4, 50%), undo A, B, C, and redoing X pays 100%. That only recovers
  what the ordering cost; it never beats completing X alone.
- **Rest days record nothing** for daily series, as PHASE5 lists them, even
  though a rest-day Daily is issued. Weekly series ignore rest days.
- **Removed quests record nothing** (not issued from the player's side).
- **The backfill skips the open day and week** (`player.currentDay`); those
  are recorded at their reset in Task 4.
- **Backup keys live in their own DataStore file**, so restoring the game's
  DataStore can't carry over another phone's folder permission or times.
- **The guard file also holds `decay`** (0.5), next to free slots and floor.
- **Percent rounds half-up**, and the label follows the shown percent
  (84.95% shows "85%" and "Bound").

**Open issues**
- Nothing records observations, applies the guard or writes XP events yet:
  that's Task 4 (wiring). Second Wind and First Principles don't update
  `seriesId` on their in-place edits until then; the backfill already
  handles past Second Wind swaps through the template key.
- Phase 4 exit and the Phase 2–4 phone checks are still carried over.
- Next: Task 4, wiring.

## 2026-11-16 — Phase 5 Tasks 4–5: wiring, strength and guard on screen

**What changed**
- **Task 4, wiring:**
  - `QuestRepository.complete` applies the grind guard. `QuestOrigin` comes
    from source and kind, so only `MANUAL` normal and side quests count.
    The guard scales XP and mastery (Long Walk's extra INT too), stops a
    bounty's loot roll past the free slots, and stores `guardMultiplier`.
  - Undo refunds exactly `xpAwarded` and resets the multiplier.
  - Habit observations: a completion records x = 1 at once, an undo
    deletes it, and `DailyCycle` records every issued series at the reset
    (daily) and at the Sunday reset (weekly), before open quests expire.
    Rest days record nothing. `HabitRepository.warm()` fills the cache for
    backfilled series on each cycle run.
  - Second Wind sets the swapped quest's `seriesId` to the Mobility
    series; First Principles clears it (a derivation is a one-off).
  - XP log: `grantXp`/`takeXp` take an `XpLog` and write `xp_event` for
    quests, boss tasks (and their undo), boss defeats, Trials, Gates, rank
    trials, chapters and Unbroken. Multipliers that aren't 1 are written as
    "guard=0.5 power=3.0 crit=2.0". An undo logs a negative amount from the
    same source (`XpSource.UNDO` dropped).
  - `QuestRepository.observeGuard`/`guardToday` give `GuardStatus` (extras,
    paid in full, used slots, what a tier would pay).
  - The `Quest` model gains `seriesId`, `guardMultiplier` and `selfAdded`.
  - Engine: `HabitStrength.trajectory` (the 90-period sparkline), with a
    test.
- **Task 5, UI (`:feature:quests`):**
  - Quest cards show "↻ 72%" for any series with a recorded period,
    coloured by label. It is a 48 dp button with a content description,
    and it opens the detail.
  - Open self-added cards show "pays NN%" past the free slots; done ones
    that were paid less show "paid NN%".
  - The header shows "Extras today: n (k at full XP)" once you've completed
    one of your own quests.
  - The add-quest form and the bounty dialog show `◆ DIMINISHING RETURNS —
    The Observer measures effort, not entries.` and "pays NN%" when the
    quest would pay less than full now; the form's XP preview includes the
    guard.
  - New `HabitDetail.kt`: strength and label, a sparkline with the 85%
    line, streak and best (days or weeks), "Measuring since" under 30
    periods, and a one-line explanation. Previews: holding and measuring,
    plus "Add quest — diminishing returns". The Quest Log sample shows a
    strength chip, a "pays 50%" and a "paid 50%".
- Tests: `Phase5DataTest` gains 8 flows:
  - completion, undo and the reset recording;
  - rest day;
  - a shielded miss still 0;
  - Unbroken leaves strength alone and logs its XP;
  - Second Wind moves the day to Mobility;
  - a weekly series at the Sunday reset;
  - the guard: 1, 1, 1, 0.5, 0.25, 0.125, mastery scaled, template quests
    exempt, exact undo, redo takes the next slot, the XP log sums to the
    XP gained, and a new day resets;
  - Hard and Extreme slots.
- `./gradlew test assembleDebug`: 334 tests (engine 226, data 72, bridge 29,
  skills 3, status 2, app 1, designsystem 1). Previews rendered.
- SPEC: the Computation bullet says when observations are written.

**Decisions**
- **A completion counts at once** for strength, so the chip moves when you
  tick the quest; the reset makes it final. Same outcome as "record at
  the reset", with feedback.
- **The strength chip shows on template quests too,** not only on
  "Repeats" quests, since template dailies are the main series.
- **Guard percents are rounded to whole numbers** (12.5% shows as 13%).
- **The bounty dialog's warning uses an Easy quest's slot** (bounties are
  Easy).
- **Label colours:** Unstable red, Forming gold, Holding blue, Bound cyan.

**Open issues**
- Second Wind on a recurring Claude daily makes tomorrow's copy the
  Mobility quest (a Phase 4 behaviour: the copy takes the swapped row). Now
  it also continues the Mobility series. Rare; left as is.
- Neutral restore days (D7) aren't wired yet: Task 6.
- Phone checks for Tasks 4–5:
  - add four of your own quests and complete them (header, "pays" and
    "paid");
  - the form's warning;
  - undo one;
  - tick a template quest and see its ↻ chip;
  - open the detail;
  - after the next reset, the chips on every repeating quest.
- Next: Task 6, the backup engine.

## 2026-11-16 — Phase 5 Tasks 6–7: backup engine and Backup UI

**What changed**
- **Engine `Backups.kt`:**
  - `BackupManifest` (format, app version, schema, UTC and local times,
    game day, row counts, DB and settings SHA-256, journal flag, player
    summary) and `RestoreMarker`, with a codec;
  - `BackupNames` (`ascent-backup-YYYYMMDD-HHMM.zip`, and the export
    name);
  - `BackupRetention`: the newest file of each of the last 14 days, plus
    the newest remaining file of each of the last 12 months; other files
    are never touched.
  - `BackupRulesTest`: 5 tests, including exactly 14 + 12 from 400 days.
- **Data `backup/` package:**
  - `BackupArchive`: the zip, `inspect` (zip, manifest, schema ≤ the app's,
    hashes, SQLite `quick_check` and `user_version`), `stage` (marker
    written last), and `applyPending` (deletes the old WAL and SHM first,
    copy-then-rename, idempotent).
  - `BackupFolder`: a plain directory, or a Storage Access Framework tree
    via `DocumentsContract`.
  - `BackupService`:
    - the snapshot: WAL checkpoint, then the database and WAL are copied
      while a Room transaction holds the write lock; the copy's WAL is
      folded in with `journal_mode=DELETE`;
    - D8 is applied to the copy only;
    - row counts, a copy of the settings file, the manifest and the zip;
    - change detection (DB hash, settings hash, journal flag) and
      retention;
    - Back up now, the scheduled backup, folder permission;
    - restore: inspect, then confirm (pre-restore backup in `files/backup`,
      then stage), Undo restore (stages the pre-restore backup), and the
      app restart.
  - `RestoreFinisher`: the first run after a swap checks the row counts
    (each table at least the manifest's), sets `neutralBefore` to today,
    records the summary and Undo restore for today, and unlocks
    *Conserved* once.
  - `DailyCycle` resolves every day before `neutralBefore` with
    `resolveNeutral`:
    - a neutral snapshot (0 objectives, rest-like), so there is no penalty,
      and HP, MP, streak and difficulty are unchanged;
    - no strength update, and no Daily issued for those days;
    - open quests close and repeating ones come back.
    A week that ran through a gap records no weekly observation.
  - `BackupPrefs` gains the last backup's name, the last error,
    `neutralBefore` and the last restore.
  - Lore: `restore` unlock kind, *Conserved* in `lore.json` (69 lines).
  - `AscentApplication` applies a staged restore before `super.onCreate()`,
    so before Hilt builds anything.
  - `DailyResetWorker` runs the scheduled backup after the reset.
  - Auto Backup rules: `ascent.db`, `ascent.db-wal` and the settings
    DataStore, for cloud backup and device transfer; never `ascent_backup`
    or `files/backup`.
- **Tests:** `BackupTest`, 6 tests:
  - a row-for-row round trip, with the settings bytes and the pre-restore
    backup;
  - a Phase 3 (v2) fixture that restores and migrates to v4, with the
    series backfill;
  - corrupt, foreign, tampered and newer backups refused with nothing
    staged and the data unchanged;
  - D8 and "only if changed";
  - D7 neutral gap days;
  - a half-staged restore is never applied.
- **Task 7, UI:**
  - `:feature:status` `BackupSettings.kt`: `BackupViewModel`, a stateless
    `BackupSection` and `BackupRoute`, shown in Settings under Save. It has
    folder, schedule note, last backup or error, Back up now, the journal
    switch, Restore with a confirm dialog (date, level, XP, streak, journal
    note), the restored summary with Undo restore, and messages for every
    refusal. 4 Previews; the Settings Preview shows the panel.
  - `RestoreEntry` on the Awakening's first screen, so a fresh install can
    restore (the exit test's path).
  - The pickers are registered in `:app` (`OpenDocumentTree`,
    `OpenDocument`) and passed down through `LocalBackupPickers`, so no
    feature module needs activity-compose (no new dependency).
  - Status shows "◆ Last backup N days ago" past 7 days, or "◆ No backup
    yet"; tapping opens Settings.
- `./gradlew test assembleDebug`: 345 tests (engine 231, data 78, bridge 29,
  skills 3, status 2, app 1, designsystem 1). `assembleRelease` and
  `lintDebug` clean. Previews rendered.
- SPEC: Auto Backup contents, the never-backed-up warning, neutral days
  as rest-like, and restore on the Awakening.

**Decisions**
- **A consistent copy without closing the database:** checkpoint, then
  copy the database and WAL under a write transaction and fold the WAL in.
  It is equivalent to "writes paused". SQLite's `VACUUM INTO` would be
  simpler but needs API 30 (minSdk is 28).
- **The pre-restore backup lives in app storage** (`files/backup`), not in
  the picked folder, so Undo restore works with no folder set.
- **Neutral days are also rest-like** in their snapshot (`restDay = 1`),
  so Momentum and Focus Chain neither grow nor break, and no penalty
  follows (0 objectives).
- **The staleness warning also shows when there has never been a backup**
  ("No backup yet").
- **The manifest also carries a player summary** (level, XP, streak) for
  the confirm dialog, and a `format` number.
- **A failed scheduled backup** is shown in Settings; the reset still
  succeeds.
- **Undo restore can't itself be undone,** and restoring a file from the
  Awakening works with no player yet.

**Open issues**
- Phone checks for Tasks 6–7:
  - Settings → Backup → pick a folder; Back up now; see the zip in the
    folder (Files app);
  - switch the journal off and back up again;
  - Restore that file: the dialog, the restart, the summary and Undo
    restore;
  - Undo restore;
  - set the date forward (debug panel: +1 week) and check the Status line
    and the neutral days after a restore.
  - The exit test (uninstall, reinstall, restore from the Awakening) waits
    for the end of the phase.
- Auto Backup can't be checked on the phone easily (`adb shell bmgr`);
  it's the fallback.
- Next: Task 8, CSV export.

## 2026-11-16 — Phase 5 Tasks 8–9: CSV export, STATUS v4, Bound achievements

**What changed**
- **Task 8, export:**
  - Engine `Export.kt`:
    - `Csv`: RFC 4180 quoting, CRLF, and a parser for checks;
    - `ExportColumns`: the column order of `quests.csv`, `daily.csv`,
      `habits.csv` and `xp_log.csv`, used by the writer and the README
      check;
    - `ExportReadme`: reads the column list for a file from the README.
  - The README is an asset (`core/data/src/main/assets/export/README.txt`):
    the format, every column, the strength and guard formulas, and that XP
    from before the log is rebuilt from quest rows.
  - Data `ExportService`:
    - writes the zip (four CSVs plus the README) to a document you create;
    - ISO dates (game days as `YYYY-MM-DD`, moments with the phone's
      offset);
    - `habits.csv` carries the strength after each period;
    - `xp_log.csv` adds rebuilt history (completions before the first
      logged event, "from quest history") ahead of the log.
  - UI: **Export data** in Settings → Backup, through a third picker
    (`CreateDocument`) registered in `:app`.
  - Tests:
    - `ExportRulesTest` (3): quoting of commas, quotes, CR and LF; a round
      trip; the shipped README lists every column in order;
    - `ExportTest` (1): headers, quoting of a tricky title, the neutral
      column, habits, and the XP log adding up to the player's XP
      (history row included).
- **Task 9, bridge and content:**
  - STATUS v4: header `· v4`; `strength NN%` on quest lines with a
    recorded series strength; `Extras today: n (next pays NN%)` after the
    side quests; `neutral` on restore-gap history days.
    `ProposalService` fills them from `HabitRepository.strengths` and
    `QuestRepository.guardToday()`.
  - The `ascent` block stays v3; the parser is unchanged and its tests
    pass.
  - `docs/ASCENT-System-Project-Instructions.md`: the v4 example, the
    strength and extras sentences, the neutral-day note, and "the block
    stays v3". SPEC's bridge list gains the Phase 5 line.
  - Achievements `bound_state` (title "Bound") and `bound_triplet` (C–B
    chest), on new metrics `bound_habits` and `bound_realms`, computed
    from the strength cache (`AchievementMetrics.bound`, by the shown
    percent).
  - Lore *Bound state* (unlocks with the achievement). Content now has 14
    achievements and 70 lore lines; the validator passes.
  - Tests:
    - a v4 STATUS test (strength tags on template, recurring and weekly
      quests, none on a one-off, the extras line, neutral and rest days);
      the golden snapshot moves to v4;
    - an engine test for `bound` and the content links;
    - a data test: one Bound series earns Bound State, the title and the
      lore line; three realms earn Bound Triplet.
- `./gradlew test assembleDebug`: 352 tests (engine 235, data 80, bridge 30,
  skills 3, status 2, app 1, designsystem 1).

**Decisions**
- **The export README is an asset, not strings.xml**: it's a document
  inside the zip, in English like the STATUS wire format, and a test keeps
  it in step with the code.
- **The export is saved where you choose** (a Save dialog), not into the
  backup folder, so exports don't mix with backups or their retention.
- **`progress` in quests.csv** is the target when done, else 0 (quests
  have no progress column).
- **Strength shows on any quest line whose series has a recorded
  strength** (template dailies too), matching the app's cards.
- **"Extras today" is always printed** (0 / 100% on a quiet day), so
  Claude can rely on the line.
- **Neutral history days are tagged `neutral`** in STATUS, and the Project
  instructions say to leave them out of completion rates.
- **Bound Triplet is "one in each realm"**: three realms covered by Bound
  series at once.

**Open issues**
- **Paste the updated `docs/ASCENT-System-Project-Instructions.md` into the
  Claude Project** (STATUS v4).
- Phone checks for Tasks 8–9:
  - Export data, then open the zip on the Mac (columns, the tricky titles,
    the README);
  - send a STATUS to Claude and check the strength tags and the extras
    line.
- Phase 5 tasks are done; next is Task 10, the exit review, then the exit
  week and the uninstall–reinstall–restore test. The Phase 4 exit and older
  phone checks are still carried over.

## 2026-11-16 — Release Task 0: section 0 applied, icons checked

**What changed**
- RELEASE.md section 0 (R1–R6) approved and folded into SPEC.md as a new
  section, "Release (friends build)".
- SPEC also changes:
  - the roadmap gains the Release, and Phase 6 (Health Connect,
    focus-timer verification, widget, check-in notifications, weekly
    charts) comes after it; smarter templates and boss decomposition stay
    shelved;
  - the shipped arc templates become "Research or study project", Iron
    Discipline and Quiet Hours; The Rod Gas and Ring and Line move to your
    own data;
  - the Mind quest examples are marked as not shipped;
  - Health Connect, the widget and charts now read "Phase 6".
- CLAUDE.md: Health Connect is "Phase 6" in the Stack line and the module
  list.
- **Icons:** the new set from `ascent-icon.zip` was already copied in
  (12 Nov):
  - `mipmap-anydpi-v26` with background, foreground and monochrome
    layers;
  - vector layers on the 108 × 108 grid, the art (with its glow) within
    about 30 units of the centre, inside the 33-unit safe zone;
  - PNG fallbacks.
  The Android Studio template icons were still there: a `.webp` beside
  every new `.png` broke the build ("Duplicate resources"), and the old
  `mipmap-anydpi/` reused the foreground as its monochrome layer. Deleted,
  with approval, as RELEASE section 1 says: 10 `.webp` files and
  `mipmap-anydpi/`. The PNGs stay (only used below API 26; harmless).
- `./gradlew test assembleDebug assembleRelease` green again. The release
  APK reports `com.tirthankar.ascent`, label ASCENT, version 1 / "1.0", and
  an adaptive icon.
- TODO.md rewritten for the Release from RELEASE.md section 8. Phase 5
  Task 10, the Phase 5 phone checks and the older carried-over items come
  first.

**Decisions (approved with section 0)**
- **The application id stays `com.tirthankar.ascent`** (R2), even though it
  holds your first name: it's developer attribution, friends know who made
  it, and changing it would split your own install.
- **R4 against the audit list:** the audit lists every hit, including
  "hard rods" and "Wigner". Item and lore flavour names stay; project
  seeds, personal examples and the Claude template's specifics go.
- **Phase 6 is back** (from RELEASE.md), after the Release.
- **About credits add JetBrains Mono**, the third bundled font.

**Open issues**
- **Your step:** back up your data on the phone (Back up now) and keep the
  zip off the phone before installing any release build. If the release
  key differs from the debug key, installing over the debug build fails;
  restore from the backup after.
- Later tasks:
  - Report a problem needs a local log (new);
  - the `oss-licenses` plugin would be a new dependency (ask at Task 6);
  - check the Claude free-plan claim (Projects, up to five) at Task 5 and
    the developer-verification dates at Task 8.
- Next: Release Task 1, identity (after the carried-over checks).


## 2026-11-17 — Local debug APK for Android testing

**What changed**
- Set up Android command-line tools and SDK 37.0 in `/tmp/ascent-android-sdk`,
  with Gradle dependencies in `/tmp/ascent-build-gradle`; no Android Studio
  or emulator was needed. These temporary directories may be removed on reboot.
- Corrected `HabitStrengthTest` to compare the daily alpha formula within
  `1e-12`, rather than requiring bit-for-bit equality. The two mathematically
  equivalent calculations differed by about `1.1e-16` on this JVM. Production
  game formulas and dependency versions are unchanged.
- Built the signed debug APK at `app/build/outputs/apk/debug/app-debug.apk`
  and copied it to `build/ASCENT-debug.apk` (about 40 MB).
- Preserved this build's debug signing key at
  `.gradle/local-signing/debug.keystore` (gitignored, owner-readable only).
  Retain this key for compatible future debug updates. To reuse it with the
  command below after a reboot, restore it to
  `/tmp/ascent-android-user/debug.keystore` after recreating that directory.
  This is a development key, not the future release signing key.

**Validation**
- `ANDROID_HOME=/tmp/ascent-android-sdk ANDROID_USER_HOME=/tmp/ascent-android-user GRADLE_USER_HOME=/tmp/ascent-build-gradle ./gradlew test assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m`
  succeeded: 352 tests, zero failures, errors or skips.
- `apksigner verify --verbose` passed (APK Signature Scheme v2).
- APK metadata: `com.tirthankar.ascent`, label ASCENT, version code 1,
  version name 1.0, minimum API 28 (Android 9), target API 37.
- SHA-256: `c07416927bfb0dcc810bec2cb99211ce0ba3a212a84442c680e2f80ad8e345af`.

**Open issues**
- No physical-device install or interaction was performed. On the phone,
  complete the Awakening, check off a quest, close and reopen the app, and
  confirm progress persists. The existing phase exit checks remain open.
- If a differently signed ASCENT is already installed, back up its player
  data before considering uninstalling it; this debug key may not match.
