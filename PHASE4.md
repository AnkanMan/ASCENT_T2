# Phase 4 — Progression

Skill trees and active powers, a class at level 10, rank trials, the lore
codex, and an avatar that changes as you level.

Read with `CLAUDE.md` and `SPEC.md` ("XP, levels, ranks and evolution",
"Skill trees and powers", "Equipment, achievements and lore codex").
**This file amends SPEC.md where noted** (section 0). Task 0 applies those
amendments to SPEC.md after the player approves them.

Like every phase: plan in `TODO.md` from the build order (section 11), one
task at a time, `./gradlew test assembleDebug` green before a task is done,
`DEVLOG.md` entry at the end of each session.

---

## 0. Amendments to SPEC.md (approve before Task 0)

Some Phase 4 rules in SPEC.md conflict with the invariants in CLAUDE.md, are
undefined, or depend on Phase 5 features. Each row is the proposed fix.

| # | SPEC.md says | Problem | Phase 4 rule |
| --- | --- | --- | --- |
| A1 | Limit Break: one Body quest at +30% target, 3× XP | Breaks the invariant "Body targets rise at most 10% per week" | Raise one open Body quest to the **largest target the weekly cap still allows**; 3× XP. If there is no headroom left this week, the power is unavailable ("No headroom this week") |
| A2 | Vanguard's Overload: one Body quest at +50% target, 3× XP | Same conflict, and would share headroom with Limit Break | Once a week, **all Body quests completed that day pay 2× XP**. Targets unchanged |
| A3 | Momentum: +3% per consecutive training day | Rewards training every day, so it fights the mandatory rest day | Rest days are **neutral**: they neither extend nor break the chain. Same for Focus Chain |
| A4 | Tier gates use "the tree's stat" | Each tree has two stats | Gate stat = **the higher** of the tree's two stats (Body: STR/AGI, Mind: INT/FOC, Self: WIS/VIT) |
| A5 | Class "from your two highest stats" | 15 stat pairs, only 5 named classes; Polymath rule ambiguous | Score each paired class = sum of its two stats. **Polymath** is eligible if the highest stat ≤ 1.15 × the lowest. The player picks from the **top two scores** (plus Polymath if eligible) |
| A6 | Hyperfocus: locked 90-min focus session | The focus timer is a Phase 5 item | Build a **minimal Focus Session** in Phase 4 (timer + linked quest). Phase 5 keeps Health Connect and verification counting |
| A7 | Insight, Mentor's Eye: "the AI" reviews/splits | The app never calls an AI | Both cast as **share-bridge requests**: they build a STATUS with a request line and open the share sheet. Claude's reply comes back through Import |
| A8 | Eigenstate Collapse: "highest-leverage task" | Nothing in the app can judge leverage | **You pick** one open quest; it pays 3× XP and the Quests tab collapses to it for the day. An optional "Ask the System which one" share button |
| A9 | Streak Shield and Unbroken both forgive misses | Undefined order | Shield applies automatically first (one miss per game week). Unbroken is only castable on a miss the Shield didn't cover |
| A10 | No cap on stacked passive bonuses | Gear, skills, class and streak can stack past the level curve | **Passive XP multiplier on any quest ≤ ×1.75** (gear + skills + class + streak). Single-target powers (×2, ×3) apply on top; crit last. Gear's own +30%/realm cap stays |

Anything else in SPEC.md stays as written. If Claude Code finds another
conflict while building, it stops and asks (CLAUDE.md, "Ask before you").

---

## 1. Skill trees

**Points.** 1 skill point every 2 levels (even levels), plus 1 per boss
defeated (from Phase 3). Points already earned are granted on update.

**Unlock rules.** A skill unlocks when all hold:
- 1 unspent point;
- its prerequisite is unlocked (chains below);
- the tree's gate stat (A4) is ≥ 20 for Tier 2, ≥ 35 for Tier 3, ≥ 50 for the
  ultimate, and the ultimate also needs both Tier 3 skills of its tree and one
  boss defeated.

**Chains** (matches the drawn tree in SPEC.md):

