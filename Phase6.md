# Phase 6 — Tomorrow planner, task sessions and Study Mode

Implementation specification · 2026-11-17

**Goal:** write tomorrow's to-do list, carry it into the next game day, and
earn a small bonus for following through. Normal quests may be part of the
plan and keep their normal rewards. The planner pays an additional **10 XP
for saving the plan** and **40 XP for completing every item**.

Add a shared task interface: tap a task, Start, Pause/Resume, Finish, then
record any required numerical result. Multiple sessions can contribute to
one task. The Quest Log and planner open the same interface.

Read with `SPEC.md`, `CLAUDE.md`, and `PHASE5.md`. This document plans the
feature; see TODO and DEVLOG for implementation and device-check status.

**Roadmap:** this phase now covers the planner and task sessions. Integrations
move to Phase 7 in SPEC and CLAUDE. The carried-forward friends-release and
phone checks remain open; feature implementation does not complete them.

## 0. Scope and decisions

### Requirements confirmed by the player

- A next-day planner / to-do list.
- Saving tomorrow's plan earns **10 XP**.
- Completing **all** items earns **40 extra XP**, not 40 per item.
- Planner rewards are separate from normal task-completion XP.
- Normal quests can be included in the plan; their normal XP still applies.
- Planned tasks share the normal daily expiration boundary.
- Clicking a task opens a detail interface with a Start action.
- Started tasks offer Pause and Finish; paused tasks can Resume.
- Finishing asks for the actual result when the task requires numerical data
  (for example, completed push-ups).

### Implementation defaults

These defaults guide the implementation authorized with this phase. Phone
validation and future refinements remain tracked separately.

| Decision | Proposed rule |
| --- | --- |
| Plan count | One plan per target game day; only tomorrow can be planned |
| Minimum | At least one nonblank item to save and earn 10 XP |
| Editing | Edit tomorrow's plan until its day starts; resaving never pays again |
| Commitment | Item membership and wording lock when the target day begins |
| Entry types | Standalone checklist item or a link to an eligible normal daily quest |
| Per-item XP | A checklist item pays none; a linked quest retains its usual XP |
| Bonus size | Exactly 10 and 40; no tier, difficulty, streak, crit, gear, class or power multipliers |
| Miss | Incomplete plans expire without a planner penalty or partial bonus |
| Completion | Pay 40 immediately when all items are done during the active game day |
| After payout | Seal the completed plan; no reopening or undo of its linked completions |
| History | Keep completed and expired plans; no automatic carry-over |
| Running sessions | One running session across the app initially; starting another offers to pause the current one |
| Multiple sessions | Accumulate saved results toward the same task's target |
| Manual logging | Allow activity already done and corrected time; label its origin |
| Partial progress | Save actual progress immediately; award ordinary completion XP only when the target is reached |
| Background | Persist the session and show a resume banner; no new background service or notification in this version |

The seal rule prevents repeatedly undoing and re-earning a bonus and avoids
taking back XP after a level-up. It needs a clear message on affected quest
undo controls. If ordinary quest undo must remain available after a planner
payout, design a transactional bonus reversal policy before implementation;
do not silently leave a paid plan incomplete or reduce the player's level.

## 1. Rewards and normal quests

For a nonempty plan P:

    planningBonus(P) = 10, once on its first valid save for tomorrow
    completionBonus(P) = 40, once when every committed item is complete
    totalPlannerBonus(P) <= 50

The 10 XP is earned on the writing day; the 40 XP is earned on the target
day. XP events record the day each reward was actually granted, with the
plan ID identifying the target date. A day can therefore contain yesterday's
plan-completion reward and tomorrow's plan-writing reward.

Example: tomorrow's plan contains two normal quests and one checklist item.
The quests earn their ordinary rewards when completed. The checklist item
earns no individual reward. Finishing all three adds exactly 40 XP; saving
the plan the previous day already earned 10 XP. Completing only two items
earns no completion bonus, even if both completed items were normal quests.

