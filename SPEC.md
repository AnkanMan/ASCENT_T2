# ASCENT — Personal System RPG: Design Spec

Nov 11, 2026 · @Tirthankar Mondal

## Concept and lore

ASCENT is a private, Solo Leveling–inspired life RPG for one player. An AI "System" issues quests across fitness, research and personal growth, and real effort becomes XP, stats, powers and rank. Every number on the character sheet maps to something you actually did.

The world is split into three realms, each a source of quests and Gates:

- **Body** — strength training, cardio, mobility. Feeds STR and AGI.
- **Mind** — research and deep work: derivations, simulations, code, writing, papers. Feeds INT and FOC.
- **Self** — sleep, recovery, reading, journaling, people, new skills. Feeds WIS and VIT.

**Lore (original).** The world is a lattice of possible selves, and most people never leave their ground state. The System is an Observer that measures will in units of effort, and it has detected an excitation in you. Gates are fluctuations where a higher state briefly becomes reachable. Clear them and the excitation grows; stop acting and you relax back toward equilibrium. Ranks run E → D → C → B → A → S, then a hidden final rank, Σ (Singularity).

**System voice.** Terse, cold, second person, uppercase headers. Sample lines:

- `◆ QUEST ISSUED — The Body Gate stirs. 4 objectives. Expires 23:59.`
- `◆ WARNING — Daily objectives incomplete. A penalty trial has been prepared.`
- `◆ LEVEL UP — 23 → 24. Three attribute points await allocation.`
- `◆ HIDDEN CONDITION MET — You trained before dawn five days running. Title unlocked: "First Light".`

**Design pillars.** Honest: stats come only from real, logged activity. Adaptive: difficulty tracks your performance in each realm. Dark in look, kind in rules: penalties never cost health, and you never lose a level.

## Visual and audio identity

The look is a dark void with glowing blue holographic panels that materialize, flicker and glitch. Every screen is a "System window": translucent glass, a 1 dp glowing border, corner brackets, a faint hex grid behind it and a 4% scanline overlay.

| Token | Hex | Used for |
| --- | --- | --- |
| Void | #04060C | App background |
| Panel | #0A1424 at 70% opacity | Glass panels |
| System Blue | #2E8BFF | Borders, headers, primary glow |
| Glow Cyan | #00D4FF | Progress bars, highlights, XP |
| Arcane Violet | #8B5CF6 | Skills, Epic rarity |
| Legend Gold | #F5B942 | Level-up, Legendary rarity |
| Penalty Red | #FF3B5C | Warnings, penalties, boss HP |
| Mist | #C9D6EA | Body text |

**Type.** Orbitron or Rajdhani for headers, JetBrains Mono for numbers and stats, Inter for body text. All three are free Google Fonts.

**Signature animations.**

- **Window materialize** — panel scales from a thin line to full height in about 220 ms with two brightness flickers.
- **System notification** — 120 ms glitch (horizontal slice offsets), then typewriter text with a blinking cursor.
- **Quest complete** — checkmark strokes in, the XP number floats up into the XP bar, bar fills with an ease-out.
- **Level up** — screen dims, a vertical light column rises, the level number rolls, particles burst, gold flash.
- **Boss hit** — 6 dp screen shake, red damage number, a chunk breaks off the HP bar.
- **Rank up** — a 3–4 s full-screen sequence where the new rank glyph is forged from light.
- **Ambient** — slow drifting particles on the Status screen; all motion respects the system "Remove animations" setting.

Build these with Compose animation APIs and a small Canvas particle system; use Lottie only for the long level-up and rank-up sequences. Use `RenderEffect` blur for glow on Android 12+, with a drawn-shadow fallback below that.

**Sound.** Short, cold, synthetic. A two-note crystalline chime for System notifications, a soft click plus shimmer for quest completion, a 1.5 s rising swell for level-up, a low glitchy buzz for penalty warnings, a deep thud for boss hits, and an optional dark drone loop on the Status screen. Source them from CC0 libraries (Kenney audio packs, Freesound with the CC0 filter) or synthesize them in jsfxr. Play effects through `SoundPool`, pair each with a haptic pulse, and stay silent when the phone is on silent.

## Character creation and attributes

Onboarding is the "Awakening": a 5-minute dark, cinematic sequence that measures your real baseline and turns it into starting stats.

1. **Detection.** Black screen, one blinking cursor, then `◆ AN EXCITATION HAS BEEN DETECTED. BEGIN MEASUREMENT?`
2. **Identity.** Name, then a starting title of "The Unmeasured". Pick a hooded silhouette avatar; aura colour and gear unlock later.
3. **Goals.** Your first System Console conversation: say what you want in each realm, and the System proposes one to three goals per realm for you to confirm, e.g. Body: 30 strict push-ups; Mind: finish the Toda truncation paper draft; Self: 7 h sleep on weeknights.
4. **Measurement.** Max push-ups and squats in one set, a 1 km run time or average daily steps, typical deep-work hours per day, pages read per week, average sleep.
5. **Rhythm.** Daily reset time (default 04:00), rest day(s), quiet hours for notifications.
6. **First quest.** The System issues Daily Quest #1 with a short guided tutorial.

Each stat starts at 10 and shifts ±5 from the measurement, judged against your own goals rather than population norms. For each measurement you enter a current value and a goal; with p = min(current / goal, 1) (for a 1 km time, where lower is better, p = min(goal / current, 1)), the shift is round(10 × p − 5). STR uses the mean shift of push-ups and squats; AGI uses steps or the 1 km time; INT, deep-work hours; WIS, pages per week; VIT, average sleep. FOC has no measurement and starts at 10.

| Stat | Realm | Grows from | Gameplay effect |
| --- | --- | --- | --- |
| STR — Strength | Body | Strength sessions, reps, load lifted | Raises max HP |
| AGI — Agility | Body | Steps, runs, cardio and mobility minutes | Critical chance: a quest pays 2× XP |
| INT — Intellect | Mind | Deep-work minutes, commits, derivations, simulations | Raises max MP |
| FOC — Focus | Mind | Unbroken focus blocks, phone-free time, meditation | Shorter skill cooldowns |
| WIS — Wisdom | Self | Reading, papers outside your field, journaling, learning | Extra Insight charges, unlocks lore |
| VIT — Vitality | Self | Sleep, proper meals, hydration, rest days | Raises max HP and HP regeneration |

**How stats grow.** Two channels. Organic growth: activity in a stat's domain fills its mastery bar, and the stat rises by 1 when mastery reaches 50 + 10 × (current value), with any overflow carried over. Each completed quest pays mastery to its stat: Easy 5, Normal 10, Hard 20 (Trivial 2, Extreme 40), times any mastery effects. Every quest has a stat; quests from Claude or added by hand default to the realm's first stat (STR, INT or WIS) and can be changed. Free points: every level-up grants 3 points you allocate by hand on the Status screen.

**Derived values.**

- Max HP = 100 + 5 × STR + 10 × VIT
- Max MP = 50 + 5 × INT + 3 × FOC
- Critical chance = min(25%, 0.5% × AGI)
- Cooldown reduction = min(40%, 1% × FOC)

**HP** is your recovery state, not a life bar. You lose 15 for a missed Daily Quest and 10 for a night under 6 h of sleep. You regain 20 for a night of 7 h or more and 30 for completing a rest-day quest. Below 30% you enter Fatigue (see the adaptive engine). At 0 you "collapse": no XP is lost, and the next day becomes a mandatory recovery day.

**MP** refills at every daily reset and is spent to activate powers.

## XP, levels, ranks and evolution

The curve is tuned so level 10 arrives in about 3 weeks and S-rank (level 100) in about 3.8 years of full Daily Quests — roughly the length of a PhD. Early levels come fast; late ones are earned.

XP needed to go from level n to n + 1:

```latex
\mathrm{XP}_{n \to n+1} = \left\lfloor 100\, n^{1.3} \right\rfloor
```

XP paid by a single quest:

```latex
\mathrm{XP}_{\text{quest}} = B_{\text{tier}} \times d_{\text{realm}} \times (1 + 0.05\, L) \times \min(1 + 0.02\, s,\ 1.5) \times c
```

Here B is the tier base (Trivial 20, Easy 40, Normal 80, Hard 150, Extreme 300), d the realm's current difficulty multiplier, L your level, s your current streak in days, and c is 2 on a critical, otherwise 1. A full Daily Quest pays about 300 × (1 + 0.05 L) XP.

