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

Each stat starts at 10 and shifts ±5 from the measurement, judged against your own goals rather than population norms.

| Stat | Realm | Grows from | Gameplay effect |
| --- | --- | --- | --- |
| STR — Strength | Body | Strength sessions, reps, load lifted | Raises max HP |
| AGI — Agility | Body | Steps, runs, cardio and mobility minutes | Critical chance: a quest pays 2× XP |
| INT — Intellect | Mind | Deep-work minutes, commits, derivations, simulations | Raises max MP |
| FOC — Focus | Mind | Unbroken focus blocks, phone-free time, meditation | Shorter skill cooldowns |
| WIS — Wisdom | Self | Reading, papers outside your field, journaling, learning | Extra Insight charges, unlocks lore |
| VIT — Vitality | Self | Sleep, proper meals, hydration, rest days | Raises max HP and HP regeneration |

**How stats grow.** Two channels. Organic growth: activity in a stat's domain fills its mastery bar, and the stat rises by 1 when mastery reaches 50 + 10 × (current value). Free points: every level-up grants 3 points you allocate by hand on the Status screen.

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

**Classes.** At level 10, alongside the D-rank trial, the System awakens a class from your two highest stats. A class gives +10% XP to its two stats and one unique skill. Classes evolve at B-rank and again at S-rank.

| Base class (Lv 10) | Top stats | Evolution (B) | Final form (S) | Unique skill |
| --- | --- | --- | --- | --- |
| Theorist | INT + WIS | Field Theorist | Architect of Laws | First Principles: one quest per day may be replaced by a derivation of your choice |
| Computationalist | INT + FOC | Simulationist | Laplace's Demon | Parallel Runs: two Mind quests can tick at once from one long simulation |
| Vanguard | STR + AGI | Warbringer | Titan | Overload: once a week, one Body quest at +50% target for 3× XP |
| Ascetic | FOC + VIT | Monk | Still Point | Stillness: a meditation block refills 25% MP |
| Wanderer | AGI + WIS | Pathfinder | Cartographer | Long Walk: thinking walks count for both Body and Mind |
| Polymath | No stat leads by >15% | Renaissance | Universalist | Resonance: +5% XP for every realm completed today |

Your avatar evolves with you: the aura colour follows rank, the silhouette changes with class and equipped gear, and a title of your choice displays under your name.

## Quest system

The Daily Quest is the heartbeat: four objectives across all three realms, issued at every reset and verified automatically wherever possible.

| Type | When it appears | What it asks | Reward | If missed |
| --- | --- | --- | --- | --- |
| Daily Quest | Every reset (default 04:00) | 4 objectives: 1–2 Body, 1 Mind, 1 Self | \~300 × (1 + 0.05 L) XP, mastery, streak +1 | Penalty Quest, −15 HP, streak resets unless shielded |
| Penalty Quest | Within 24 h of a missed Daily | One small corrective task, e.g. a 15-min walk plus one line on what got in the way | Clears the penalty, +10 HP | −10 HP, nothing more |
| Side Quest | 0–3 per day, AI-suggested | Optional tasks aimed at weak stats or open goals | Tier XP, small loot chance | Expires silently |
| Emergency Quest | \~2 per week, inside your active hours | Short and timed, e.g. "Within 2 h: 45 min focused work on X" | 1.5× XP, Rare item chance | No penalty |
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

- **Health Connect** — steps, exercise sessions and sleep are read automatically.
- **Focus timer** — the in-app session timer is its own proof for deep-work and reading blocks.
- **Manual check-off** — everything else, on the honour system, with an optional note or photo. It is a single-player game: cheating only corrupts your own character sheet.

**Mind quest library (seed examples).**

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