Planner grants use `grantXp` and `logXp`, so level-ups, attribute points,
skill points and level-up events follow the existing rules. Add explicit XP
sources `PLAN_WRITTEN` and `PLAN_COMPLETED` for history and export.

Planner items do not independently count toward Daily completion, streaks,
mastery, realm difficulty, habit strength, loot or the grind guard. A linked
quest still affects those systems exactly as it did before linking. Linking
a manual quest must not change its origin or bypass its guard multiplier.
An incomplete plan does not issue a penalty quest or cost HP. Ordinary
consequences for an unfinished linked Daily objective still apply once.

## 2. Game day, activation and expiration

Use `GameDay` and `GameClock`; never introduce a separate midnight reset.
“Tomorrow” means `gameDay.today().plusDays(1)`.

With a 04:00 reset:

1. Monday evening: save Tuesday's plan, earn 10 XP.
2. Tuesday 04:00: the plan becomes active and its item list locks.
3. Before Wednesday 04:00: complete all items to earn 40 XP.
4. Wednesday 04:00: any incomplete Tuesday plan expires with no bonus.

At Tuesday 02:00, the app still considers it Monday's game day, so the
“Tomorrow” editor still targets Tuesday's game day beginning at 04:00.
Show the target date and reset time to make this explicit.

Store the target game-day epoch, not a separate per-item deadline. Derive
boundaries using the existing reset and time-zone settings, as normal daily
quests do. Editing those settings must not create a second plan or replay
reward claims for the same stored target date.

Run lifecycle reconciliation on foreground and in `DailyCycle`; WorkManager
is a scheduling convenience, not the authority for whether an action is in
time. Recheck the clock inside every save, edit, link and completion
transaction. A stale screen cannot save tomorrow's plan after it activates
or complete yesterday's plan after expiry.

After absence, expire missed plans without inventing task completions or
paying bonuses. During a neutral restore gap, close outdated plans as neutral
and unpaid; do not apply a penalty. Already recorded reward claims survive
restore and must not be replayed.

## 3. Items and links to normal quests

### Standalone checklist item

- A title, position and completion state owned by the plan.
- Uses manual check-off, with no tier, mastery or per-item XP. It can also
  use the shared session interface; numerical checklist items store their
  own target, unit and progress without becoming ordinary quests.
- Reuse the existing forbidden-task validation for new titles.
- Can be checked and unchecked while the active plan is not yet rewarded.
- Cannot be completed early in the Tomorrow editor.

### Linked normal daily quest

- A reference to one existing quest instance for the plan's target day.
- The quest row remains the source of truth for completion and its rewards.
- Tapping the row opens the shared task interface. Checkbox tasks retain
  quick completion; measured tasks open result entry. Both use normal quest
  completion when eligible, then evaluate the plan; never pay the quest twice.
- A completion from the ordinary Quest Log or Claude's existing `complete`
  action also updates the planner through the repository integration.
- Allow normal daily-scope quests (template, manual or Claude). Exclude
  weekly quests, side quests with different expiry, emergencies, penalties,
  Gates and boss tasks from direct links in this first version.
- Reject duplicate links to the same quest inside a plan. One completion
  must not satisfy multiple slots pointing to the same quest.

Tomorrow's generated daily quest instances generally do not exist yet.
Do not prematurely issue tomorrow's Daily or mutate today's quests to solve
this. Initially, write a checklist item describing the intended activity.
On the target day, allow binding that existing item to an eligible quest
issued for the same day. This replaces its completion source without
removing the committed item, changing its title, or awarding XP.

Binding requires the item to be incomplete, with no recorded progress or
session history, and the quest to be open; a quest already completed cannot
retroactively fill a planner item. Do not discard or transfer standalone
session results by linking after activity has started. Once bound,
the link cannot be swapped or cleared during the active day. Preserve the
original planned title and display the linked quest's current title too.
Semantic matching is the player's responsibility; do not guess by title.