**Bonus cap (Phase 4, A10, A11, A16).** Passive bonuses from gear, skills and class add together, and their sum on any one quest is capped at +75% (a ×1.75 multiplier). Gear's own cap of +30% per realm still applies inside that. The streak term stays a separate multiplier, as in the formula above. Powers that multiply a quest or a day (×2, ×3, including Overload) apply on top of the cap, and the critical multiplier is applied last. From Phase 5, the grind guard's multiplier m scales the base of a self-added quest before all of these (Durability and honest metrics).

**Ranks.** Reaching the level threshold unlocks a Rank Trial: a 7-day special Gate with objectives in all three realms. Failing costs nothing except a 7-day wait before retrying.

| Rank | Level needed | Time at full pace | Aura colour |
| --- | --- | --- | --- |
| E | 1 | Start | Faint grey-blue |
| D | 10 | \~3 weeks | System Blue |
| C | 25 | \~4 months | Glow Cyan |
| B | 45 | \~1 year | Arcane Violet |
| A | 70 | \~2.1 years | Legend Gold |
| S | 100 | \~3.8 years | White-blue blaze |
| Σ (hidden) | 100 + thesis raid cleared | End of PhD | Inverted black and gold |

Times assume every Daily Quest is completed; real pace will run 20–30% slower, which is fine.

**Classes.** At level 10, alongside the D-rank trial, the System awakens a class from your two highest stats. A class gives +10% XP and +10% mastery to its two stats, and one unique skill. Classes evolve at B-rank and again at S-rank.

**Choosing a class (Phase 4, A5, A14).** Each paired class scores the sum of its two stats. Polymath is eligible when the highest stat is at most 1.15 × the lowest. You pick from the two top scores (ties shown side by side), plus Polymath if eligible. At each rank-up you may choose again with fresh scores; the choice is made before any evolution, so at B and S the newly chosen class evolves at once. Otherwise the class can't be changed.

| Base class (Lv 10) | Top stats | Evolution (B) | Final form (S) | Unique skill |
| --- | --- | --- | --- | --- |
| Theorist | INT + WIS | Field Theorist | Architect of Laws | First Principles: one quest per day may be replaced by a derivation of your choice |
| Computationalist | INT + FOC | Simulationist | Laplace's Demon | Parallel Runs: two Mind quests can tick at once from one long simulation |
| Vanguard | STR + AGI | Warbringer | Titan | Overload: once a week, all Body quests completed that day pay 2× XP; targets unchanged (A2) |
| Ascetic | FOC + VIT | Monk | Still Point | Stillness: a meditation block refills 25% MP |
| Wanderer | AGI + WIS | Pathfinder | Cartographer | Long Walk: thinking walks count for both Body and Mind |
| Polymath | No stat leads by >15% | Renaissance | Universalist | Resonance: +5% XP for every realm completed today |

Your avatar evolves with you: the aura colour follows rank, the silhouette changes with class and equipped gear, and a title of your choice displays under your name.

## Quest system

The Daily Quest is the heartbeat: three to five objectives across all three realms, issued at every reset and verified automatically wherever possible. Its objectives are the quests issued for today, from templates or applied from Claude. The streak rises by 1 only when every objective is done. Quests you add yourself pay XP but don't count toward the Daily or the streak; from Phase 5, past the third in a game day they pay diminishing XP (the grind guard).

| Type | When it appears | What it asks | Reward | If missed |
| --- | --- | --- | --- | --- |
| Daily Quest | Every reset (default 04:00) | 3–5 objectives: 1 Body, 1–2 Mind, 1–2 Self | \~300 × (1 + 0.05 L) XP, mastery, streak +1 | Penalty Quest, −15 HP, streak resets unless shielded |
| Penalty Quest | Within 24 h of a missed Daily | One small corrective task, e.g. a 15-min walk plus one line on what got in the way | +10 HP back (the streak stays reset) | −10 HP, nothing more |
| Side Quest | 0–3 visible, from balance templates, Claude or your errands | Optional tasks aimed at weak realms or open goals | 0.75× tier XP, 10% loot roll | Expires silently |
| Emergency Quest | \~2 per week, inside your active hours | Short and timed, e.g. "Within 2 h: 45 min focused work on X" | 1.5× XP, 20% roll for a Rare or better item | No penalty |
| Hidden Quest | Never announced | A behaviour condition, e.g. 5 dawn workouts in a row | Title, lore entry, Epic item | — |
| Gate / Boss | Weekly / per milestone | Multi-day objectives (next sections) | Large XP, gear, titles | Gate closes; boss can be re-challenged |

A Daily Quest as it appears on screen:

```
◆ DAILY QUEST — PREPARATION OF THE VESSEL
  [BODY]  Push-ups ................................ 0 / 40
  [BODY]  Steps (auto · Health Connect) ...... 3,120 / 8,000
  [MIND]  90 min deep work on KPZ (auto · focus timer)
  [SELF]  Read 15 pages, lights out by 00:30
  REWARD  420 XP · mastery ×4 · streak 12 → 13
  EXPIRES 17:42:09
```

**Verification.** Three methods, chosen per objective:

- **Health Connect** — steps, exercise sessions and sleep are read automatically. *Planned for Phase 6, after the Release; until then these are checked off by hand.*
- **Focus timer** — the in-app session timer is its own proof for deep-work and reading blocks.
- **Manual check-off** — everything else, on the honour system, with an optional note or photo. It is a single-player game: cheating only corrupts your own character sheet.

**Mind quest library (examples).** These are the kind of quests Claude proposes for a research project; they are personal examples, not shipped in the app. From the Release (R3), the app ships only generic templates, and your own quests live in your data.

- Push one commit to dipole-sep-hydrodynamics with a matching RESEARCH\_LOG.md entry.
- Write one clean page of the Toda lattice truncation derivation.
- Run one parameter sweep for the KPZ project and add the figure to RESULTS.md.
- Read one arXiv paper and write a three-line summary.
- Close one TODO.md item or sharpen one open entry in QUESTIONS.md.

**Body and Self seeds.** Push-ups, squats, planks, runs, 10-minute mobility, step targets; journaling three lines, calling a friend or family member, 20 minutes on a new skill, no phone for the first 30 minutes after waking, cooking a proper meal.

**Your own quests.** You can add any task. The System estimates its time and effort, assigns a tier and XP, and asks you to confirm.

## System Console: talk to the System, set your goals

Talking to the System is how you set and change goals in ASCENT. You type or speak in plain language, the System turns it into goals and quests, and nothing enters your log until you apply it.

&#91;embedded content: System Console loop · 5 steps, back to the conversation\]

A dismissed proposal changes nothing; the System adjusts on your next message.

**What you can say.**

- **Plan today** — "Two hours of meetings, low energy, KPZ runs need checking." The System issues 3–5 daily quests sized to that.
- **Set the week** — it reviews last week, then proposes 3–6 weekly goals across the three realms.
- **Set long-term goals** — "Finish the Toda truncation draft by March." Milestone-sized goals can become bosses.
- **Report progress** — "Did 40 push-ups and two hours on the dipole runs." It proposes marking the matching quests done.
- **Adjust the load** — "Deadline Friday" or "I'm sick" lowers or reshapes targets for the day or week.
- **Ask for help** — a progress review, or breaking a stuck problem into sub-quests (the Insight power runs here).

**Goal hierarchy.** Three levels, each feeding the one below:

1. **Long-term goals** — weeks to months, one to three per realm; milestone-sized ones become bosses.
2. **Weekly goals** — 3–6 per week, each linked to a long-term goal where one exists.
3. **Daily quests** — 3–5 per day, drawn from your weekly goals, plus any you add yourself.

Every quest and weekly goal links to its parent, so finishing quests visibly moves the goal above it.

**The week** runs Sunday to Saturday and turns over at the Sunday daily reset: last week's weekly goals and quests close, the weekly System Assessment covers the seven days just ended, and Sunday planning sets the new week.

**Check-in rituals.** Three optional conversations, each opened by a System notification at a time you choose.

| Ritual | When | What happens |
| --- | --- | --- |
| Morning briefing | Daily reset or first open | The System issues today's quests from your weekly goals; reply to adjust them before applying |
| Evening debrief | A time you set, e.g. 21:30 | You report the day in a sentence or by voice; it proposes completions and notes what slipped |
| Weekly planning | Sunday | A review of the week and next week's goals, merged with the weekly System Assessment |

