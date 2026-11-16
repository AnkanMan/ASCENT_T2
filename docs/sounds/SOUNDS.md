# ASCENT sound effects

All sounds are by Kenney (www.kenney.nl), licensed **CC0 1.0**
(public domain; credit not required). Downloaded 2026-11-14 from
Kenney's own uploads on OpenGameArt:

| Pack | Source page | Format |
| --- | --- | --- |
| Interface Sounds 1.0 | https://opengameart.org/content/interface-sounds | ogg |
| Sci-fi Sounds | https://opengameart.org/content/sci-fi-sounds | ogg |
| Digital SFX Set | https://opengameart.org/content/63-digital-sound-effects-lasers-phasers-space-etc | mp3 |
| RPG Sound Pack | https://opengameart.org/content/50-rpg-sound-effects | ogg (alternates not used yet) |

## In the app (`core/designsystem/src/main/res/raw/`)

Chosen by name and length only; I couldn't listen to them. Audition them and
swap any that feel wrong (see alternates below).

| Resource | SPEC event | Original file | Pack |
| --- | --- | --- | --- |
| `sfx_system_chime.mp3` | System notification: two-note chime | `twoTone1.mp3` (1.2 s) | Digital SFX |
| `sfx_quest_complete.ogg` | Quest complete: soft click + shimmer | `confirmation_001.ogg` | Interface |
| `sfx_level_up.mp3` | Level-up: rising swell | `powerUp1.mp3` (1.2 s) | Digital SFX |
| `sfx_penalty.ogg` | Penalty warning: low glitchy buzz | `glitch_001.ogg` | Interface |
| `sfx_boss_hit.ogg` | Boss hit: deep thud | `lowFrequency_explosion_001.ogg` | Sci-fi |
| `sfx_chest_open.ogg` | Chest opening | `forceField_000.ogg` | Sci-fi |
| `sfx_unlock.mp3` | Achievement or lore unlock | `threeTone1.mp3` (1.7 s) | Digital SFX |
| `sfx_cast.mp3` | Power cast: rising zap under the sigil ring (Phase 4) | `zapThreeToneUp.mp3` | Digital SFX |
| `sfx_skill_unlock.ogg` | Skill unlocked (Phase 4) | `maximize_001.ogg` | Interface |
| `sfx_class.ogg` | Class awakened or changed (Phase 4) | `glass_001.ogg` | Interface |
| `sfx_rank_up.mp3` | Rank up (Phase 4) | `threeTone2.mp3` | Digital SFX |

The Phase 4 picks came from the alternates already downloaded (same packs, CC0),
so nothing new was fetched. Total size is about 200 KB.

## Alternates (`docs/sounds/alternates/`, not packaged)

The filename prefix names the event it could replace:

- `chime_glass_001.ogg` (now also `sfx_class`), `chime_twoTone2.mp3`
- `complete_confirmation_002.ogg`, `complete_select_003.ogg`
- `levelup_threeTone2.mp3` (now also `sfx_rank_up`), `levelup_zapThreeToneUp.mp3` (now also `sfx_cast`)
- `penalty_error_003.ogg`, `penalty_glitch_003.ogg`
- `bosshit_lowFrequency_explosion_000.ogg`, `bosshit_impactMetal_000.ogg`
- `chest_scifi_doorOpen_000.ogg`, `chest_maximize_004.ogg`
- `unlock_maximize_001.ogg` (now also `sfx_skill_unlock`), `unlock_tone1.mp3`

To swap one, copy the alternate over the resource in `res/raw`, keeping the
resource name (lowercase, letters, digits and underscores only).

SPEC's optional dark drone loop for the Status screen isn't included yet.