Before a plan is rewarded, undoing a linked quest through the existing
allowed undo path makes the corresponding item incomplete. Removing,
expiring or replacing a linked quest never removes its planner item or
counts it as done. Show a blocked-link explanation; do not silently shrink
the required list. Warn before a player removes a quest linked to a plan.
After the 40 XP payout, linked quest undo is blocked by the seal rule.

## 4. Data and transactions

Proposed additions in `:core:data`:

- `DayPlanEntity`: ID, unique `targetDay`, state (`SCHEDULED`, `ACTIVE`,
  `COMPLETED`, `EXPIRED`, `NEUTRAL`), created/updated times, activation time,
  writing reward claim time, completion reward claim time.
- `PlanItemEntity`: ID, plan ID, position, committed title, nullable linked
  quest ID, standalone completion time, completion mode and optional target,
  unit and actual progress for standalone measured items. Index plan ID and linked quest ID;
  prevent duplicate non-null links within a plan. Quest removal must not
  cascade-delete an item.
- `PlannerDao`: observe plan/items, retrieve by target day, insert/update,
  conditional state/claim updates, and query rewarded quest links for undo.
- `PlannerRepository`: save/edit tomorrow, reconcile lifecycle, bind quest,
  check/uncheck item and evaluate completion.
- Domain/UI models should derive a linked item's done state from its quest,
  rather than maintaining another independently editable checkbox state.

The pre-phase Room schema is v4. This phase adds an additive **4 → 5 migration**, registers it,
export schema 5 and test the entire migration chain. Existing players start
with no plans; do not synthesize planning rewards from historical quests.
Include the session tables and quest progress fields described in section 6
in this same migration when implementing the phase together. If v5 has
already shipped by then, add a subsequent migration instead of rewriting it.

Save the plan and claim its 10 XP in one transaction. For linked completion,
update the quest, evaluate its plan, claim 40 XP and write both XP records in
the same transaction. Emit notices only after commit. A crash or repeated
tap cannot leave a paid reward without a claim or a claim without its XP.

Avoid a dependency cycle: both repositories can depend on a small internal
`PlannerCompletion` coordinator that uses DAOs and the shared grant helper.
`QuestRepository` invokes it after changes, while `PlannerRepository` can
delegate a linked check-off to `QuestRepository`.

Conditional claim writes and unique target dates enforce idempotence across
multiple screens, foreground/reset races and repeated worker runs. Evaluate
“all complete” only for a nonempty, active, committed plan and only before
its target day's closing reset.

## 5. Screens and feedback

Extend `:feature:quests`; no new module or bottom-navigation destination.
Keep the existing Daily and Weekly views available. Add a Planner entry
with **Today**, **Tomorrow** and a compact **History** view.

- Tomorrow: date, activation/expiry information, ordered title fields,
  add/remove/reorder controls and “Save plan · +10 XP” before first save.
  After saving, show “Planning bonus earned” and an ordinary Save changes
  action. Persist only on explicit save; text entry itself grants nothing.
- Today: committed items, `n / total`, reset countdown, quest-link picker,
  and “Complete every item for +40 XP”. Linked rows identify normal quest
  rewards separately from the plan bonus. Item rows open task details;
  partial session results update progress without counting the item as done.
- Complete: one short “Plan complete · +40 XP” notice; sealed history card.
  Use existing theme, reduced-motion behaviour and level-up handling.
- Expired: show the achieved count and “Expired”; no blame or extra penalty.
- Empty: explain how to prepare tomorrow. Never display an empty list as a
  completed plan or grant a bonus for it.
- All new text belongs in string resources; touch targets and descriptions
  follow the existing accessibility rules. Add Previews for the above states.

### Visual and navigation revision — 2026-11-17

The player authorized implementing this revision after reviewing the original UI.
Use the existing dark void, HoloPanel corner brackets/scanlines, SystemBlue and
GlowCyan tokens, Rajdhani headings and JetBrains Mono numbers. No new visual
library, permissions, persistence schema or reward changes are needed.

- Replace the small planner text control with a full-width, prominent clickable
  holographic panel: a large calendar/task emblem, large glowing blue heading,
  descriptive subtitle and clear forward affordance. Minimum touch size 48 dp.