**Proposal cards.** Every change the System suggests appears under its message as a card: add a goal, add a quest, mark done, change a target, remove, or turn a goal into a boss. Tick the ones you want and tap Apply selected, or Dismiss. Before anything is applied, the adaptive engine checks each target against your current difficulty band and clamps it if needed.

**What the System knows.** Each reply is built from:

- your Dossier, an editable profile of your research, constraints and preferences;
- your current goals, quests, stats and streak;
- a summary of the last 14 days;
- the current Claude chat; start a fresh chat each week to keep it focused.

**Input and voice.** You talk in the Claude app, so typing and Claude's own voice input both work — handy for a quick report after a workout. Ask the System offers quick starters that ride along with the status: Plan my day, Set this week, Report, Low energy today.

**Strictness.** A dial (Gentle, Standard, Ruthless) changes only the System's tone. The safety floors from the adaptive engine are identical at every setting.

**Offline.** ASCENT itself never needs a connection. Without Claude, daily quests come from templates and the adaptive engine, and you can always add and tick quests by hand.

**How Claude connects: the share bridge.** ASCENT never calls an AI service itself. You plan with Claude in the Claude app, on your own Claude plan, inside a Claude Project called ASCENT System that holds the System's instructions.

1. **Ask the System** in ASCENT builds an ASCENT STATUS message — date, level, streak, difficulty per realm, goals and quests with their ids, the last 7 days, Dossier notes — and opens the Android share sheet. Pick Claude, or copy and paste.
2. You talk to the System in that Claude chat: plan, report, adjust, review.
3. Claude ends any proposal with one `ascent` code block. Share the reply to ASCENT, or tap **Import from Claude**, which reads the clipboard.
4. ASCENT parses the block, validates every action and shows the proposal cards described above.

The block Claude returns:

```json
{"v":1,"actions":[
  {"op":"add_goal","horizon":"week","realm":"MIND","title":"Finish KPZ scaling figures","target":3,"unit":"figures"},
  {"op":"add_quest","scope":"daily","realm":"BODY","title":"Push-ups","tier":"normal","target":40,"unit":"reps","goal":null},
  {"op":"complete","id":"q7f2a"},
  {"op":"edit","id":"q1b9c","target":30},
  {"op":"remove","id":"q9z8y"}
]}
```

Claude's `tier` is easy, normal or hard, mapping to the Easy, Normal and Hard tiers; Trivial and Extreme aren't available to Claude. `recurring` (true for a quest that repeats daily) and `goal` are optional; a missing `recurring` means false and a missing `goal` means null.

