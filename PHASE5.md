# Phase 5 — Durability and honest metrics

Three features borrowed from other habit apps:

1. **Backup and export** — your progress survives a lost or replaced phone, and
   your history can leave the app for analysis.
2. **Habit strength** — a smoothed score on every repeating quest, so one miss
   reads as a dip, not a reset.
3. **Grind guard** — diminishing XP after the 3rd self-added quest in a day.

The old Phases 5 (Integrations) and 6 (System depth) are **shelved**. Mark them
so in SPEC.md's roadmap in Task 0; nothing from them is built here.

Read with `CLAUDE.md` and `SPEC.md`. Same workflow as every phase: `TODO.md` from
the build order (section 7), one task at a time, `./gradlew test assembleDebug`
green before a task is done, a `DEVLOG.md` entry per session.

---

## 0. Decisions (approve before Task 0)

| # | Question | Proposed rule | Why |
| --- | --- | --- | --- |
| D1 | Smoothing constant for strength | α_day = 1 − 0.2^(1/30) ≈ 0.05223 | A daily quest done every day reaches 80% after 30 days, 96% after 60, 99.2% after 90 |
| D2 | Weekly repeating quests | Same memory in real time: α_week = 1 − 0.2^(7/30) ≈ 0.3131 | A score means the same thing whatever the period. Cost: weekly scores are noisier (one observation per week) |
| D3 | Do Streak Shield, Unbroken and other powers change strength? | **No.** Strength records only what happened | The streak is the game layer and can be forgiven. Strength is the one metric no power touches |
| D4 | Grind-guard curve | Multiplier m(n) = 1 for n ≤ 3, then 0.5^(n−3) with a floor of 0.1 | 4th pays 50%, 5th 25%, 6th 12.5%, 7th onward 10%. Logging stays rewarded; farming doesn't |
| D5 | Tier gaming (adding one Extreme quest instead of six Easy ones) | Self-added Hard quests use **2** guard slots, Extreme quests **3** | Without this, raising the tier bypasses the guard |
| D6 | Backup format | A zip of the SQLite database file, DataStore files and a manifest — not a JSON dump | Restoring an old backup reuses the Room migrations that are already tested. JSON/CSV is the separate *export* for reading |
| D7 | Days between a backup and its restore | **Neutral**: no penalties, no HP loss, streak frozen, no strength updates | Those days are unknown, not missed. Treating them as misses would punish restoring |
| D8 | Clarity journal text in backups | Included by default, with a setting to exclude it | Backups may end up in a synced folder; the choice is yours |

---

## 1. Habit strength

### Definition

A **series** is one repeating quest across all the days (or weeks) it is
issued. Each series gets a stable `seriesId` that survives the daily re-issue.

For each period *k* in which the series was issued, record an observation

    x_k = min(progress_k / target_k, 1)      ∈ [0, 1]

so partial work counts partially. Targets may change with the difficulty
engine; x is always measured against that period's own target.

Strength is an exponential moving average:

    S_k = (1 − α) · S_{k−1} + α · x_k,      S_0 = 0

with α from D1 (daily) or D2 (weekly).

**Why these numbers.** For a perfect record, S_n = 1 − (1 − α)^n. Requiring
S_30 = 0.8 gives (1 − α)^30 = 0.2, so α = 1 − 0.2^(1/30) ≈ 0.05223. The
e-folding time is τ = −1 / ln(1 − α) = 30 / ln 5 ≈ 18.64 days, so the score
mostly reflects the last three weeks or so. These milestones match the ones
Loop Habit Tracker publishes (80% / 96% / 99% at one, two and three months);
this is our own formula chosen to reproduce them, not a copy of Loop's code.

**Consequences worth knowing** (from the formula, for the UI copy and tests):
- One miss from S ≈ 1 drops the score to ≈ 0.948.
- Climbing back from 0.948 to 0.99 takes 31 perfect days.
- Weekly series: 31% after 1 week, 78% after 4, 95% after 8, 99% after 13.

### Periods that don't count

The score is **unchanged** (no update at all) for a period in which the series
wasn't issued: rest days, days where Second Wind swapped the quest out,
neutral restore gaps (D7). A period in which it *was* issued and not done
records x = 0, whatever the Shield or Unbroken did (D3).

### Storage and computation

- `HabitObservation(seriesId, periodKey, x, target, progress, recordedAt)`,
  primary key `(seriesId, periodKey)`.
- Record at period close (the reset), and update in place if the player edits
  or undoes that period's completion before the next reset.
- Recompute S from the full observation history, oldest first, in `Double`.
  Cache the latest value per series; a cache miss or an edited past period
  recomputes from scratch. Hundreds of observations is trivial work.
- Display: integer percent (rounded half-up), never the raw double.

### Backfill