- Planner is a dedicated navigation page, not a dialog. Today, Tomorrow and
  History remain available as tabs. Tomorrow has a dated briefing/reward panel,
  numbered task cards, clear edit/remove/reorder controls, a separate task editor
  panel and a full-width explicit Save action. Fields span the available width;
  measurement choices scroll on narrow screens; keyboard insets remain usable.
- Opening any task (including linked planner items and the resume banner) pushes
  a dedicated task page. Use a progress panel, large readable session timer,
  prominent Start/Pause/Resume/Finish actions, a result panel and history cards.
- Hide the app bottom bar on planner/task pages to keep the workspace clear
  and route leaving through the Back controls.
- Toolbar Back and Android Back follow the navigation stack: task → originating
  planner/Quest Log, planner → Quest Log. Keep the draft and selected planner tab
  on task return/rotation. Confirm before leaving an unsaved planner draft.
- Reuse existing static glow and reduced-motion-aware progress components. Keep
  accessible button semantics, icon labels, scalable text and 48 dp controls.
- Include full-page Previews for Tomorrow, task progress/result and empty/history
  states. Verify `test assembleDebug lintDebug`; separately check actual phone
  navigation, keyboard, large text, draft retention and timer controls.

Implementation: update `Activities.kt`, Quest Log entry/callbacks, app navigation
and planner string resources. Update this document, TODO and append DEVLOG.
Existing transactions, XP claims, expiry and session recovery remain authoritative.

## 6. Shared task interface and activity sessions

### Existing support and additions

Ordinary quests currently have targets and done/open status, without actual
numerical progress or a general Start/Pause/Resume flow. Gates and arc
objectives have progress logging; Hyperfocus has its own timed session.
Reuse their visual components where suitable, but do not treat their data
models or rewards as interchangeable.

Extend `:feature:quests`, `:core:data` and `:core:engine`. No new module,
library, permission or AI call is needed for the initial implementation.

### Detail and activity screens

1. **Tap:** show title, description where available, target/unit, current
   progress, normal rewards, session history, Start and Log activity already
   done. Linked planner items show the separate plan bonus requirement.
2. **Start:** enter an activity screen with elapsed active time, accumulated
   saved progress, Pause and Finish. Starting alone pays nothing.
3. **Pause:** persist active time and offer Resume or Finish. Pause time is
   never counted as work.
4. **Finish:** stop the timer and open the result form. Returning to the
   session leaves it paused until explicitly resumed; time spent entering
   results is not active work. Cancel/discard is explicit and pays nothing.
5. **Save result:** write one contribution, update aggregate progress and,
   if the target is reached, complete the task through its existing reward
   path. Show what was recorded and any rewards earned.

Keep quick completion for checkbox tasks and manual logging for work done
without starting the timer. Do not require a timer for every activity.

### Daily focus special case

The player requested on 2026-11-17 that daily deep work and phone-free focus
be single-session tasks. Identify these by the stable `mind_deep_work` and
`mind_phone_free_block` template keys and DAILY scope, never by display title.

- Show Start, Finish and Discard; never Pause or Resume. Keep duration mode.
- One result must cover the whole target; partial sessions cannot accumulate
  toward completion. Manual full-session confirmation remains available.
- A running focus session cannot be implicitly paused by starting/resuming
  another timer: finish or discard it first. Ordinary tasks keep pause/resume.
- Navigation/backgrounding in the same boot keeps the timer running. Existing
  paused sessions and safe reboot/restore recovery can be finished or discarded,
  but not resumed; do not erase history or fabricate time.
- Implement shared engine identification, repository enforcement, task controls
  and explanatory strings. Test pause/resume rejection, timer conflicts, partial
  rejection/full completion and weekly/ordinary-task regression behaviour.
- No schema change or change to the separate Hyperfocus power is needed.

### Explicit measurement modes