**Reference files.** The Claude Project instructions live in the repo as `docs/ASCENT-System-Project-Instructions.md` and are the contract for this format; change both together. The web prototype, [ASCENT — System Console](https://claude.ai/artifact/8PZMPxzDZL9eMcfEf5grV3), shows the chat, cards and tabs, and works for planning until the app is ready.

## Adaptive difficulty engine

Each realm runs its own controller that holds your completion rate near 80%: hard enough to stretch you, easy enough that you win most days. The AI chooses *which* objectives to set; this engine alone sets *how much*.

Each realm r keeps a difficulty multiplier d (starts at 1.0, clamped to 0.5–3.0) that scales your baseline targets. Daily, with x the fraction of that realm's objectives completed:

```latex
c_r \leftarrow (1-\alpha)\,c_r + \alpha\,x_r, \qquad d_r \leftarrow d_r \exp\!\big[\eta\,(c_r - c^*)\big]
```

with α = 0.3, c\* = 0.80 and η = 0.15. In control terms this is integral control on ln d, with the moving average acting as a low-pass filter on the error. Sustained perfect days raise difficulty about 3% per day; a bad patch lowers it about 6% per day, so it falls faster than it climbs. Targets are then rounded to friendly numbers (push-ups to 5s, minutes to 15s).

**Baseline targets** (the d = 1.0 targets for template quests, from the Awakening measurements):

- Push-ups and squats: 2 × your max set; plank: 2 × your max hold.
- Steps: your average + 1,000; walk: 20 min.
- Deep-work block: 60 min, or 90 min if your typical day has more than 4 h of deep work.
- Reading: pages per week ÷ 7, at least 10.
- Lights out: 7 h before your day starts.

Each is scaled by the realm's d, rounded, then capped by the overrides below.

**Overrides that sit above the controller.**

- **Fatigue.** HP below 30% multiplies every realm by 0.7 for the day, cuts the Daily Quest to three objectives (one of them recovery) and pauses Emergency Quests.
- **Body safety caps.** Body targets rise at most 10% per week; the same muscle group never gets hard work on consecutive days; every fourth rising week is a 60% deload week.
- **Floors that never move.** At least one rest day per week; sleep targets never drop below 7 h; no fasting or calorie-restriction quests, ever.
- **Re-entry.** After three or more missed days, all multipliers restart at 0.8 × their old value, and the System's first message back is a welcome, not a warning.
- **Crunch weeks (optional).** If your calendar shows a deadline or heavy meeting load, Mind objectives shrink to one focused block on the thing that matters.

The engine validates every AI-proposed quest: any target outside the current band for that realm is clamped before it is shown to you.

## Gates and boss battles

Gates are week-long dungeons; bosses are your real milestones given an HP bar. Both turn big, vague goals into visible progress.

**Gates.** Each Gate has 3–6 objectives over 3–7 days, a lore line and a reward chest. Its rank (E–S) is set by how far it sits above your current baseline, not by absolute numbers. You can hold up to two open Gates at once. A Gate that expires pays out in proportion to what you cleared; nothing is taken away.

| Gate | Rank | Days | Objectives | Reward |
| --- | --- | --- | --- | --- |
| The Literature Abyss | C | 7 | Read 5 papers on dipole-conserving hydrodynamics; one-paragraph summary of each | 2,500 XP, Rare Mind gear |
| The Clean Code Crypt | C | 5 | Refactor one simulation script, add tests, document it in CLAUDE.md | 2,000 XP, Epic accessory chance |
| Iron Week | D | 7 | 4 strength sessions, 400 push-ups total | 1,800 XP, double STR mastery |
| Silence Protocol | D | 5 | No social media before noon; 2 h phone-free every day | FOC mastery, title "Unreachable" |
| Dawn Gate | B | 7 | Up before 07:00 and trained before 09:00, every day | 4,000 XP, Boots of the Dawnwalker |

The rewards in this table are flavour examples; real payouts follow the Gate board rules in Depth mechanics (Phase 3).

**Bosses.** When you name a milestone, the AI breaks it into 8–20 tasks, each worth damage in proportion to its estimated effort; the boss's HP is the total. Every completed task plays as an attack with a damage number. Tasks are grouped into phases; finishing a phase plays new lore text and unlocks the next block of tasks. The deadline is the enrage timer — past it, tasks still count but rewards fall 25%.

| Boss | Real milestone | Phases | Reward |
| --- | --- | --- | --- |
| The Draft Wyrm | Paper draft for the hard-rod current fluctuations work | Literature → Calculations → Figures → Writing → Submission | 15,000 XP, Hamiltonian Blade (Legendary), title "Integrable" |
| Warden of the Seminar | A group-meeting or conference talk | Outline → Slides → Dry run → Delivery | 6,000 XP, Epic gear |
| The 5K Revenant | Run 5 km under your target time | Base building → Intervals → Time trial | 8,000 XP, Epic or Legendary drop |
| The Thesis Colossus (raid) | The PhD thesis | One sub-boss per chapter, over months | Unlocks the Σ-rank trial |

The rewards in this table are flavour examples; real rewards follow the boss rules in Depth mechanics (Phase 3).

## Skill trees and powers

Three trees, one per realm, each with four tiers ending in an ultimate; a fourth tree opens at A-rank. Until then it is shown locked at the edge of the Skills screen, with "A-rank" as its only hint (A15). Passive skills change the rules quietly; active skills are your powers — they cost MP, have cooldowns and play a sigil animation when cast.

**Unlocking.** You earn 1 skill point every 2 levels, plus one from each boss. Tier 2 needs the tree's stat at 20, Tier 3 at 35, and the ultimate needs 50 plus one boss defeated. A tree's stat is the higher of its two stats: Body STR/AGI, Mind INT/FOC, Self WIS/VIT (A4).

| Tree | Tier | Skill | Type | Effect |
| --- | --- | --- | --- | --- |
| Body | 1 | Iron Body | Passive | +10% STR mastery |
| Body | 1 | Light Feet | Passive | +10% AGI mastery |
| Body | 2 | Momentum | Passive | +3% Body XP per consecutive training day, capped at 15%; rest days neither extend nor break the chain (A3) |
| Body | 2 | Second Wind | Active, 20 MP | Swap today's workout for 15 min of mobility without breaking the streak |
| Body | 3 | Limit Break | Active, 40 MP, weekly | One open Body quest rises to the largest target the +10% weekly cap still allows and pays 3× XP; unavailable when the cap leaves no headroom this week (A1) |
| Body | 3 | Recovery Aura | Passive | Rest days restore 50% more HP |
| Body | Ult | Unbroken | Active, monthly | Turns one missed Daily Quest into a completed one at 50% XP; streak kept. Only on a miss the Streak Shield didn't cover (A9) |
| Mind | 1 | Deep Work | Passive | +10% INT mastery |
| Mind | 1 | Clear Mind | Passive | +10% FOC mastery |
| Mind | 2 | Hyperfocus | Active, 30 MP | Locked 90-min focus session with Do Not Disturb on; 2× INT XP. Phase 4 builds a minimal Focus Session (timer plus a linked quest) for it; Phase 5 adds Health Connect and verification counting (A6) |
| Mind | 2 | Insight | Active, 20 MP | Claude splits a stuck problem into 3 concrete sub-quests; charges scale with WIS. Cast as a share-bridge request: the app shares your STATUS with the request, and the reply comes back through Import (A7) |
| Mind | 3 | Literature Sense | Passive | Papers read pay both INT and WIS |
| Mind | 3 | Focus Chain | Passive | +5% Mind XP per consecutive day with a completed focus session, capped at 25%; rest days are neutral (A3) |
| Mind | Ult | Eigenstate Collapse | Active, 80 MP, weekly | You pick one open quest; it pays 3× XP and the Quests tab collapses to it for the day. An optional button asks Claude which one, through the share bridge (A8) |
| Self | 1 | Restful | Passive | +10% VIT mastery |
| Self | 1 | Curious | Passive | +10% WIS mastery |
| Self | 2 | Streak Shield | Passive | One missed day per week is forgiven. It applies automatically, before Unbroken (A9) |
| Self | 2 | Clarity | Active, 15 MP | 5-min guided journal that becomes tomorrow's priorities |
| Self | 3 | Bonds | Passive | Social quests also restore HP |
| Self | 3 | Mentor's Eye | Active, weekly | An in-depth review of your week by Claude with three recommendations, cast as a share-bridge request (A7) |
| Self | Ult | Equilibrium | Passive | A rest day fully restores HP and MP and gives +10% XP the next day |

**The hidden Observer tree (A-rank).** Cross-realm powers: *Superposition* (one task counts for two realms, once per day), *Time Dilation* (extend one quest by 12 h, weekly), and *Singularity*, the Σ-rank capstone.

The drawn tree below shows how the tiers connect.

&#91;embedded content: skill trees · 3 realms, 4 tiers each, plus the hidden Observer tree\]

Each column reads top-down: a skill unlocks the one beneath it once the stat gate on the left is met.

## Equipment, achievements and lore codex

Gear gives small, real bonuses and changes how your avatar looks; achievements mark milestones with titles; the codex slowly tells the story of the Observer.

**Equipment.** Seven slots: weapon, head, body, hands, feet and two accessories. Rarity runs Common (grey), Rare (blue), Epic (violet), Legendary (gold) and Mythic (red-black, raids only). Items drop from Gate chests, bosses, Emergency Quests and achievements. Duplicates dismantle into Essence, which upgrades an item from +1 to +5. Wearing three pieces of one set adds a set bonus.

| Item | Slot | Rarity | Effect | Source |
| --- | --- | --- | --- | --- |
| Hamiltonian Blade | Weapon | Legendary | +15% Mind XP; one free Insight per day | Boss drops |
| Boots of the Random Walker | Feet | Legendary | +20% AGI mastery; steps beyond target pay bonus XP | Diffusive Limit achievement |
| Toda Chain | Accessory | Epic | Raises the streak bonus cap from 1.5× to 1.6× | Conserved Quantity achievement (30-day streak) |
| Cloak of Detailed Balance | Body | Epic | +10% XP in your currently weakest realm | Ergodic achievement (all three realms, 30 days) |
| Lens of Rough Interfaces | Head | Rare | +1 Insight charge per day | Gate chests |
| Boots of the Dawnwalker | Feet | Rare | +10% XP for quests finished before 09:00 | Gate chests |

**Achievements (seed set).**

- **Symmetry Breaking** — defeat your first boss.
- **Phase Transition** — reach C-rank.
- **Conserved Quantity** — hold a 30-day streak.
- **Ergodic** — touch every realm every day for 30 days.
- **Equipartition** — bring all six stats within 10% of each other.
- **Deep Field** — log 100 hours of deep work.
- **Diffusive Limit** — walk 1,000,000 steps in total.
- **Peer Reviewed** — submit a paper.
- **Centurion** — complete 100 Daily Quests.

**Lore codex.** About 40 short entries, unlocked by level, rank, bosses and hidden quests. Each is written by hand in the System voice from a fixed lore bible and ships in the app's assets (the app never calls an AI to write them). Claude, following the same lore bible in the Claude Project, can write lines for your own arcs, bosses and Gates; they arrive through the share bridge and you apply them like any proposal. An entry is frozen once unlocked so the canon stays consistent. Read in order, they tell the story of the lattice, the Observer, and what waits at Σ.

## Depth mechanics (Phase 3)

This section turns the overview sections above into buildable rules for Phase 3. Every number is a starting value, to be tuned after a month of real use.

### Quest structure

| Type | Horizon | Source | Limit | Reward | If missed |
| --- | --- | --- | --- | --- | --- |
| Main quest (arc chapter) | Weeks to months | A long-term goal, via Claude or by hand | 1 active arc per realm | 1,000 × (1 + 0.05 L) XP per chapter; arc end unlocks lore | Nothing; the arc waits |
| Daily quest | 1 day | Templates, Claude, you | 3–5 | Tier XP (XP section) | Penalty quest, −15 HP |
| Side quest | 1–3 days | Balance templates, Claude, your errands | 0–3 visible | 0.75 × tier XP, 10% loot roll | Expires silently |
| Penalty quest | Until the next reset | Issued after a missed Daily | 1 open | +10 HP | −10 HP |
| Emergency quest | 2 h | \~2 per week, inside your active hours | — | 1.5 × tier XP, 20% item roll | Nothing |
| Gate | 3–7 days | Sunday Gate board, Claude | 2 open | Rank XP and a chest | Partial payout |
| Boss | To a deadline | Arc finale or any milestone | 2 active | Damage-based XP, guaranteed Epic+ drop | Rewards −25% after the deadline |

### Main quests: arcs

An arc is a long-term goal split into 3–6 chapters of checkable objectives, ending in a boss. The active chapter's objectives feed weekly planning: Sunday's "Set this week" draws weekly goals from them first. One active arc per realm keeps focus; the rest wait in a backlog.

| Arc | Realm | Chapters | Finale |
| --- | --- | --- | --- |
| Research or study project | Mind | Literature → Work → Results → Write-up | Boss: the write-up |
| Iron Discipline | Body | Base (weeks 1–4) → Build (5–8) → Peak (9–11) → Test (12) | Trial: max-rep test against your week-1 record |
| Quiet Hours | Self | Fixed wake time → Lights-out target → Screens off before bed → Two consistent weeks | Title: Keeper of Quiet Hours |

Mind chapters are written by you or proposed by Claude and confirmed by you. ASCENT only tracks them; it never decides what the physics should be.

*Release (R3):* these are the arc templates that ship. The first personal seeds (The Rod Gas, Ring and Line) are no longer in the app; arcs like them are created in your own data, by hand or from Claude's `add_arc`.

A chapter completes when all its objectives are done; a chapter without objectives is completed by hand. Chapter objectives are logged by hand or through weekly goals linked to them. An arc with fewer than 3 chapters gets a warning but is allowed, since chapters can be added later.

### Side quests

Three sources:

- **Balance quests** — from the template pool, aimed at your lowest-completion realm over the last 7 days.
- **Claude proposals** — an `add_quest` action with scope `side`.
- **Bounties** — your own one-off errands (emails, admin, chores), added in one tap; Self realm and Easy tier by default.

At most 3 are visible at once. Each expires after 1–3 days, pays 0.75× its tier XP, has a 10% chance of a loot roll, and is never penalised.

- One balance quest is issued per reset while fewer than 3 side quests are visible. Balance quests expire after 2 days, bounties after 3, and Claude's `expires_days` is clamped to 1–3.
- The loot roll happens when the quest is completed.

### Penalty and emergency quests

- **Penalty quest.** Issued at the reset after a missed Daily and due at the next reset: one small corrective task. Done: +10 HP back; the streak stays reset. Missed: −10 HP, nothing more. At most one is open at a time. Shown in Penalty Red under `◆ WARNING`.
- **Emergency quest.** About 2 per week, at random times between your wake time and the start of quiet hours. Short and timed, with a 2 h window that starts when it is shown. Pays 1.5× tier XP and a 20% roll on the Emergency row of the drop table. Never penalised; paused during Fatigue.

### Gate board

At every Sunday reset the board offers three Gates: one at your rank, one a rank below, one a rank above, each in a different realm. At E rank there is no rank below, so the board offers E, E and D. You accept up to two; Claude can also propose one, but a Claude Gate beyond 2 open is blocked. Objectives are multiples of your current baseline times that realm's difficulty multiplier, so a rank measures effort relative to you.

**Progress.** Your normal quests count toward a Gate. Each objective's target is the normal-week amount (baseline × d, or the Gate template's weekly amount for things like papers) × (1 + the rank's extra effort), scaled to the Gate's days. Matching completed quests count automatically; anything else is logged by hand on the Gate screen.

| Rank | Extra effort over a normal week | Base XP | Chest |
| --- | --- | --- | --- |
| E | \~10% | 400 | E–D chest |
| D | \~20% | 800 | E–D chest |
| C | \~35% | 1,600 | C–B chest |
| B | \~50% | 3,200 | C–B chest |
| A | \~70% | 6,400 | A–S chest |
| S | \~100% | 12,800 | A–S chest |

- **Payout.** XP = base × (1 + 0.05 L) × fraction cleared, where fraction cleared is the mean of min(progress / target, 1) over the objectives. The chest opens only at 100%; between 50% and 100% you get the XP only.
- **Gate break.** Below 50% at expiry you keep the XP for what you cleared, get no chest, and unlock a lore line. Nothing is taken away.
- **Red Gates.** Once a month, opt-in, one rank above your highest, all-or-nothing, double chest. It appears as a fourth card on the first board of each month.
- **Body cap.** Body objectives count toward the 10%-per-week limit. High-rank Gates load Mind and Self instead, and the engine rejects any Gate that would break the cap.

### Bosses

- **HP.** The sum of task damage, where damage = 100 × estimated hours, with each estimate clamped to 0.25–40 h. You can edit estimates. Tasks are grouped into 2–5 phases; finishing one phase unlocks the next with a lore line.
- **Honest HP bar.** The bar moves only with real work. A critical hit (AGI) doubles a task's XP, not its damage, and bosses never regenerate. After 5 idle days the boss "stirs": one notification, no penalty.
- **Enrage.** Past the deadline, tasks still count but rewards fall 25%.
- **Defeat.** XP = 1.5 × total damage × (1 + 0.05 L), one guaranteed Epic or Legendary drop, a title and a lore entry. A 40-hour paper draft is 4,000 HP and pays 6,000 × (1 + 0.05 L) XP.
- **Trials.** Body bosses are single-session tests against your own record, such as max push-ups after a deload week. Beat the record to win; otherwise the Trial reopens in 4 weeks. At most one Trial every 4 weeks. The first record is your Awakening measurement. A won Trial pays 300 × (1 + 0.05 L) XP plus a roll on the Boss row.
- **Limits.** Two active bosses, plus one raid: a multi-month boss such as the thesis, with chapters as sub-bosses. An arc's finale boss counts toward the two.
- **Peer Reviewed.** A defeated boss can be marked "paper submitted", which is the event behind that achievement.

### Loot and equipment

**Drop tables** (percent by rarity):

| Source | Common | Rare | Epic | Legendary | Mythic |
| --- | --- | --- | --- | --- | --- |
| Side quest (10% roll) | 80 | 18 | 2 | 0 | 0 |
| E–D chest | 60 | 32 | 8 | 0 | 0 |
| C–B chest | 30 | 45 | 22 | 3 | 0 |
| A–S chest | 10 | 40 | 38 | 12 | 0 |
| Emergency quest (20% roll) | 0 | 85 | 15 | 0 | 0 |
| Boss | 0 | 0 | 70 | 30 | 0 |
| Raid | 0 | 0 | 0 | 50 | 50 |

Rolls use a seeded random generator (seed = a per-install seed + event id; the player id is always the same). The generator is SplitMix64, implemented in the engine, so old rolls replay identically after library updates. Every drop is reproducible in tests, and repeating an action can't re-roll it.

**Affixes.** Each item has a slot, a rarity and an affix budget:

| Rarity | Affixes | Max per affix | Extra |
| --- | --- | --- | --- |
| Common | 1 | 3% | — |
| Rare | 1–2 | 5% | — |
| Epic | 2 | 8% | — |
| Legendary | 2 | 10% | One unique effect |
| Mythic | 2 | 12% | One unique effect and an aura |

Affix types, all stored as JSON effects: XP multiplier for one realm, mastery multiplier for one stat, crit chance, streak cap, Insight charges, cooldown reduction, HP regeneration, Gate reward, and chest luck (shifts a roll one row toward rarer). Hard caps keep gear from outrunning the level curve: total gear XP bonus per realm ≤ +30%, total crit chance ≤ 25%.

Insight charges and cooldown reduction don't roll as affixes until Phase 4, when those systems exist. Hand-written items and set bonuses that use Phase 4 systems (Insight, cooldowns, Streak Shield, Hyperfocus) can still drop; that part of the item shows as dormant until Phase 4.

**From Phase 4 (A12)** those parts work:
- Insight charges from gear add to the 1 + ⌊WIS / 20⌋ daily charges.
- Iron Lattice gives one extra shielded miss per game month.
- Observer's Regalia's −25% Hyperfocus cooldown stacks with FOC's cooldown reduction, together at most −40%.

**Names.** Common to Epic names come from a prefix and a slot base. Prefixes: Ballistic, Diffusive, Ergodic, Adiabatic, Hydrodynamic, Integrable, Conserved. Bases: Blade, Cowl, Cloak, Gauntlets, Boots, Ring, Sigil. Legendary and Mythic items are hand-written, for example:

- **Hard-Rod Gauntlets** (Legendary, hands) — +10% STR mastery; push-up Trials count double for mastery.
- **Wigner Lens** (Legendary, head) — +1 Insight charge; Mind Gates pay +10% XP.

**Upgrades.** Dismantle any unequipped item you don't want into Essence: Common 1, Rare 3, Epic 10, Legendary 30. Mythic items can't be dismantled. Upgrading costs 5, 10, 20, 40 and 80 Essence for +1 to +5. Each level adds 20% of the item's base affix values, still under the global caps above (an upgraded affix may exceed its rarity's per-affix maximum).