If past quest rows let you rebuild per-series history, backfill observations
from them during the migration. If they don't, start at S = 0 and label the
series "measuring since <date>" until 30 periods exist. Never invent history.

### Where it shows

- **Quest card:** a small `72%` next to the repeat icon.
- **Quest detail:** current strength, a 90-period sparkline, current streak and
  best streak for that series.
- **Labels:** < 30% *Unstable* · 30–60% *Forming* · 60–85% *Holding* · ≥ 85% *Bound*.
- **STATUS v4:** each repeating quest line gains `strength 72%`. Claude reads it
  for reviews; the Project instructions get one sentence about it.

### Achievements and lore (data only)

| id | Kind | Condition | Reward |
| --- | --- | --- | --- |
| bound_state | Threshold | Any repeating quest reaches 85% strength | Title "Bound" |
| bound_triplet | Threshold | Three repeating quests at ≥ 85% at once, one per realm | C–B chest |

Lore line for `bound_state` (follows the lore bible):
**Bound state** — "This habit no longer needs to be pushed. It sits in its own well, and only a sustained absence will lift it out."

---

## 2. Grind guard

### Scope

Counts **self-added** quests only: anything the player created by hand,
including Bounties and the extras Clarity creates. It does **not** count Daily
Quest objectives, template quests, quests applied from Claude, arc objectives,
Gate objectives, boss tasks, penalty, emergency or hidden quests.

### Rule

Per game day (reset to reset), self-added quests are numbered in the order
they're **completed**. A quest's slot count is 1 (Trivial to Normal), 2 (Hard)
or 3 (Extreme) (D5). The n-th slot pays

    m(n) = 1                        for n ≤ 3
    m(n) = max(0.5^(n − 3), 0.1)    for n > 3

A multi-slot quest uses the multiplier of its **last** slot. XP and mastery
are both multiplied. Order of application:

    XP = base × m × passive multipliers (≤ ×1.75) × single-target powers × crit

Past the 3rd slot, a self-added side quest gets **no loot roll**.

The free-slot count (3) and the floor (0.1) are constants in a content file,
not literals in code, so the threshold can be retuned without a code change.

### Undo is exact

Store `guardMultiplier` and `paidXp` on the quest at completion. Un-completing
refunds exactly `paidXp`; other quests keep what they were paid. No
retroactive recomputation, so completing and un-completing can never gain XP.

### UI

- When adding a self-added quest past the 3rd slot: one System line,
  `◆ DIMINISHING RETURNS — The Observer measures effort, not entries.`, and
  the card shows the multiplier it will pay ("pays 25%").
- Quests tab header: "Extras today: 4 (3 at full XP)".
- STATUS v4: `Extras today: 4 (next pays 25%)`.

---

## 3. Backup and export

### Backup file (for restoring)

`ascent-backup-YYYYMMDD-HHMM.zip`, containing:

- `ascent.db` — the Room database, copied after `PRAGMA wal_checkpoint(FULL)`
  with the database closed or writes paused, so the copy is consistent;
- `datastore/` — settings and Dossier;
- `manifest.json` — app version, Room schema version, created-at time
  (UTC and local), game day, row counts per table, SHA-256 of `ascent.db`,
  and whether the journal was excluded (D8).

Content assets (loot tables, lore, skills) are not backed up; they ship in the
APK.

### Where backups go

- **Folder:** the player picks a folder once through the Storage Access
  Framework; keep the persisted URI permission. Any sync tool can mirror that
  folder off the phone; for example Syncthing to the Mac mini, with no
  subscription.
- **Schedule:** WorkManager, daily after the reset, only if anything changed
  since the last backup. Plus a **Back up now** button.
- **Retention:** keep the newest 14 daily files and one file per month for 12
  months; delete older ones.
- **Second layer:** enable Android Auto Backup for the database and DataStore
  (with backup rules), so a new phone signed into the same Google account
  restores automatically. It's a fallback, not the main path; Android decides
  when it runs and enforces its own size quota.
- **Staleness:** if the last successful backup is more than 7 days old, show a
  one-line warning on Status.

### Restore

1. Pick a file. Verify it's a zip with a manifest and the SHA-256 matches.
2. Refuse a backup whose schema version is **newer** than the app's.
3. Make an automatic **pre-restore backup** of the current state.
4. Stage the files, close Room, swap in the database and DataStore, reopen.
   Room's migrations bring an older backup up to date.
5. Check the row counts against the manifest (allowing for migration-created
   rows), then apply D7: every game day between the backup and now is neutral.
6. Show a summary: level, XP, streak and backup date, with **Undo restore**
   available until the next reset (it restores the pre-restore backup).

Restore lore line (shown once, follows the lore bible):
**Conserved** — "The record is restored. Nothing measured was lost; the excitation resumes where it was."

### Export (for reading and analysis)