| Tree | Left chain | Right chain | Ultimate needs |
| --- | --- | --- | --- |
| Body | Iron Body → Momentum → Limit Break | Light Feet → Second Wind → Recovery Aura | Limit Break + Recovery Aura |
| Mind | Deep Work → Hyperfocus → Focus Chain | Clear Mind → Insight → Literature Sense | Focus Chain + Literature Sense |
| Self | Restful → Streak Shield → Bonds | Curious → Clarity → Mentor's Eye | Bonds + Mentor's Eye |

**Respec.** Returns every point. Free once per rank-up (a respec token);
otherwise 50 Essence. Unlocks are lost, cooldowns are not reset.

**Full definitions.** "Day", "week" and "month" are game periods: the day
starts at the player's reset time, the week runs Sunday to Saturday, the
month is the calendar month of the game day.

| Skill | Kind | Cost and limit | Exact effect |
| --- | --- | --- | --- |
| Iron Body | Passive | — | +10% STR mastery |
| Light Feet | Passive | — | +10% AGI mastery |
| Momentum | Passive | — | +3% Body XP per consecutive day with a completed Body quest, cap +15%; rest days neutral (A3) |
| Second Wind | Active | 20 MP, 1/day | Replace one open Body daily with "Mobility 15 min" (Easy). It still counts as the day's Body objective. Not on rest days |
| Limit Break | Active | 40 MP, 1/week | One open Body quest → largest target within the weekly cap; 3× XP on completion (A1) |
| Recovery Aura | Passive | — | Rest-day HP restore ×1.5 |
| Unbroken | Active (ult) | 50 MP, 1/month | During the day after a missed Daily: yesterday's Daily counts as completed at 50% XP, streak restored, the −15 HP refunded, the penalty quest removed. Not after a Shield-covered miss (A9) |
| Deep Work | Passive | — | +10% INT mastery |
| Clear Mind | Passive | — | +10% FOC mastery |
| Hyperfocus | Active | 30 MP, 12 h cooldown | Start a 90-min Focus Session linked to one open Mind quest. Full 90 min: +90 min progress on that quest, 2× its XP and 2× INT mastery. Ended early: minutes count, no bonus, MP not refunded. Do Not Disturb on if the player granted access |
| Insight | Active | 20 MP per charge; charges/day = 1 + ⌊WIS / 20⌋ | You type the stuck problem; the app shares `Request: Stuck: …` with your STATUS (A7). Claude's 3 sub-quests come back through Import |
| Focus Chain | Passive | — | +5% Mind XP per consecutive day with ≥ 60 min of completed Mind work (a timed Mind quest or a Focus Session), cap +25%; rest days neutral |
| Literature Sense | Passive | — | Quests with unit `papers` or tag `paper` pay full mastery to both INT and WIS |
| Eigenstate Collapse | Active (ult) | 80 MP, 1/week | Pick one open quest or boss task: 3× XP; the Quests tab shows only it until done or day end ("Show all" still works). Optional share: "Request: Collapse: which one task?" (A8) |
| Restful | Passive | — | +10% VIT mastery |
| Curious | Passive | — | +10% WIS mastery |
| Streak Shield | Passive | — | First missed Daily each week: streak kept, no penalty quest. HP rules unchanged |
| Clarity | Active | 15 MP, 1/day | 3-line journal: what moved, what's stuck, tomorrow's one thing. Creates up to 3 `extra` quests for tomorrow from the third line. Text stays on the phone, never in STATUS |
| Bonds | Passive | — | Quests tagged `social` also restore 10 HP |
| Mentor's Eye | Active | 30 MP, 1/week | Shares `Request: Review` with a **28-day** history section in STATUS (A7) |
| Equilibrium | Passive (ult) | — | Completing the rest-day quest fully restores HP and MP and gives +10% XP the next day |

**Cooldown reduction** (FOC, ≤ 40%) applies only to hour-based cooldowns
(Hyperfocus). Per-day, per-week and per-month limits reset at the period
boundary and aren't reduced.

**Observer tree.** Shown locked at the edge of the Skills screen with "A-rank"
as its only hint. No effects in Phase 4.

---

## 2. Powers: the casting framework