**Sets.**

| Set | Realm | 3-piece bonus |
| --- | --- | --- |
| Iron Lattice | Body | One extra Streak Shield per month |
| Observer's Regalia | Mind | Hyperfocus cooldown −25% |
| Quiet Hours | Self | Sleep quests +20% XP |

Equipped gear changes the avatar silhouette; Mythic gear also changes the aura particles. The avatar is drawn in Compose Canvas: a hooded silhouette with an overlay per gear slot, tinted by rarity colour.

### Achievements and titles

Achievements are data, not code. Each is a JSON entry with an id, a condition and a reward. One evaluator listens to game events (quest done, level up, Gate cleared, boss defeated, day closed) and checks them. Condition kinds: counter (an event count ≥ n), streak (consecutive days with a property), threshold (a value ≥ x), event (a single occurrence) and hidden (any of these, invisible until earned). Counters come in tiers I, II and III.

| Achievement | Kind | Condition | Reward |
| --- | --- | --- | --- |
| Symmetry Breaking | Event | Defeat your first boss | Title, C–B chest |
| Phase Transition | Threshold | Reach C-rank | Title |
| Conserved Quantity | Streak | 30-day Daily Quest streak | Toda Chain (Epic accessory) |
| Ergodic | Streak | All three realms touched every day for 30 days | Cloak of Detailed Balance |
| Equipartition | Threshold | All six stats within 10% of each other | Title |
| Deep Field I / II / III | Counter | 25 / 100 / 400 hours of deep work | 20 / 60 / 200 Essence |
| Gatekeeper I / II / III | Counter | 5 / 25 / 100 Gates cleared | Chest of matching rank |
| Diffusive Limit | Counter | 1,000,000 steps in total | Boots of the Random Walker |
| Centurion | Counter | 100 Daily Quests completed | Title |
| Peer Reviewed | Event | Mark a paper as submitted | A–S chest, title |
| Still Night | Hidden | 14 nights in a row at your sleep target | Title |
| Red Sky | Hidden | Clear a Red Gate | A–S chest |

