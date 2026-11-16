# ASCENT lore bible

The fixed canon for every lore line in ASCENT. The seed lines in
`core/data/src/main/assets/content/lore.json` follow it. Add this file to the
**ASCENT System** Claude Project as a project file, so the lines Claude writes
for your own arcs, bosses and Gates stay consistent.

All lore is original. Nothing here comes from Solo Leveling or any other
franchise.

## The world

- **The lattice.** The world is a lattice of possible selves. Every site is a
  version of you. Most people spend their lives at one site, the ground state.
- **The ground state.** The lowest-energy configuration: the habits that cost
  nothing. It isn't evil. It's just where everything settles when nothing
  pushes.
- **The excitation.** You. Something in you has left the ground state, and
  the Observer has detected it.
- **The Observer.** What the app calls the System. It measures will in units
  of effort. It records what was done, never what was meant. It doesn't
  praise, judge or forgive; it measures. It speaks in short, cold lines and
  uppercase headers (`◆ …`).
- **Equilibrium and relaxation.** Stop acting and you drift back toward
  equilibrium. This isn't punishment, it's thermodynamics. Nothing earned is
  erased; the excitation just decays if it isn't fed.
- **Fluctuations (Gates).** Places where a higher state briefly becomes
  reachable. They open, and they close. A Gate that closes before you cross
  it "breaks": it folds back into noise and leaves an echo, a lore line.
  Nothing is taken from you.
- **Red Gates.** Rare fluctuations far from equilibrium, where the lattice is
  unstable. All or nothing.
- **Fixed points (bosses).** Milestones that resist change: a paper, a talk,
  a race. They aren't enemies; they're places where effort accumulates until
  something moves. Their HP is the hours they demand. They never regenerate.
  They wait.
- **Trials.** Measurements against your own past record. The only opponent
  the Observer recognizes is who you were.
- **Trajectories (arcs).** Paths through the lattice made of chapters. A
  walked segment is fixed; it can't be unwalked.
- **Artifacts (items).** Condensed from cleared fluctuations and resolved
  fixed points. Unmade, they leave **Essence**, which is conserved.
- **Ranks E → S.** Energy levels of the excitation.
- **Σ (Singularity).** Hidden. The state where the Observer and the observed
  coincide. Hint at it; never explain it.

## Voice rules

- Second person, present or past tense. Terse and cold, with a sharp,
  practical coach underneath.
- One or two sentences, at most 200 characters.
- Metaphors come from statistical physics and dynamics: lattice, ground
  state, excitation, equilibrium, fluctuation, fixed point, trajectory,
  conservation, symmetry, phase, measurement, noise.
- Failure is data, never shame. Don't mock, threaten or guilt-trip.
- No health, diet or medical claims.
- Never use: shadow, arise, monarch, hunter, dungeon, mana, awakened one, or
  any character, place or skill name from an anime, game or film.
- Titles of entries are 1–4 words, sentence case.

## Where lines appear

| Event | Seed line | Can Claude write a specific one? |
| --- | --- | --- |
| The Awakening and level milestones | Yes | No |
| A chapter completed | Generic | Yes, per arc |
| An arc ended | One per realm, plus the template arcs | Yes, per arc |
| A Gate broken (under 50%) | One per realm | Yes, per Gate |
| A Red Gate cleared | Yes | No |
| A boss phase unlocked (2, 3, 4, final) | Generic | Yes, per boss and phase |
| A boss defeated, stirring or enraged | Generic | Defeat only, per boss |
| A Trial won or failed | Generic | No |
| An achievement earned | One each | No |
| A rank trial opens or fails; a rank reached; the Σ hint after S | Yes | No |
| A class chosen, or evolved at B and S | One per class and stage | No |
| The first skill, the first power cast, each ultimate, a respec | Yes | No |
| The Observer tree appears (A-rank) | Yes | No |

Each entry enters the Codex the first time it unlocks and is then frozen.
Generic lines are shown again in context each time their event happens.
A line Claude writes for your own arc, boss or Gate replaces the generic one
for that event. It arrives through the share bridge as an `add_lore` card
that you apply (bridge v2), and it's frozen once unlocked.