| Mode | Examples | Result entry and completion |
| --- | --- | --- |
| Checkbox | Send an email | Confirm done; elapsed time is optional history |
| Count | Push-ups, completed exercises | Nonnegative whole count; done when accumulated count reaches target |
| Duration | Deep work, meditation | Show active minutes, allow labelled manual adjustment; done at target duration |
| Quantity | Pages, distance | Amount in the task's unit; whole pages, fractional distance where appropriate |

Use explicit mode and unit metadata. Existing known units can provide
defaults, with a player confirmation/edit path for ambiguous tasks. Never
infer completion rules solely from the title. Distinguish a duration target
from a time-of-day objective such as lights out: the latter remains a
checkbox until a dedicated verification method exists.

Elapsed time does not prove a repetition count or distance. Ask for those
results after Finish. Store canonical values (for example, active seconds)
and convert units for display without rounding prematurely. Require finite,
valid values; zero is a valid recorded session but cannot satisfy a positive
target. Use sensible input validation without changing Body safety caps or
rewarding a player for exceeding them.

### Progress and rewards

- Multiple saved sessions accumulate: 15 + 10 + 15 push-ups = 40/40.
- Preserve actual results above target, while completion percentage is
  capped at 100%. Excess work grants no additional completion reward.
- Starting, pausing, resuming and partial sessions grant no XP or mastery.
- A normal quest pays its existing completion XP/mastery once at target,
  with its normal difficulty, crit, guard and power rules. Do not award both
  a new session reward and an ordinary quest reward for the same activity.
- A standalone planner item has no ordinary quest reward. Its completion
  contributes only to the plan's all-items condition.
- A linked quest contributes one completion to the planner. A partial
  session cannot mark it done or trigger the 40 XP bonus.
- Session saves and reward claims must be idempotent. Store a stable session
  ID; double-tapping Save or retrying after a crash cannot add its result twice.

The phase introduces partial input for habit strength: at period close,
record `min(actualProgress / target, 1)` for measured recurring quests.
Checkboxes retain 0/1 observations. Preserve the existing treatment of rest,
neutral periods and shielded misses. This supersedes the current binary-only
implementation, so Task 0 must amend SPEC and later tasks must test it.

Audit goal, Gate and arc progress propagation. Where an existing consumer
counts completed quest targets, preserve that behaviour initially; merely
saving a partial session must not also grant a second objective contribution.
Do not silently change those systems to actual-progress accumulation.

### Corrections and undo

Allow correcting or removing an unrewarded session contribution before the
task's expiry. Recompute totals from its saved contributions; do not edit an
unrelated aggregate without updating history. Target, unit and mode changes
after work has been recorded require an explicit policy; initially lock
them for that task instance so results cannot be reinterpreted to earn XP.

For a rewarded normal quest, a correction that makes it incomplete must go
through the existing permitted undo transaction first, reversing stored
reward amounts rather than calculating a new reward. If undo is disallowed
(level-up, loot, planner seal or another existing restriction), explain why
and block the correction. Extra edits while remaining complete cannot
reissue rewards. Preserve existing power-undo limitations rather than claiming
every secondary effect can be reversed.

### Session persistence and reset

- Add `TaskSessionEntity`, a session DAO and `TaskSessionRepository`.
  Suggested fields: ID, exactly one quest/standalone-item owner, owning
  period, state, start/pause/end timestamps, accumulated active duration,
  running time anchor, recorded quantity/unit, result origin and optional
  note. Reject orphaned owners and duplicate finalization.
- Add completion mode and actual progress to ordinary quest storage.
  Represent legacy completed measured quests as their target and legacy
  incomplete quests as zero, explicitly marked as inferred legacy data.
  Do not invent historical sessions or claim those values were measured.
- Use monotonic elapsed time while running so a wall-clock adjustment does
  not inflate work time. Persist sufficient anchors to resume after process
  death; on device reboot or an invalid anchor, pause at the last safely
  recorded duration and explain that elapsed time could not be recovered.
- Navigating away or locking the phone does not discard the session. Show
  an in-app resume banner on return. One running session is enforced in the
  repository, not merely by disabling a UI button.