You wear one title at a time, shown under your name. Titles are cosmetic, except the class titles from the XP section.

**Definitions.** Ergodic: at least one quest done in each realm that day. Equipartition: the highest stat ≤ 1.1 × the lowest.

**Logged data.** Health Connect comes in Phase 6 (see the roadmap), so until then achievements count logged quests: Deep Field counts minutes on completed Mind quests with a time unit, Diffusive Limit counts the targets of completed step quests, and Still Night counts completed lights-out quests on consecutive nights. If Health Connect is built later, it switches them to real data. Counters are backfilled from your quest history when achievements first switch on.

### Claude bridge: new actions (block v2)

The `ascent` block gains six actions. Proposed Gates and bosses pass the same engine checks as everything else: effort sizing and the Body cap. If a proposed Gate sits outside your effort band, the card shows the corrected rank.

```json
{"v":2,"actions":[
  {"op":"add_quest","scope":"side","realm":"SELF","title":"Submit the travel reimbursement form","tier":"easy","expires_days":2},
  {"op":"add_arc","realm":"MIND","title":"The Rod Gas","goal":"g3","chapters":[
    {"title":"Numerical current cumulants","objectives":[{"title":"Cumulants to 4th order at three densities","target":3,"unit":"densities"}]},
    {"title":"BMFT prediction","objectives":[]}],
   "finale":{"boss":"The Draft","deadline":"2027-03-31"}},
  {"op":"add_gate","rank":"C","realm":"MIND","title":"The Literature Abyss","days":7,
   "objectives":[{"title":"Read a BMFT paper, write a 3-line summary","target":3,"unit":"papers"}]},
  {"op":"add_boss","title":"The Draft","deadline":"2027-03-31","arc":"a2",
   "phases":[{"name":"Calculations","tasks":[{"title":"Write up the BMFT cumulant calculation","hours":6}]}]},
  {"op":"add_boss_task","boss":"b1","phase":2,"title":"Make figure 3","hours":3},
  {"op":"add_lore","for":"b1","event":"defeat","title":"The Draft falls","text":"Months of hours, one manuscript. The fixed point has moved, and it carries your name."}
]}
```

- **Ids.** Arcs `a1`, bosses `b1`, boss tasks `t1`, Gates `gt1`; goals (`g12`) and quests (`q7f2a`) are unchanged.
- `complete` also accepts boss-task ids, so a reported task hits its boss.
- `add_lore` gives your own arc (`chapter_done`, `arc_end`), boss (`phase_2` to `phase_5`, `defeat`) or Gate (`break`) its own line, replacing the generic one for that event. The text must be at most 200 characters, follow `docs/lore-bible.md`, and pass the franchise-name filter. A line can't replace one that has already unlocked.
- v1 blocks are still accepted.
- **STATUS v2** adds side quests, active arcs with the current chapter's objectives, open Gates, and bosses with their phases and task ids.
- The app sends `ASCENT STATUS · v2` and reads v1 and v2 blocks; `docs/ASCENT-System-Project-Instructions.md` documents both the STATUS sections and the six new actions.
- **Phase 4 (STATUS v3, block v3; PHASE4.md section 7).** STATUS adds `Class:`, `Powers:` and `Rank trial:` lines after `Stats:` (each `none` when empty), and Mentor's Eye sends `Last 28 days:` instead of `Last 7 days:`. `add_quest` gains optional `stat` and `tags` (social, paper, meditation, walk, mobility, sleep, journal, derivation, simulation, writing; unknown tags dropped). No new actions: Claude may suggest a power by name but never casts one, and can't propose or edit a rank trial. The app sends `ASCENT STATUS · v3` and reads v1, v2 and v3 blocks.
- **Phase 5 (STATUS v4; PHASE5.md section 5).** Each quest line whose habit series has a recorded strength gains `strength NN%`; `Extras today: n (next pays NN%)` follows the side quests; a history day between a backup and its restore is tagged `neutral` instead of `rest`. No new actions: the app sends `ASCENT STATUS · v4` and still reads v1–v3 blocks. The Project instructions explain strength (≥ 85% holding on its own, < 30% needs attention), ask for at most three self-added quests a day, and say neutral days are left out of completion rates.

### Data model additions

Game content — loot tables, item templates, sets, Gate templates, achievements and lore — ships as JSON files in the app's assets, so balancing never needs a code change. Lore lines are written by hand, never generated by the app: the seed lines live in `core/data/src/main/assets/content/lore.json`, the canon in `docs/lore-bible.md`, and Claude's lines for your own content arrive through `add_lore`.

| Entity | Key fields |
| --- | --- |
| Arc, Chapter | realm, title, goalId, order, status; arcId, objectives, status |
| Gate, GateObjective | rank, realm, title, opensAt, closesAt, isRed, status; title, target, unit, progress |
| Boss, Phase, BossTask | plus arcId, isTrial, record to beat |
| Item, InventoryEntry | plus affixes, upgrade level, setId, roll seed |
| PlayerAchievement | achievementId, tier, progress, earnedAt |

### Screens and sound in Phase 3

- **Navigation.** Four tabs: Status · Quests · Console · Gates; Skills joins in Phase 4. The Quests tab gains Side and Arcs sections; the Gates tab holds the Gate board and the Boss Arena; Inventory and Codex open from Status.
- **Sound.** CC0 sounds by Kenney (Interface Sounds, Sci-fi Sounds, Digital SFX) in `:core:designsystem` `res/raw`, played through SoundPool with a paired haptic. Sources, picks and alternates are listed in `docs/sounds/SOUNDS.md`.

### Phase 3 build order

1. **Content as data** — JSON schemas, seed files and a loader with validation tests.
2. **Engine** — seeded loot rolls, affix caps, Gate sizing and payout, boss damage, phases and enrage, the achievement evaluator. Pure Kotlin, fully tested.
3. **Side, penalty and emergency quests** — balance templates, bounties, expiry; penalty quests; emergency quests.
4. **Main quests** — arcs and chapters, the arc screen, chapters feeding weekly goals.
5. **Gate board** — Sunday spawn, accept, progress, chest-opening animation.
6. **Bosses** — the arena screen, phases, Trials.
7. **Inventory** — equip, compare, dismantle, upgrade, sets, avatar changes.
8. **Achievements and titles** — evaluator wiring and the Codex screen.
9. **Bridge v2** — the six new actions, with parser tests built from real Claude replies.
10. **Sound and haptics** — chests, hits, unlocks.

Exit: one arc chapter, one Gate and one boss phase run from your real week.

## Durability and honest metrics (Phase 5)

Buildable rules, export columns and the required tests are in `PHASE5.md`. Its decisions D1–D8 (approved 2026-11-16) and the clarifications below are the rules. Every number is a starting value.

### Habit strength

- **Series.** A repeating quest across every period it is issued. Template quests form one series per template key. Your own and Claude's recurring quests get a `seriesId` when created, carried forward by the daily copy. One-off quests have no series.
- **Observation.** For each period in which the series was issued, x = min(progress / target, 1), measured against that period's own target. Quests have no progress column yet, so x is 1 for a completed quest and 0 otherwise; partial credit waits for progress logging.
- **Score.** Sₖ = (1 − α) · Sₖ₋₁ + α · xₖ, with S₀ = 0. Daily α = 1 − 0.2^(1/30) ≈ 0.05223 (D1): a perfect record reaches 80% / 96% / 99.2% after 30 / 60 / 90 days. Weekly α = 1 − 0.2^(7/30) ≈ 0.3131 (D2): 31% / 78% / 95% after 1 / 4 / 8 weeks. The period key is the game day (reset to reset), or the week's Sunday for weekly quests.
- **Periods that don't count.** No update at all for a period in which the series wasn't issued: rest days, the day Second Wind swapped the quest out (that day belongs to the Mobility series instead), and neutral restore days (D7). A period in which it was issued and not done records 0, whatever Streak Shield or Unbroken did (D3). No power touches strength.
- **Computation.** A completion records its period at once (so the score moves when you tick the quest), an undo removes it again, and the reset records every issued period's final value. Unbroken's later rewrite of yesterday never reaches it. S is recomputed from the full history, oldest first, in `Double`, with the latest value cached per series. It is shown as an integer percent, rounded half-up.
- **Backfill.** Rebuilt only from exact matches in past quest rows (template rows by key; recurring rows by title, realm, scope and source). Anything else starts at 0 and reads "measuring since <date>" until 30 periods exist. History is never invented.
- **Labels.** Below 30% *Unstable* · 30–60% *Forming* · 60–85% *Holding* · 85% and above *Bound*. Shown as a small percent on the quest card, and in a quest detail with a 90-period sparkline and the series' current and best streak.

