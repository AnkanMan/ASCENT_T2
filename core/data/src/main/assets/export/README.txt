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
  progress: the amount done; quests are done or not done, so this is the target or 0
  status: OPEN, DONE, EXPIRED or REMOVED
  source: TEMPLATE (issued by the app), CLAUDE (applied from Claude) or MANUAL (added by you)
  issuedAt: when the quest was created
  completedAt: when it was completed; empty if not done
  paidXp: the XP it paid (0 if not done)
  guardMultiplier: the grind guard's multiplier when it was paid (1 for full XP)

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
  source: QUEST, BOSS_TASK, BOSS, TRIAL, GATE, RANK_TRIAL, CHAPTER, UNBROKEN or OTHER
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