Actives share one framework in `:core:engine` (rules) and a feature flow
(UI). Passives are pure JSON effects folded by `RewardCalculator`; actives
are a small set of coded **power handlers** keyed by skill id, because each
needs a target or a flow.

- **Where you cast.** From the Skills screen; by long-pressing a quest (powers
  that take a quest as their target); from the Boss Arena (Eigenstate
  Collapse on a boss task).
- **Cast check.** Unlocked, enough MP, inside its limit or cooldown, valid
  target, not in a state that forbids it (rest day for Second Wind, no cap
  headroom for Limit Break). The UI says which check failed, in one line.
- **Confirm.** One confirmation card showing cost, effect and target. No
  undo after confirm, except: a cast whose target quest is deleted the same
  day refunds MP.
- **Log.** Every cast is a `PowerCast` row (active → completed or expired),
  so effects survive app restarts and the daily reset handles expiry.
- **Interaction with the bridge.** Claude may suggest a power in prose; it
  can never cast one. No new bridge actions for powers.

---

## 3. Classes

**Class Awakening** happens when you reach level 10, independent of the
D-rank trial, so a failed trial never delays your class.

1. Scores and choices per A5. Ties are offered side by side.
2. A short cinematic: the six stats rise as columns, the two leading ones
   ignite, the class sigil forms. The player confirms the choice.
3. The class title appears under the name (the player can still wear another
   title), its lore line unlocks, and its silhouette layer appears.

**Class bonus.** +10% mastery to the class's two stats and +10% XP on quests
whose stat is one of them. Polymath: Resonance instead (below).

**Unique skills** (granted with the class, no point cost):

| Class | Skill | Exact effect |
| --- | --- | --- |
| Theorist | First Principles | 1/day: replace one open Mind daily with "Derivation: …" (you name it), same tier |
| Computationalist | Parallel Runs | 1/day: link two open timed Mind quests to one session; completing the longer one completes both |
| Vanguard | Overload | 1/week: all Body quests completed that day pay 2× XP (A2) |
| Ascetic | Stillness | 1/day: a quest tagged `meditation` refills 25% of max MP |
| Wanderer | Long Walk | 1/day: a quest tagged `walk` of ≥ 30 min also pays Normal INT mastery |
| Polymath | Resonance | +5% XP on each quest for every realm with a completed quest today, max +15% |

**Changing class.** Free at each rank-up (you're offered the choice again
with fresh scores); otherwise not possible.

**Evolutions.** At B-rank and S-rank the class evolves automatically
(SPEC.md table). Phase 4 builds the logic, names and lore lines; the visual
layer for evolved forms reuses the base class layer with the new rank aura.
Test with the debug panel (section 9).

---

## 4. Rank trials

- **Unlock.** Level ≥ threshold and current rank one below: E→D at 10,
  D→C at 25, C→B at 45, B→A at 70, A→S at 100. Rank is stored, never derived
  from level. A player already past a threshold gets the trial on update.
- **Shape.** A special Gate (`kind = RANK_TRIAL`) of 7 days with **2
  objectives per realm**, each sized at the target rank's Gate effort (D +20%,
  C +35%, B +50%, A +70%, S +100%). Body objectives obey the weekly cap; the
  remaining effort moves to Mind and Self, as with high-rank Gates.
- **Rules.** Doesn't count toward the 2-open-Gate limit. Claude can't propose
  or edit one (it sees it in STATUS and can plan around it).
- **Pass.** All 6 objectives complete before the trial closes. Rewards: the
  new rank, its aura, a chest of the new rank's row, a respec token, the
  rank's lore line, and the class re-choice offer.
- **Fail.** XP for what you cleared (Gate payout rule), no chest, the
  "Measurement noise" lore line, and a 7-day wait before the next attempt.