- Apply the owner's existing expiry: daily quests and planner items end at
  the daily reset; ordinary weekly quests retain their weekly expiry.
  Initial session support covers normal daily/weekly quests and standalone
  planner items. Gates, bosses and special quest kinds retain their existing
  interactions.
- At expiry, close running or paused sessions against the original owner.
  Never move their progress into a recurring copy. Cap recoverable elapsed
  time at the boundary. Unsaved numerical results may be retained as late
  historical notes, but cannot complete the expired task or grant late XP.
  No automatic completion from an unconfirmed timer crossing its target.
- A restored running session resumes as paused; downtime and neutral restore
  gaps are not counted as activity. Preserve saved results and reward claims.

Use a small transaction coordinator for session result, quest completion and
planner evaluation. Avoid circular repository dependencies and emit game
events only after the entire transaction commits.

### Hyperfocus and Claude compatibility

Ordinary sessions must not spend MP, enable Do Not Disturb or grant
Hyperfocus bonuses. Retain the existing Hyperfocus power and its rewards.
Before implementation, define a single timing owner for a Hyperfocus-linked
quest: initially block a generic running session on that same quest while
Hyperfocus is active, and block Hyperfocus from starting while a generic
session is running. Do not add generic Pause semantics to Hyperfocus without
separately revising its existing rules.

Keep the incoming Claude block format unchanged. Existing `complete` on a
measured quest is explicit manual full-completion confirmation: fill any
remaining progress to target with a labelled manual contribution, never a
fabricated timed session. Repeated imports pay nothing extra. Resolve an
active session on the quest before applying that confirmation so it cannot
later add the same result. Existing edit/remove actions must respect the
recorded-work and planner-seal restrictions, including a review message when
an action cannot apply.

## 7. Backup, export and bridge

- Existing full-database backups should include all new planner/session tables; verify
  manifest row counts, round trips, restore undo and schema compatibility.
- Old v4 backups migrate to v5 with empty planner/session tables and the
  documented legacy-progress values. Older app builds
  must refuse a v5 backup using the existing newer-schema check.
- Extend CSV export with `plans.csv` and `plan_items.csv`, including target
  day, state, item titles/links/completions and bonus claims. Document exact
  columns in the export README and keep the column-order checks.
- Include both bonus sources in `xp_log.csv`; their events must reconcile
  with total XP independently of quest events.
- Add `sessions.csv` with owner IDs, state, timestamps, active duration,
  quantity/unit, origin and note. Include measured quest progress/mode in
  `quests.csv` and standalone progress in `plan_items.csv`; update exact
  headers and README together. Distinguish inferred legacy results from
  measured results and late history from eligible progress.
- No new Claude action or STATUS version is required in this phase.
  Existing quest-completion imports still trigger planner evaluation. Update
  the Project instructions and SPEC to explain manual full completion of
  measured tasks without changing the incoming JSON shape.

## 8. Required tests

**Pure engine rules**

- Empty plans cannot earn either bonus; valid planning pays exactly 10.
- Zero or partial completion pays zero completion XP; all items pay 40 once.
- Exactly 50 maximum planner XP per target plan, independent of quest XP.
- Repeat save, evaluate, activate and expire operations are idempotent.
- Game-day eligibility at reset minus one instant, exactly reset and after
  reset; non-default reset, Sunday boundary, time-zone/DST behaviour.

**Repository and lifecycle**

- Saving twice, concurrent saves and app restart do not repeat 10 XP.
- A quest pays its usual XP once; planner linking never changes its origin,
  guard slots, mastery or Daily membership.
- Mixed linked/checklist plans require every item, with no 40 XP per item.
- Completion through either screen or the Claude bridge stays consistent.
- Binding validates day, type, open state, absence of standalone work and duplicate links; activation
  does not require future quest rows to have existed on the writing day.
- Undo before payout updates progress; after payout the seal blocks undo.
- Removing/replacing a quest cannot reduce the committed item count.
- Boundary races, delayed workers and offline catch-up cannot pay late.
- Both bonus grants handle level-ups and XP logs correctly; failed
  transactions roll back claims and XP together.
