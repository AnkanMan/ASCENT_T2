ASCENT data export
==================

This zip holds your ASCENT history for reading and analysis. It is never used to
restore: for that, use a backup file (ascent-backup-*.zip).

Format: UTF-8 CSV, one header row per file, RFC 4180 quoting (a value with a comma,
a quote or a line break is wrapped in double quotes, and quotes inside it are
doubled), CRLF line ends. Dates are ISO-8601: a game day is YYYY-MM-DD; a moment is
YYYY-MM-DDTHH:MM:SS+HH:MM in the phone's time zone at export. A game day runs from
one daily reset to the next.

quests.csv — one row per quest instance (a repeating quest has one row per day or week)
  id: the quest's short id, as Claude sees it (e.g. q7f2a)
  seriesId: the habit series ("t:<template>" or "r:<first id>"); empty for one-offs
  scope: DAILY or WEEKLY
  realm: BODY, MIND or SELF
  title: the quest as written
  tier: TRIVIAL, EASY, NORMAL, HARD or EXTREME
  target: the target amount for that day or week
  unit: the unit as written (reps, min, pages, done, ...)
  progress: recorded actual progress; legacy completed rows infer their target
  status: OPEN, DONE, EXPIRED or REMOVED
  source: TEMPLATE (issued by the app), CLAUDE (applied from Claude) or MANUAL (added by you)
  issuedAt: when the quest was created
  completedAt: when it was completed; empty if not done
  paidXp: the XP it paid (0 if not done)
  guardMultiplier: the grind guard's multiplier when it was paid (1 for full XP)
  measurementMode: AUTO, CHECKBOX, COUNT, DURATION or QUANTITY
  legacyProgress: true when progress was inferred during migration

daily.csv — one row per game day
  date: the game day
  level: level at the day's close
  totalXp: total XP at the day's close
  hp: HP after the day's close
  mp: MP after the day's close
  streak: Daily Quest streak after the day's close
  bodyDone: Body objectives of the Daily Quest done
  bodyTotal: Body objectives issued
  mindDone: Mind objectives done
  mindTotal: Mind objectives issued
  selfDone: Self objectives done
  selfTotal: Self objectives issued
  neutral: true for a day between a backup and its restore (neither done nor missed)

habits.csv — one row per habit series and period
  seriesId: the series, as in quests.csv
  title: the series' most recent quest title
  periodKey: the period's first day (the game day, or the Sunday of the week)
  x: how fully it was done that period, min(progress / target, 1)
  strength: the habit strength after that period, from 0 to 1

xp_log.csv — one row per XP change
  timestamp: when it happened
  source: QUEST, BOSS_TASK, BOSS, TRIAL, GATE, RANK_TRIAL, CHAPTER, UNBROKEN, OTHER, PLAN_WRITTEN or PLAN_COMPLETED
  amount: XP gained; negative for an undo
  multipliers: the multipliers that weren't 1, e.g. "guard=0.5 power=3.0 crit=2.0"

XP history before the XP log existed (before Phase 5) is rebuilt from completed
quests only, with source QUEST and multipliers "from quest history". XP from Gates,
bosses, Trials, rank trials and chapters before then isn't listed.


Habit strength
--------------
A smoothed completion rate for each repeating quest. For each period k in which the
quest was issued:

    x_k = min(progress_k / target_k, 1)
    S_k = (1 - a) * S_(k-1) + a * x_k,    S_0 = 0

with a = 1 - 0.2^(1/30) ~ 0.05223 for daily quests and a = 1 - 0.2^(7/30) ~ 0.3131
for weekly ones. A perfect record reaches 80% after 30 days, 96% after 60, 99.2%
after 90. Periods where the quest wasn't issued (rest days, a quest swapped out by a
power, days between a backup and its restore) leave S unchanged. Streak Shield and
Unbroken never change it. Shown as a whole percent: below 30% Unstable, 30-60%
Forming, 60-85% Holding, 85% and above Bound.


Grind guard
-----------
Quests you add yourself (your own, bounties, Clarity's extras) are numbered in the
order you complete them each game day. Trivial to Normal quests use 1 slot, Hard 2,
Extreme 3. A quest pays the multiplier of its last slot n:

    m(n) = 1                       for n <= 3
    m(n) = max(0.5^(n - 3), 0.1)   for n > 3

so the 4th pays 50%, the 5th 25%, the 6th 12.5%, the 7th onward 10%. It scales XP
and mastery. Undo refunds exactly what was paid.


plans.csv — Phase 6 records
  id: plan ID
  targetDay: target game day
  state: SCHEDULED, ACTIVE, COMPLETED, EXPIRED or NEUTRAL
  writtenAt: 10 XP claim time
  completedAt: 40 XP claim time; empty if unpaid


plan_items.csv — Phase 6 records
  id: id
  planId: planId
  position: position
  title: title
  questId: questId
  mode: mode
  target: target
  unit: unit
  progress: progress
  done: done


sessions.csv — Phase 6 records
  id: id
  questId: questId
  itemId: itemId
  state: state
  startedAt: startedAt
  endsAt: endsAt
  activeSeconds: activeSeconds
  endedAt: endedAt
  quantity: quantity
  origin: origin
  note: note

Session state SAVED contributes to progress; VOID and EXPIRED do not. A questId and itemId are mutually exclusive. TIMER and MANUAL distinguish the result origin. Running duration is checkpointed; a restored session pauses and does not count restore downtime.

Timed count/distance results remain self-reported: TIMER records that a timer was used, not automatic verification of the quantity.


study_sessions.csv — Study Mode attempts, including incomplete and discarded starts
  id: Study attempt id.
  day: Game day when the attempt started; at most three starts per day.
  subject: Study subject.
  objective: Session mission.
  questId: Optional linked normal quest id.
  state: RUNNING, RESULT, COMPLETED, INCOMPLETE, DISCARDED or INTERRUPTED.
  plannedSeconds: Required continuous study duration (at least 1800 seconds).
  activeSeconds: Safely measured/checkpointed duration; no pause/resume.
  startedAt: Start timestamp.
  endedAt: End timestamp, if stopped.
  quoteIndex: Persisted index into the original offline motivational quotes.
  learned: Learning debrief.
  nextStep: Optional next-session objective.
  studyXp: Study reward (30 or 100); zero for unsuccessful or pending attempts.
  questXp: Ordinary linked-quest XP, separate from Study XP.

Study XP uses source STUDY in xp_log.csv and has no quest multipliers. Exactly
60 minutes pays 30 XP; completed measured duration above 60 minutes pays 100 XP.
Start consumes one of three daily attempts even if discarded. Interrupted,
incomplete, discarded and pending attempts grant no Study XP. Running timers
recover within the same boot; reboot/restore ends them without invented time.