- **Rank-up sequence.** The 3–4 s full-screen sequence from SPEC.md ("the
  new rank glyph is forged from light"), then the Status screen with the new
  aura.
- **Σ.** Out of scope. Only the hidden lore line shown after S exists.

---

## 5. Avatar evolution

Drawn entirely in Compose (Canvas and vector paths); no external art, all
shapes original. Back to front:

1. **Aura** — rank colour from SPEC.md (E faint grey-blue, D System Blue,
   C Glow Cyan, B Arcane Violet, A Legend Gold, S white-blue blaze; the Σ
   palette exists but is unreachable). A radial glow plus a slow particle
   field.
2. **Level marks** — particle count = min(8 + ⌊L / 2⌋, 60); one thin orbit
   ring per 10 levels, max 10.
3. **Silhouette** — the hooded figure from onboarding.
4. **Class layer** — one per class: a cloak cut and a geometric chest sigil
   (circle-and-line motifs, no letters). Evolutions brighten the same layer.
5. **Gear layers** — from Phase 3.
6. **Name and title** — under the figure.

Shown on the Status Window, in Inventory, and in the rank-up sequence.
With "Remove animations" on, the aura and particles are static.

---

## 6. Lore codex

Phase 3 built the Codex screen and the event lines. Phase 4 completes it as
the Observer's **Record**:

- **Record view.** All unlocked entries in unlock order, with filters:
  World · Ranks · Classes · Skills · Arcs · Bosses · Gates · Achievements.
- **Progress without spoilers.** "23 of 61 recorded". Locked entries show no
  title or hint; hidden ones are counted but never described.
- **Unlock moment.** A `◆ NEW RECORD` toast with the typewriter effect; an
  unread dot on Status and on the entry until opened.
- **New lines.** The ones in the appendix go into
  `core/data/src/main/assets/content/lore.json`. The loader already enforces
  ≤ 200 characters and the franchise-name filter; add a test that every new
  id is unique and every trigger exists.

---

## 7. Claude bridge: STATUS v3

No new actions. Two changes, both backward compatible:

**STATUS v3** adds three lines after `Stats:`:

```
Class: Theorist (INT+WIS) · bonus +10% INT/WIS
Powers: Hyperfocus ready · Insight 2/2 · Limit Break used this week · Eigenstate Collapse locked
Rank trial: D · open · 4 days left · 3/6
```

and, for Mentor's Eye only, a `Last 28 days:` section instead of
`Last 7 days:`. Powers lists only unlocked actives: `ready`, `n/m` charges,
`cooldown 5 h`, or `used this week|month`.

**`add_quest` gains two optional fields:**
- `stat`: STR, AGI, INT, FOC, WIS or VIT (default: the realm's first stat).
- `tags`: any of `social`, `paper`, `meditation`, `walk`, `mobility`, `sleep`,
  `journal`, `derivation`, `simulation`, `writing`. Unknown tags are dropped
  silently; the card shows the tags kept.

v1 and v2 blocks stay valid. Update
`docs/ASCENT-System-Project-Instructions.md` in the same task: the STATUS v3
lines, the two fields, the tag list, and one rule — "you may suggest a power
by name in your reply; never assume it has been cast."

---

## 8. Data model and content

**Room** (one version bump, with an exported schema and a migration test;
`fallbackToDestructiveMigration` stays banned):

| Entity | Change |
| --- | --- |
| Player | + `classId`, `classChosenAt`, `rank` (stored), `respecTokens` |
| PlayerSkill | `skillId`, `unlockedAt`, `periodKey`, `usesInPeriod`, `lastCastAt` |
| PowerCast | `id`, `skillId`, `castAt`, `targetId`, `mpSpent`, `status`, `expiresAt` |
| Gate | + `kind` (`NORMAL`, `RED`, `RANK_TRIAL`), `targetRank`, `attempt`, `retryAt` |
| Quest | + `tags` (if not already present) |
| LoreEntry | + `category`, `readAt` |

**Content assets** (JSON, validated at load, with schema tests):
`skills.json` (tree, tier, chain, gate, kind, cost, limit, effects),
`classes.json` (stats, bonus, unique skill, evolutions),
`rank_trials.json` (rank → effort, rewards), `avatar.json` (rank palettes,
class layer ids, particle rules), plus the new `lore.json` lines.

---

## 9. Screens, motion, sound, debug

- **Navigation.** Skills becomes the fifth tab: Status · Quests · Console ·
  Gates · Skills.
- **Skills screen.** Pan-and-zoom node graph (three columns, glowing links,
  locked nodes dimmed), node sheet with effect, cost, limit and unlock
  requirements, an Unlock button and a Cast button.
- **Status.** Avatar (section 5), class and title, MP bar, a "Powers ready"
  row with one-tap cast for untargeted powers, the rank-trial card when open.
- **Cast animation.** A sigil ring draws itself around the target
  (≈ 600 ms), then the effect lands. Sound from the existing Kenney sets in
  `docs/sounds/SOUNDS.md`; add picks for cast, unlock, class, rank-up.
- **Focus Session (A6).** Full-screen dark timer with a pulsing ring, linked
  quest shown, "End early" with confirm. Requests notification-policy access
  for Do Not Disturb only when Hyperfocus is first cast; works without it.
- **Debug panel** (debug builds only, never in release): set level, stats,
  rank, MP; grant points and Essence; fast-forward the day or week. Needed to
  test classes, trials and evolutions without waiting months.

---

## 10. Tests that must exist

- **Unlocks:** points, gate stat (A4), prerequisites, ultimate conditions;
  respec returns exactly the points spent.
- **Periods:** day/week/month keys across reset times (04:00 and 05:30),
  Sunday week boundary, month boundary; cooldown reduction only on hour-based
  cooldowns.
- **Caps (property tests):** for random combinations of gear, skills, class
  and streak, passive XP multiplier ≤ ×1.75 (A10) and gear ≤ +30% per realm;
  Limit Break never produces a Body target above the weekly cap (A1).
- **Shield and Unbroken:** ordering per A9, including two misses in a week.
- **Classes:** scoring, ties, Polymath eligibility, re-choice at rank-up.
- **Rank trials:** 2 objectives per realm, Body within the cap, pass and fail,
  7-day retry, not counted toward the Gate limit.
- **Lore:** new lines ≤ 200 characters, pass the franchise filter, unique ids,
  every trigger exists.
- **Bridge:** golden test for STATUS v3; `stat` and `tags` parsing; unknown
  tags dropped; v1 and v2 blocks still parse.
- **Migration:** previous schema → new schema with player data intact.

---

## 11. Build order

0. **Amendments** — once approved, apply section 0 to SPEC.md and note it in
   DEVLOG.md.
1. **Content** — `skills.json`, `classes.json`, `rank_trials.json`,
   `avatar.json`, new lore lines; schemas and loader tests.
2. **Engine** — unlock rules, MP and periods, passive effects and the ×1.75
   cap, power handlers' rules, class scoring, rank-trial generation and pass
   check, respec. Pure Kotlin, all section 10 engine tests.
3. **Data** — Room migration and repositories.
4. **Debug panel** — so everything after this can be tested quickly.
5. **Skills screen** — graph, node sheet, unlock, respec.
6. **Casting framework** — cast checks, confirm card, PowerCast log, expiry
   at reset, cast animation and sound.
7. **Powers, in order** — Second Wind, Clarity, Limit Break, Insight and
   Mentor's Eye (share requests), Eigenstate Collapse, Unbroken.
8. **Focus Session + Hyperfocus** (A6).
9. **Class Awakening** — cinematic, choice, bonus, unique skills, Status.
10. **Rank trials** — generation, trial card, pass and fail, rank-up
    sequence, rewards, Gate board using the new rank.
11. **Avatar evolution** — layered renderer on Status, Inventory and rank-up.
12. **Record** — codex completion, filters, toasts, unread dots.
13. **Bridge** — STATUS v3, `stat` and `tags`, docs update, parser tests from
    real Claude replies.

**Exit:** one week of real use in which you cast at least three different
powers, choose a class, attempt the D-rank trial, and see your avatar reflect
rank and class, with no regressions in the daily loop.

**Out of scope:** Observer tree effects, Σ, Health Connect (Phase 5),
Assessment charts (Phase 6).

---

## Appendix — new lore lines

Follow `docs/lore-bible.md`. Each is final text for `lore.json`; ids are
suggestions. Generic class-evolution lines unlock when the evolution happens.

| id | Trigger | Title | Text |
| --- | --- | --- | --- |
| rank_trial_start | A rank trial opens | Calibration | Seven days, three realms. The Observer calibrates against the level you claim. |
| rank_trial_fail | A rank trial fails | Measurement noise | The trial closed below threshold: noise, not a verdict. The instrument resets in seven days. |
| rank_d | Reach D | First excited state | The excitation holds above the ground state for the first time. The Observer now measures you in finer units. |
| rank_c | Reach C | Metastable | You no longer fall back each night. A metastable state is still not a stable one; keep feeding it. |
| rank_b | Reach B | Phase boundary | Small pushes now move large regions. What once cost effort has become the configuration you settle into. |
| rank_a | Reach A | Far from equilibrium | Few trajectories reach this energy. The lattice bends near you, and something in the record has begun to look back. |
| rank_s | Reach S | Critical point | Here, fluctuations span every scale. One step further, the measurement and the measured may no longer differ. |
| sigma_hint | Shown after S | Σ | There is a state where the record and the one recorded coincide. The Observer has never measured it, and waits to see if you will. |
| class_theorist | Choose Theorist | Least action | Your effort bends toward first principles. The Observer files you among those who derive before they believe. |
| class_computationalist | Choose Computationalist | Many runs | You trust the ensemble over the single sample. The Observer files you among those who measure by iteration. |
| class_vanguard | Choose Vanguard | Load-bearing | Your record is written in repetitions. The Observer files you among those who move mass to move themselves. |
| class_ascetic | Choose Ascetic | Low noise | You remove before you add. The Observer files you among those whose signal rises as the noise falls. |
| class_wanderer | Choose Wanderer | Mean free path | You think in motion. The Observer files you among those whose ideas arrive at walking speed. |
| class_polymath | Choose Polymath | Every mode | Your energy spreads across every degree of freedom. The Observer cannot file you under a single heading. |
| evo_field_theorist | Theorist → Field Theorist | From particles to fields | Single derivations now join into continuous ones. Your class has changed phase. |
| evo_simulationist | Computationalist → Simulationist | Converged | Your runs agree with each other now. Your class has changed phase. |
| evo_warbringer | Vanguard → Warbringer | Inertia | Stopping now costs more than going. Your class has changed phase. |
| evo_monk | Ascetic → Monk | Noise floor | Your quiet has a measurable floor. Your class has changed phase. |
| evo_pathfinder | Wanderer → Pathfinder | Long trajectories | Your walks now cover more ground than your maps. Your class has changed phase. |
| evo_renaissance | Polymath → Renaissance | Coupled modes | Your realms now drive each other. Your class has changed phase. |
| final_architect | → Architect of Laws | Conserved quantities | You no longer follow rules so much as find the ones that hold. The record lists you as a source. |
| final_laplace | → Laplace's Demon | Full state | Given the state, you compute the rest. The Observer notes the resemblance and says nothing. |
| final_titan | → Titan | Constant | What you carry no longer moves you. The record lists you as a constant. |
| final_still_point | → Still Point | Reference frame | Everything around you fluctuates; you do not. The record lists you as the reference frame. |
| final_cartographer | → Cartographer | The chart | Every path you walked is now a coordinate. The record lists you as a chart. |
| final_universalist | → Universalist | All sites | You have visited every site of the lattice that matters to you. The record has run out of headings. |
| skill_first | First skill unlocked | First operator | You have learned to act on the state instead of only living in it. Spend effort, receive change. |
| power_first_cast | First power cast | Applied | The first cast is recorded. Power, here, is effort made earlier and spent now. |
| ult_unbroken | Unlock Unbroken | Unbroken chain | Once a month, one missed link no longer ends the chain. Not mercy: tolerance. |
| ult_collapse | Unlock Eigenstate Collapse | Collapse | Many tasks in superposition, and you measure one. The rest wait, unchanged, until you look again. |
| ult_equilibrium | Unlock Equilibrium | Detailed balance | Rest and effort now flow both ways at equal rates. This is not stillness; it is balance. |
| observer_glimpse | Reach A (Observer tree appears) | A fourth axis | A tree with no realm has appeared at the edge of the record. Its first branches are already lit. |
| respec | First respec | Reconfiguration | Points return to the pool. The trajectory you walked stays fixed; only the operators change. |