### Grind guard

- **Scope.** Self-added quests only (source `MANUAL`): your own quests, bounties and Clarity's extras. Template quests, Claude's quests, penalty, emergency and hidden quests, arc and Gate objectives and boss tasks never use a slot.
- **Slots.** Per game day, in completion order. Trivial to Normal quests use 1 slot, Hard 2, Extreme 3 (D5). A quest's last slot is the slots already used by today's completed self-added quests plus its own, and it pays that slot's multiplier: m(n) = 1 for n ≤ 3, otherwise max(0.5^(n − 3), 0.1) (D4). The 4th pays 50%, the 5th 25%, the 6th 12.5%, the 7th onward 10%. The free-slot count (3) and the floor (0.1) are content constants.
- **Application.** m scales both XP and mastery. It multiplies the quest's base; passive bonuses (≤ ×1.75), the streak term, single-target powers and the critical then apply as before. Past the 3rd slot a self-added side quest gets no loot roll.
- **Undo is exact.** The multiplier and the paid XP (`xpAwarded`) are stored on the quest. Undo refunds exactly what was paid; other quests keep their pay, and nothing is recomputed. Completing and undoing can never gain XP.
- **UI.** Past the 3rd slot: `◆ DIMINISHING RETURNS — The Observer measures effort, not entries.`, the card shows what it will pay ("pays 25%"), and the Quests tab header reads "Extras today: 4 (3 at full XP)".

### Backup, restore and export

- **Backup (D6).** `ascent-backup-YYYYMMDD-HHMM.zip`: the Room database copied after a WAL checkpoint with writes paused, the DataStore files, and `manifest.json` (app version, schema version, created-at in UTC and local time, game day, row counts per table, SHA-256 of the database, and whether the journal was excluded). Content assets ship in the APK and aren't backed up.
- **Where.** A folder you pick once through the Storage Access Framework, with the permission kept. A daily backup after the reset if anything changed, plus **Back up now**. Retention: the newest 14 daily files and one per month for 12 months. Android Auto Backup covers the database (and its WAL) and the settings DataStore as a fallback, never the backup bookkeeping. Status warns in one line when the last backup is more than 7 days old, or there has never been one.
- **Journal (D8).** Clarity's journal (in the cast notes) is included by default; a setting excludes it.
- **Restore.** Verify the zip, manifest and hash; refuse a backup from a newer schema; make an automatic pre-restore backup; stage the files and restart the app, which swaps them in before the database and settings open; Room's migrations bring an older backup up to date; check the row counts. Every game day between the backup and now is **neutral** (D7): no penalties, no HP loss, the streak frozen, no strength updates. The neutral days' snapshots also count as rest days, so chains neither grow nor break. A summary (level, XP, streak, backup date) offers **Undo restore** until the next reset. Errors leave the current data untouched. A fresh install offers "Restore from a backup" on the Awakening's first screen.
- **Export.** **Export data** (Settings → Backup, saved where you choose) writes `ascent-export-YYYYMMDD.zip`: UTF-8 CSV (RFC 4180, ISO-8601 dates) for quests, days, habits and XP events, plus a README with every column and the formulas. XP from before the XP log is rebuilt from quest rows only. Export is for reading only and is never used for restore.

### Data model (Room v4)

| Entity | Change |
| --- | --- |
| Quest | + `seriesId`, `guardMultiplier`; the paid XP is the existing `xpAwarded` |
| HabitObservation | New: seriesId, periodKey, x, target, progress, recordedAt; key (seriesId, periodKey) |
| HabitSeriesCache | New: seriesId, period, strength, lastPeriodKey, streak, best (streak), observations, sinceKey |
| XpEvent | New: timestamp, game day, source, amount, multipliers, refId. Every XP grant from v4 on; earlier history comes from quest rows only |
| DailySnapshot | + `neutral` for restore gaps |

DataStore gains `backupFolderUri`, `lastBackupAt`, `lastBackupHash` and `excludeJournalFromBackup`, in their own file (`ascent_backup`): device-local and never inside a backup, so a restore can't bring back another phone's folder permission or backup times. The grind guard's numbers live in `content/guard.json` (free slots, decay, floor, slots per tier). The migration is additive; filling `seriesId` on existing rows only sets the new column. Its backfill records closed periods only: done is 1, expired is 0, and removed quests, rest days (for daily series) and the open day or week record nothing.

### Bridge and content