**Reference files.** The Claude Project instructions live in the repo as `docs/ASCENT-System-Project-Instructions.md` and are the contract for this format; change both together. The web prototype, [ASCENT — System Console](https://claude.ai/artifact/8PZMPxzDZL9eMcfEf5grV3), shows the chat, cards and tabs, and works for planning until the app is ready.

## Adaptive difficulty engine

Each realm runs its own controller that holds your completion rate near 80%: hard enough to stretch you, easy enough that you win most days. The AI chooses *which* objectives to set; this engine alone sets *how much*.

Each realm r keeps a difficulty multiplier d (starts at 1.0, clamped to 0.5–3.0) that scales your baseline targets. Daily, with x the fraction of that realm's objectives completed:

```latex
c_r \leftarrow (1-\alpha)\,c_r + \alpha\,x_r, \qquad d_r \leftarrow d_r \exp\!\big[\eta\,(c_r - c^*)\big]
```

with α = 0.3, c\* = 0.80 and η = 0.15. In control terms this is integral control on ln d, with the moving average acting as a low-pass filter on the error. Sustained perfect days raise difficulty about 3% per day; a bad patch lowers it about 6% per day, so it falls faster than it climbs. Targets are then rounded to friendly numbers (push-ups to 5s, minutes to 15s).

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

**Bosses.** When you name a milestone, the AI breaks it into 8–20 tasks, each worth damage in proportion to its estimated effort; the boss's HP is the total. Every completed task plays as an attack with a damage number. At 66% and 33% HP the boss changes phase: new lore text, and the next block of tasks unlocks. The deadline is the enrage timer — past it, tasks still count but rewards fall 25%.

| Boss | Real milestone | Phases | Reward |
| --- | --- | --- | --- |
| The Draft Wyrm | Paper draft for the Toda truncation work | Literature → Calculations → Figures → Writing → Submission | 15,000 XP, Hamiltonian Blade (Legendary), title "Integrable" |
| Warden of the Seminar | A group-meeting or conference talk | Outline → Slides → Dry run → Delivery | 6,000 XP, Epic gear |
| The 5K Revenant | Run 5 km under your target time | Base building → Intervals → Time trial | 8,000 XP, Boots of the Random Walker |
| The Thesis Colossus (raid) | The PhD thesis | One sub-boss per chapter, over months | Unlocks the Σ-rank trial |

## Skill trees and powers

Three trees, one per realm, each with four tiers ending in an ultimate; a hidden fourth tree opens at A-rank. Passive skills change the rules quietly; active skills are your powers — they cost MP, have cooldowns and play a sigil animation when cast.

**Unlocking.** You earn 1 skill point every 2 levels, plus one from each boss. Tier 2 needs the tree's stat at 20, Tier 3 at 35, and the ultimate needs 50 plus one boss defeated.

| Tree | Tier | Skill | Type | Effect |
| --- | --- | --- | --- | --- |
| Body | 1 | Iron Body | Passive | +10% STR mastery |
| Body | 1 | Light Feet | Passive | +10% AGI mastery |
| Body | 2 | Momentum | Passive | +3% Body XP per consecutive training day, capped at 15% |
| Body | 2 | Second Wind | Active, 20 MP | Swap today's workout for 15 min of mobility without breaking the streak |
| Body | 3 | Limit Break | Active, 40 MP, weekly | One Body quest at +30% target pays 3× XP |
| Body | 3 | Recovery Aura | Passive | Rest days restore 50% more HP |
| Body | Ult | Unbroken | Active, monthly | Turns one missed Daily Quest into a completed one at 50% XP; streak kept |
| Mind | 1 | Deep Work | Passive | +10% INT mastery |
| Mind | 1 | Clear Mind | Passive | +10% FOC mastery |
| Mind | 2 | Hyperfocus | Active, 30 MP | Locked 90-min focus session with Do Not Disturb on; 2× INT XP |
| Mind | 2 | Insight | Active, 20 MP | The AI splits a stuck problem into 3 concrete sub-quests; charges scale with WIS |
| Mind | 3 | Literature Sense | Passive | Papers read pay both INT and WIS |
| Mind | 3 | Focus Chain | Passive | +5% Mind XP per consecutive day with a completed focus session, capped at 25% |
| Mind | Ult | Eigenstate Collapse | Active, 80 MP, weekly | Collapses your whole TODO list to the single highest-leverage task, which pays 3× XP |
| Self | 1 | Restful | Passive | +10% VIT mastery |
| Self | 1 | Curious | Passive | +10% WIS mastery |
| Self | 2 | Streak Shield | Passive | One missed day per week is forgiven |
| Self | 2 | Clarity | Active, 15 MP | 5-min guided journal that becomes tomorrow's priorities |
| Self | 3 | Bonds | Passive | Social quests also restore HP |
| Self | 3 | Mentor's Eye | Active, weekly | An in-depth AI review of your week with three recommendations |
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
| Hamiltonian Blade | Weapon | Legendary | +15% Mind XP; one free Insight per day | The Draft Wyrm |
| Boots of the Random Walker | Feet | Legendary | +20% AGI mastery; steps beyond target pay bonus XP | The 5K Revenant |
| Toda Chain | Accessory | Epic | Raises the streak bonus cap from 1.5× to 1.6× | 30-day streak |
| Cloak of Detailed Balance | Body | Epic | +10% XP in your currently weakest realm | All three realms completed 14 days running |
| Lens of Rough Interfaces | Head | Rare | +1 Insight charge per day | The Literature Abyss |
| Boots of the Dawnwalker | Feet | Rare | +10% XP for quests finished before 09:00 | Dawn Gate |

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

**Lore codex.** About 40 short entries, unlocked by level, rank, bosses and hidden quests. The AI writes each one in the System voice from a fixed lore bible stored in the app, and an entry is frozen once unlocked so the canon stays consistent. Read in order, they tell the story of the lattice, the Observer, and what waits at Σ.

## AI layer

Claude is the System's voice and planner: it chooses quests, decomposes bosses, and explains your progress. Numbers are computed on the device first; the AI interprets them and never invents them.

| Job | When | Input | Output |
| --- | --- | --- | --- |
| Quest generation | When you ask the System | 14 days of completions, stats, difficulty per realm, goals, open Gates and bosses, rest days, calendar load | 4 Daily objectives + 0–3 Side Quests as JSON |
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
| Settings | Reset time, rest days, integrations, sound, haptics, motion | Connect Health Connect |

**Outside the app.** A home-screen widget (Jetpack Glance) shows Daily Quest progress and the countdown. Notifications fire at the reset, three hours before the Daily expires, and for Emergency Quests, each with the System chime and silent during quiet hours.

## Tech architecture and data model

Native Android and offline-first: all game logic and data live on the phone, and only AI calls go out.

&#91;embedded content: app architecture · on-device core, two outbound calls\]

The game engine is plain Kotlin with no Android dependencies, so the XP maths, the difficulty controller and effect stacking can be unit-tested in isolation.

| Layer | Choice | Why |
| --- | --- | --- |
| UI | Kotlin, Jetpack Compose, Navigation Compose | Fine control of animation and Canvas drawing |
| Structure | MVVM, single activity, Hilt for dependency injection | Standard and well supported by Claude Code |
| Storage | Room (SQLite), DataStore for settings | Offline-first, one source of truth |
| Background | WorkManager | Daily reset, Gate expiry, Health Connect sync |
| Health | Health Connect client | Steps, exercise sessions, sleep |
| AI | Claude app via the share sheet and clipboard | Runs on your Claude plan; no API key or server |
| Motion and sound | Compose animation, Canvas particles, Lottie, SoundPool, haptics | The System feel |
| Charts and widget | Vico or custom Canvas; Jetpack Glance | Assessment charts; home-screen quest widget |

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

Build in six phases, each ending with something you actually use on your phone; never start a phase until the previous one has survived a week of daily use.

1. **Foundations.** Android Studio project, module layout, and `:core:engine` with the XP curve, difficulty controller and RewardCalculator, all unit-tested. Design system: colour tokens, fonts, a `HoloPanel` composable and the materialize animation. Exit: engine tests pass; one static Status Window renders in the System style.
2. **Playable loop (MVP).** Awakening onboarding, Player and attributes in Room, Ask the System and Import from Claude (the share bridge) with proposal cards, template Daily Quests as the offline fallback, manual check-off, Status Window with the stat radar, level-up animation, daily reset in WorkManager, notifications. Exit: you complete a Daily Quest every day for a week.
3. **Depth.** Penalty, Side and Emergency quests; Gates; bosses with HP bars and phases; achievements; inventory and equipment; sound effects and haptics.
4. **Progression.** Skill trees and active powers with MP and cooldowns, class awakening at level 10, rank trials, lore codex, avatar evolution.
5. **Integrations.** Health Connect, focus-timer verification, home-screen widget.
6. **AI.** Check-in ritual notifications that open Ask the System, smarter template quests, boss decomposition, the Insight power, and the weekly System Assessment with charts.

**Working with Claude Code.** Export this doc as Markdown into the repo as `SPEC.md`. Write a `CLAUDE.md` with the house rules: the engine never imports Android code, every engine change ships with a test, and colours come only from design tokens. Keep a `TODO.md` per phase, run each phase on its own branch, and review on your real phone after every phase.

**Installing.** No Play Store needed: enable USB debugging on your phone and install straight from Android Studio on the Mac mini.

**Parked for now.** Watching Git repositories — commit-based Mind quests and automatic verification from your research repos — is out of scope until after phase 6. Until then, research quests are checked off by hand or by the focus timer.

**Open questions for you.**

- [x] Final app name — keep ASCENT or choose your own.
- [x] Daily reset time and weekly rest day(s).