- Rest days support optional plans without changing safety rules; neutral
  restore days expire unknown plans without granting completion rewards.
- Schema 4 → 5 and older migration chains preserve existing player data.
- Backup/restore preserves all rows and reward claims; exports quote titles
  correctly and all XP sources reconcile with the player total.

**Task sessions and measured progress**

- Start → Pause → Resume → Finish records active time without pauses or
  result-entry time; manual adjustment is labelled and validated.
- Count, duration, quantity and checkbox forms use the right inputs/units;
  invalid, negative and nonfinite data cannot corrupt stored progress.
- 15 + 10 + 15 reaches 40 once; partial/zero results pay no quest reward;
  above-target results preserve the actual number without extra rewards.
- Repeated finalization and concurrent saves add one contribution and pay
  quest XP and any all-items planner bonus exactly once.
- A linked quest session opened from either screen shares one owner and
  updates both screens, while other planner items still gate the 40 XP.
- Session edits recompute totals; corrections after reward respect existing
  undo/level/loot restrictions and the completed-plan seal.
- Partial habit observations use actual/target; checkbox and legacy
  behaviour, rest exclusions and neutral periods remain consistent.
- Leaving the app, process death, reboot, time changes and restore do not
  discard saved work, inflate duration or create two running sessions.
- Daily and weekly expiration close the correct owner; reset races, late
  results and recurring copies cannot produce late or duplicate rewards.
- Hyperfocus and ordinary sessions cannot double-track the same work or
  accidentally share MP costs, DND changes or power bonuses.
- Claude complete/edit/remove actions cannot bypass progress, active-session
  resolution, claim idempotence or planner-seal restrictions.
- Existing goal/Gate/arc progress is not counted twice after session saves.
- Migration preserves existing rows and distinguishes legacy inferred
  progress; backup/restore and CSV exports include session records correctly.

**Phone checks**

Write a plan, see +10, resave without another reward, advance the debug clock
to its target day, link a normal quest, complete some items, reopen the app,
complete the rest and see exactly +40 alongside normal quest XP. Confirm
the lock/undo message, expiration at the next reset, and backup/restore.
Also log 25 of 40 push-ups, record another 15, and verify one normal quest
reward and the correct planner progress. Test timed work with pauses, manual
logging, background/lock-screen return, process restart, reset during a
session, correction restrictions and Hyperfocus alongside generic sessions.
Provide Compose Previews for task detail, running, paused, result entry,
partial completion, completed and expired states.

## 9. Build order

0. Reconcile the roadmap and fold the agreed planner/session rules into SPEC.
   Move integrations to Phase 7 consistently; prepare the implementation
   checklist without marking current release/phone checks as complete.
1. Add pure planner and session/progress rules and tests in `:core:engine`.
2. Add planner/session entities, DAOs, quest progress/mode fields and domain
   models; implement migration 4 → 5 with legacy-progress and migration tests.
3. Implement task details, measurement/result forms and manual session logging
   before timers; accumulate progress through normal quest reward handling.
4. Implement persistent Start/Pause/Resume/Finish sessions, the one-running
   rule, clock recovery and in-app resume banner.
5. Implement planner persistence, editing and transactional writing reward.
6. Wire planner activation/expiration, session expiry and neutral restore
   handling into `DailyCycle`; coordinate Hyperfocus timing ownership.
7. Implement quest binding, completion coordination, undo seal and the
   transactional 40 XP grant; test normal quest rewards remain independent.
8. Implement Planner UI/ViewModel and shared session entry points; complete
   string resources, Previews, accessibility and reward feedback.
9. Integrate partial habit observations, audit goal/Gate/arc propagation and
   test Claude compatibility; update SPEC and Project instructions.
10. Extend backup tests, session/planner CSV exports, README and XP-log checks.
11. Run `./gradlew test assembleDebug`, then complete the phone checks.
   Produce an updated debug APK; release APK/version/signing changes follow
   the existing release process when this feature is actually distributed.