- **STATUS v4.** Each repeating quest line gains `strength NN%`, and `Extras today: n (next pays NN%)` follows the quests. No new actions, so the `ascent` block stays v3. The Project instructions gain two sentences (strength's meaning; at most three self-added quests a day) in the same task.
- **Achievements.** `bound_state` (Threshold: any repeating quest at 85%; title "Bound") and `bound_triplet` (Threshold: three repeating quests at 85% or more at once, one per realm; C–B chest).
- **Lore.** *Bound state* for `bound_state`, and *Conserved*, shown once after a restore (texts in `PHASE5.md`).

## Release (friends build)

Buildable rules are in `RELEASE.md`. Its decisions R1–R6 (approved 2026-11-16) are the rules. No new game features.

- **R1, name.** The home-screen name is **ASCENT**.
- **R2, application id.** `com.tirthankar.ascent`, kept forever: changing it after friends install makes a separate app whose data doesn't carry over. `versionName` is `MAJOR.MINOR.PATCH` from `1.0.0`; `versionCode` goes up by 1 with every APK sent.
- **R3, personal content.** Nothing that identifies you or your projects ships in the APK. Every friend starts blank: name, baselines, rest day, reset time and Dossier come from the Awakening. Project-specific seeds become generic templates (a "Research or study project" arc, plus the Body and Self arcs). Your own data stays in your database and backups.
- **R4, flavour names.** Physics-flavoured item and lore names (Hard-Rod Gauntlets, Wigner Lens, the lattice lore) stay: they're flavour, not personal data. The audit still lists every hit; only project seeds, personal examples and identifying text are removed.
- **R5, distribution now.** A signed APK shared directly; updates install over it with the same key and a higher `versionCode`.
- **R6, distribution from 2027.** Register ASCENT in a free limited-distribution developer account before Android developer verification reaches India (check the timeline first).
- **Friends' System.** Each friend makes their own Claude Project from a bundled, generic instructions template filled from their onboarding (Settings → The System). The app stays fully playable without Claude.
- **About.** Credits for the bundled fonts (Rajdhani, Inter and JetBrains Mono, SIL Open Font License), the Kenney sounds (CC0) and the open-source libraries.

## AI layer

Claude is the System's voice and planner: it chooses quests, decomposes bosses, and explains your progress. Numbers are computed on the device first; the AI interprets them and never invents them.

| Job | When | Input | Output |
| --- | --- | --- | --- |
| Quest generation | When you ask the System | 14 days of completions, stats, difficulty per realm, goals, open Gates and bosses, rest days, calendar load | 3–5 Daily objectives + 0–3 Side Quests as JSON |
| Boss decomposition | When you create a milestone | Milestone, deadline, your notes | 8–20 tasks with phases and effort estimates |
| Weekly System Assessment | Sunday reset | 7 days of metrics and week-over-week changes | What grew, what slipped, one pattern, 3 recommendations, a suggested Gate |
| Insight power | When cast | Your description of a stuck problem | 3 concrete sub-quests |
| Flavour text | Notifications, lore unlocks | The event | One line in the System voice |
| System Console chat | Any time you open it | Your message, the dossier, current quests, last 7 days | A reply in the System voice, plus proposed quest changes you apply with one tap |

**System Console.** In this design the console is your Claude chat, and its proposals arrive through the `ascent` block.

**Progress analysis.** The app computes stat growth per week, completion by realm and weekday, your best hours for deep work, and simple correlations such as sleep versus next-day Mind completion. The Assessment turns these into plain findings, for example: "Mind completion falls to 40% after nights under 6 h — tonight's Self objective is lights-out by 00:30."

**Format.** Claude follows the ASCENT System Project instructions and returns changes in an `ascent` block (see the System Console section). ASCENT parses it with kotlinx.serialization; a malformed block never crashes the app — it shows what it could read and says what it couldn't.

**Cost.** Planning runs on your existing Claude plan in the Claude app. ASCENT has no API key, no server and no per-request cost.

**Privacy.** Nothing leaves the phone until you tap share. The STATUS message holds only aggregates such as average sleep or weekly step totals, never raw Health Connect records.

## Screens and UX flow

The Status Window is home; a five-tab bottom bar (Status · Quests · Console · Gates · Skills) reaches everything else, the Codex opens from Status, and System notifications can overlay any screen.

| Screen | What it shows | Key interactions |
| --- | --- | --- |
| Awakening | First-run sequence (see Character creation) | Answer prompts, record baseline |
| Status Window | Avatar with rank aura; name, title, class, rank; level and XP bar; HP and MP bars; six-stat hexagon radar; streak; today's quest ring | Allocate free points with +; open Inventory and Assessment |
| Quest Log | Daily Quest card with countdown; Side, Emergency and Penalty quests (penalty in red) | Tap an objective to log or check it; start a timer |
| System Console | Chat with the System; its proposed quest changes appear as cards | Plan the day or week, report progress, apply or dismiss proposals |
| Focus Session | Full-screen dark timer with a pulsing ring | Start, pause, cast Hyperfocus |
| Gates | Open Gates with rank badge, days left and progress | Enter a Gate to see lore, objectives and chest |
| Boss Arena | Boss name and art, HP bar with phase markers, tasks as attacks, damage log, enrage countdown | Complete tasks, cast powers |
| Skill Tree | Pan-and-zoom node graph with glowing links | Unlock with skill points, cast active skills |
| Inventory | Avatar with 7 gear slots; item grid by rarity | Equip, compare, dismantle, upgrade |
| Codex | Achievements grid and lore entries | Read lore, set a title |
| System Assessment | Weekly report: stat growth, completion heatmap (realm × weekday), findings, recommendations | Accept a suggested Gate |
| Settings | Reset time, rest days, backup and export, sound, haptics, motion | Back up now, restore, export data |

**Outside the app.** A home-screen widget (Jetpack Glance) showing Daily Quest progress and the countdown is planned for Phase 6. Notifications fire at the reset, three hours before the Daily expires, and for Emergency Quests, each with the System chime and silent during quiet hours.

## Tech architecture and data model

Native Android and offline-first: all game logic and data live on the phone, and only AI calls go out.

&#91;embedded content: app architecture · on-device core, two outbound calls\]

The game engine is plain Kotlin with no Android dependencies, so the XP maths, the difficulty controller and effect stacking can be unit-tested in isolation.

| Layer | Choice | Why |
| --- | --- | --- |
| UI | Kotlin, Jetpack Compose, Navigation Compose | Fine control of animation and Canvas drawing |
| Structure | MVVM, single activity, Hilt for dependency injection | Standard and well supported by Claude Code |
| Storage | Room (SQLite), DataStore for settings | Offline-first, one source of truth |
| Background | WorkManager | Daily reset, Gate expiry, daily backup |
| Health | Health Connect client (Phase 6) | Steps, exercise sessions, sleep |
| AI | Claude app via the share sheet and clipboard | Runs on your Claude plan; no API key or server |
| Motion and sound | Compose animation, Canvas particles, Lottie, SoundPool, haptics | The System feel |
| Charts and widget | Vico or custom Canvas; Jetpack Glance (Phase 6) | Weekly charts; home-screen quest widget |

**Modules.** `:core:engine` (pure Kotlin), `:core:data` (Room, repositories), `:core:designsystem` (theme, holo panels, sounds), `:integrations` (health, AI), and one `:feature:*` module per screen group.

**Data model (Room entities).**

| Entity | Key fields |
| --- | --- |
| Player | name, title, classId, level, totalXp, rank, hp, mp, freePoints, skillPoints, streak |
| Attribute | type (STR…VIT), value, mastery |
| Quest | type, realm, title, tier, target, unit, progress, verify, source, issuedAt, dueAt, status, xp, goalId |
| RealmDifficulty | realm, multiplier, completionEma, baselines |
| Gate | rank, title, lore, objectives, opensAt, closesAt, status, chest |
| Boss, BossTask | name, milestone, maxHp, hp, phase, deadline; phase, title, damage, done |
| Skill, PlayerSkill | tree, tier, kind, mpCost, cooldown, effect, prereqs; unlockedAt, lastCastAt |
| Item, InventoryEntry | slot, rarity, upgrade, effect, setId; equipped |
| Achievement, LoreEntry | condition, reward; text, unlockedAt |
| ActivityEvent | timestamp, source, realm, amount, unit, questId, xp |
| DailySnapshot | date, level, stats, hp, completion per realm |
| Goal | horizon (long-term or week), realm, title, target, unit, progress, deadline, parentId, bossId, status |
| ChatMessage | role, text, proposals, proposalState, createdAt |
| Dossier | profile text, memory note, strictness, ritual times |

**Effects as data.** Every bonus from skills, gear and class is stored as a small JSON effect such as `{"kind":"xp_mult","realm":"MIND","value":1.15}`. One RewardCalculator folds all active effects, so new items and skills need no new code.

## Build roadmap for Claude Code

Build in phases, each ending with something you actually use on your phone; never start a phase until the previous one has survived a week of daily use.

1. **Foundations.** Android Studio project, module layout, and `:core:engine` with the XP curve, difficulty controller and RewardCalculator, all unit-tested. Design system: colour tokens, fonts, a `HoloPanel` composable and the materialize animation. Exit: engine tests pass; one static Status Window renders in the System style.
2. **Playable loop (MVP).** Awakening onboarding, Player and attributes in Room, Ask the System and Import from Claude (the share bridge) with proposal cards, template Daily Quests as the offline fallback, manual check-off, Status Window with the stat radar, level-up animation, daily reset in WorkManager, notifications. Exit: you complete a Daily Quest every day for a week.
3. **Depth.** Everything in Depth mechanics (Phase 3): main quest arcs; side, penalty and emergency quests; the Gate board; bosses and Trials; loot and equipment; achievements and titles; sound and haptics.
4. **Progression.** Skill trees and active powers with MP and cooldowns, class awakening at level 10, rank trials, lore codex, avatar evolution. Buildable rules are in `PHASE4.md`; its amendments A1–A16 are folded into this document.
5. **Durability and honest metrics.** Backup and restore, CSV export, habit strength on repeating quests, and the grind guard on self-added quests. Buildable rules are in `PHASE5.md`; its decisions D1–D8 are folded into this document (Durability and honest metrics).

**Release — ASCENT for friends.** Turn the personal build into an app friends can install, set up for themselves and update without losing data; no new game features. Buildable rules are in `RELEASE.md`; its decisions R1–R6 are folded into this document (Release).

6. **Integrations (after the Release, as a normal update).** Health Connect, focus-timer verification, the home-screen widget, check-in notifications that open Ask the System, and weekly charts. (The Focus Session itself shipped in Phase 4.)

**Still shelved.** Smarter template quests and boss decomposition from the old AI phase; nothing is built until you un-shelve them. (The Insight power shipped in Phase 4 as a share request.)

**Working with Claude Code.** Export this doc as Markdown into the repo as `SPEC.md`. Write a `CLAUDE.md` with the house rules: the engine never imports Android code, every engine change ships with a test, and colours come only from design tokens. Keep a `TODO.md` per phase, run each phase on its own branch, and review on your real phone after every phase.

**Installing.** No Play Store needed: enable USB debugging on your phone and install straight from Android Studio on the Mac mini. Friends get a signed release APK (see Release).

**Parked for now.** Watching Git repositories — commit-based Mind quests and automatic verification from your research repos — is out of scope. Research quests are checked off by hand or by the focus timer.

**Open questions for you.**

- [x] Final app name — keep ASCENT or choose your own.
- [x] Daily reset time and weekly rest day(s).