**Export data** writes `ascent-export-YYYYMMDD.zip` with UTF-8 CSV files,
RFC 4180 quoting, ISO-8601 dates and one header row each:

| File | One row per | Columns |
| --- | --- | --- |
| `quests.csv` | Quest instance | id, seriesId, scope, realm, title, tier, target, unit, progress, status, source, issuedAt, completedAt, paidXp, guardMultiplier |
| `daily.csv` | Game day | date, level, totalXp, hp, mp, streak, completion per realm, neutral (bool) |
| `habits.csv` | Series × period | seriesId, title, periodKey, x, strength |
| `xp_log.csv` | XP event | timestamp, source, amount, multipliers applied |
| `README.txt` | — | What each column means and the formulas used for strength and the guard |

Export is read-only: it is never used for restore.

---

## 4. Data model

One Room version bump with an exported schema and a migration test.
`fallbackToDestructiveMigration` stays banned.

| Entity | Change |
| --- | --- |
| Quest | + `seriesId` (if missing), `guardMultiplier`, `paidXp`; confirm `source` distinguishes self-added |
| HabitObservation | new: `seriesId`, `periodKey`, `x`, `target`, `progress`, `recordedAt` |
| HabitSeriesCache | new: `seriesId`, `strength`, `lastPeriodKey`, `best` |
| DailySnapshot | + `neutral` (bool) for restore gaps |

DataStore: `backupFolderUri`, `lastBackupAt`, `lastBackupHash`,
`excludeJournalFromBackup`.

---

## 5. Claude bridge: STATUS v4

No new actions. STATUS gains:

- `strength NN%` on every repeating quest line;
- `Extras today: n (next pays NN%)` after the quests.

Update `docs/ASCENT-System-Project-Instructions.md` in the same task with two
sentences: strength is a 0–100% smoothed completion rate where ≥ 85% means
the habit is holding on its own and < 30% means it needs attention; and "Don't
suggest more than three self-added quests in a day; past three they pay
diminishing XP."

---

## 6. Tests that must exist

**Strength**
- α_day = 1 − 0.2^(1/30); perfect daily record gives 0.80, 0.96, 0.992 at
  30, 60, 90 periods (tolerance 1e-9).
- Weekly: 0.3131, 0.7773, 0.9504 at 1, 4, 8 weeks.
- One miss from a long perfect run gives (1 − α) × S.
- Non-issued and neutral periods leave S unchanged; Shield and Unbroken
  don't (D3).
- Partial credit: x = progress/target capped at 1.
- Editing a past period recomputes to the same value as a fresh computation
  (idempotence).
- Period keys across reset times (04:00, 05:30), the Sunday week boundary and
  a time-zone change.

**Grind guard**
- m(n) sequence 1, 1, 1, 0.5, 0.25, 0.125, 0.1, 0.1.
- Slot weights: a Hard quest at slots 3–4 pays 0.5; an Extreme quest at
  slots 2–4 pays 0.5.
- Excluded sources never consume slots.
- Complete/un-complete in any order never increases total XP (property test).
- No loot roll past the 3rd slot; the guard resets at the game-day boundary.

**Backup and restore**
- Round trip: backup then restore reproduces every table row for row.
- A fixture backup from the Phase 3 schema restores and migrates.
- Corrupt zip, wrong hash and newer-schema backups are rejected with a clear
  message, and the current data stays untouched.
- A pre-restore backup always exists after a restore.
- Gap days are neutral: no penalty quests, no HP loss, streak unchanged.
- Retention keeps exactly 14 dailies plus 12 monthlies.
- CSV: quoting of commas, quotes and newlines in titles; column order matches
  README.

**Migration:** previous schema → new schema with player data intact.

---

## 7. Build order

0. **Approve section 0.** Apply it to SPEC.md, mark old Phases 5–6 as shelved,
   note it in DEVLOG.md.
1. **Engine: strength** — pure Kotlin, all strength tests.
2. **Engine: grind guard** — pure Kotlin, all guard tests.
3. **Data** — migration, `seriesId` assignment, observations, backfill.
4. **Wiring** — record observations at reset and on undo; guard applied at
   completion; `paidXp` stored.
5. **UI** — strength on cards and detail, guard notice and counter.
6. **Backup engine** — checkpoint, zip, manifest, hash; restore pipeline with
   staging, swap, migrate, verify, neutral gaps, undo.
7. **Backup UI** — Settings → Backup: folder, schedule, retention, Back up now,
   Restore, last-backup line, staleness warning; Auto Backup rules.
8. **Export** — CSV zip and README.
9. **Bridge and content** — STATUS v4, docs update, the two achievements and
   two lore lines, parser and golden tests.

**Exit:** a week of real use, then a real test: back up, uninstall the app,
reinstall, restore, and confirm level, XP, streak and every strength score
match what you had.