**Exit (phone validation remains pending):** a week of real planning with at least one complete and one expired
plan; verify exact bonuses, ordinary quest rewards, reset boundaries,
persistence and a backup/restore round trip. No new dependency, permission,
AI API or server is required.
Include timed and manually logged tasks during the week, with at least one
multi-session numerical task and a session interrupted by a daily reset.


## Study Mode — approved extension (2026-11-17)

Implement the player's agreed Study Mode as a dedicated System screen and a
prominent special button in the Quest Log, next to the planner entry.

### Product rules

1. Mission briefing: subject, concrete objective and chosen duration ≥30 minutes.
   An optional link to an open Mind duration quest requires the session duration
   to cover the full quest target as well as the 30-minute minimum.
2. Motivational quote: randomly select an original local quote at each start,
   avoid a consecutive repeat, persist the selection and keep it on the Study
   screen. No popup, network request or repeated reroll on recomposition.
3. Immersive timer: blue/cyan System ring, readable monotonic elapsed time,
   chosen duration, objective and progress. No Pause or Resume. Leaving the
   page/backgrounding does not stop a same-boot timer.
4. Finish stops measured time. Meeting the chosen duration opens a debrief;
   ending early records an incomplete attempt with zero XP. Discard remains
   available before reward submission and always grants zero XP.
5. Debrief: what was learned and optional next step. Save grants exactly 30 XP
   for completed elapsed time from 30 through 60 minutes inclusive, or 100 XP
   strictly above 60 minutes. This explicit Study reward is separate from normal
   linked-quest XP, which can only pay through the existing path once.
6. Results/history: subject/objective, elapsed/target duration, outcome, debrief,
   next step, Study XP and linked quest XP. Keep incomplete/discarded attempts.
7. At most three starts per existing game day, counting all incomplete and
   discarded starts. Limit checked transactionally, never just in the UI.

### Persistence and compatibility

- Add Room v6 study_session table and additive 5→6 migration/schema test.
  Persist day, mission, chosen seconds, quote index, optional linked quest/task
  session IDs, state, monotonic anchor/boot, checkpointed seconds, end boundary,
  debrief and separate reward amounts. START consumes a slot; refresh/retry does
  not. Completion/reward submission is transactional and idempotent.
- One Study attempt may be RUNNING/RESULT at a time. Generic running timers and
  Hyperfocus block starting Study; Study blocks starting/resuming those timers.
  A linked quest gets a protected task-session marker so target edits/removals
  and other completion routes cannot rewrite the active mission.
- Same-boot process restart recovers elapsed time from the anchor. Reboot/restore
  safely ends the attempt as INTERRUPTED, preserving checkpointed history, no XP
  and no Resume. Daily reset ends unfinished/pending attempts without XP; start
  rejects durations that cannot fit before the current daily reset. No fabricated
  time, migration rewrite, new permission, service or internet dependency.
- Whole-database backups preserve the table; restore finalization interrupts
  active attempts and closes linked markers. Add study_sessions.csv to export.
- Keep existing planner, task sessions, daily focus exception and side-quest fix.

### Visual implementation and validation

Use existing HoloPanel corner brackets, dark hex-grid background, Rajdhani titles,
monospace timer/XP and SystemBlue/GlowCyan. Entry button has a large book/sigil
emblem, glowing title and session allowance. Main page has a dedicated orbital
ring, persistent quote panel, distinct mission/debrief/history sections and
full-width ≥48 dp controls. Confirmation protects Discard and an early Finish.
Reuse reduced-motion-aware components; no mandatory looping animation.

Files: engine StudyRules + tests; data entity/DAO/migration, StudyRepository and
recovery/backup/export/timer guards + tests; quests StudyMode composables/ViewModel,
entry and app navigation/resources; SPEC/TODO/DEVLOG updates. Build order: rules,
storage, lifecycle/reward integration, UI, migration/repository/backup/export tests,
then test/assembleDebug/lintDebug and signed debug APK. Phone visual/navigation,
clock recovery, quota and exact 60-minute reward checks remain explicit device QA.
