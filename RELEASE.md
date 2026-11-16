# Release — ASCENT for friends

Turn the personal build into an app friends can install, set up for themselves
and update without losing data. No new game features in this phase.

Read with `CLAUDE.md` and `SPEC.md`. Same workflow: `TODO.md` from the build
order (section 8), one task at a time, `./gradlew test assembleDebug` green,
plus `./gradlew assembleRelease` green from Task 4 on, and a `DEVLOG.md` entry
per session.

Phase 6 (Health Connect, focus-timer verification, home-screen widget, check-in
notifications, weekly charts) comes **after** this release, as a normal update.

---

## 0. Decisions (approve before Task 0)

| # | Question | Proposed rule |
| --- | --- | --- |
| R1 | App name on the home screen | **ASCENT** |
| R2 | `applicationId` | Keep the current one **forever**. Changing it after friends install makes a second, separate app, and their data doesn't carry over. Check it now; if it still contains something like `example` or `test`, fix it in this phase, before anyone installs |
| R3 | Your personal content | Moves out of the app into your own data. Every friend starts blank (section 2) |
| R4 | Physics-flavoured item and lore names (Hard-Rod Gauntlets, Wigner Lens, lattice lore) | Keep: they're flavour, not personal data. Only things that identify you or your projects are removed |
| R5 | Distribution now | Signed APK shared directly (section 6) |
| R6 | Distribution from 2027 | Register the app in a free limited-distribution developer account before developer verification reaches India (section 6) |

---

## 1. App identity and icon

- **Icon files** come from `ascent-icon.zip`. Copy its `res/` folders into
  `app/src/main/res/`, replacing the template launcher icons (delete any old
  `ic_launcher*` files, including `.webp` ones, so nothing stale wins).
  - `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`: adaptive
    icon with background, foreground and a **monochrome** layer (themed icons on
    Android 13+).
  - `drawable/ic_launcher_{background,foreground,monochrome}.xml`: vector
    layers on the 108 × 108 adaptive grid; the art stays inside the safe zone.
  - `mipmap-*dpi/` PNGs: fallback for devices below API 26 (only used if
    `minSdk` < 26).
  - `ascent-icon-master.svg` and `gen.py` regenerate everything from one
    geometry; `ascent-icon-512.png` is for READMEs or a store listing.
- Manifest: `android:icon="@mipmap/ic_launcher"`,
  `android:roundIcon="@mipmap/ic_launcher_round"`, `android:label` from
  `strings.xml` (R1).
- **Versioning:** `versionName` as `MAJOR.MINOR.PATCH` (start at `1.0.0`),
  `versionCode` strictly increasing by 1 per release. Every APK you send must
  have a higher `versionCode` than the last.

---

## 2. Remove personal content

Nothing that identifies you or your projects ships in the APK.

1. **Audit.** Search the repo, including `assets/content/`, string resources,
   seed data, templates, tests and fixtures, for: your name, institute,
   supervisor, project names and topics (hard rods, BMFT, μ∞, Wigner, ring vs
   line, KPZ, Toda, dipole), cluster names and any personal baselines. List
   every hit in `DEVLOG.md` before changing anything.
2. **Seeds.** Replace project-specific seed arcs and quests with generic
   templates: a "Research or study project" arc (Literature → Work →
   Results → Write-up), plus the existing Body and Self template arcs.
   Tests may keep physics examples as fixtures only if they never reach the
   APK.
3. **Defaults.** The Dossier, name, baselines, rest day and reset time start
   empty and come from the Awakening onboarding.
4. **Your own data** is untouched: it lives in your database and your
   backups, not in the code. Make a backup before installing the release
   build over your debug build (Task 0).

---

## 3. Friend-proofing the share bridge

Friends can't use your Claude Project; each needs their own.

- **Bundled template.** Ship a generic
  `assets/content/system-instructions-template.md`: the current Project
  instructions with the Player section turned into placeholders and every
  personal example made generic.
- **Settings → The System → Copy System instructions.** Fills the template
  from the player's onboarding data (name, baselines, equipment, rest day,
  reset time, Dossier) and copies it to the clipboard, with a second button
  that shares `lore-bible.md` as a file for the Project's knowledge.
- **Settings → The System → How to set it up.** One screen, four steps:
  install the Claude app and sign in → create a Project called "ASCENT System"
  (Projects work on the free plan, which allows up to five) → paste the
  instructions and add the lore bible file → use **Ask the System**.
- **Without Claude.** The app must stay fully playable on templates alone.
  Onboarding says so in one line; nothing nags about Claude.

---

## 4. Release build

- **Signing key.** Generate one upload/signing keystore (Android Studio →
  Build → Generate Signed App Bundle or APK → Create new). Store the file and
  both passwords in your password manager **and** on TM_SSD. If this key is
  lost, no update can ever install over a friend's copy; they'd have to
  uninstall (keeping only what their backups hold).
- **No secrets in Git.** Gradle reads the keystore path and passwords from
  `~/.gradle/gradle.properties` or a gitignored `keystore.properties`.
- **Shrinking.** `isMinifyEnabled = true`, `isShrinkResources = true`. Add
  keep rules for kotlinx.serialization (every `@Serializable` class used in
  the bridge, content files and backups) and anything Room needs. R8 breaking
  serialization is the most likely release-only bug, so the release build gets
  its own test pass (Task 7).
- **Debug-only code stays out.** The Phase 4 debug panel and any test hooks
  live in the `debug` source set; verify they're absent from the release APK.
  Strip verbose logging in release.
- **No network.** Confirm the merged manifest has no `INTERNET` permission.
  Fonts are bundled, not downloadable.

---

## 5. About and support screens

- **About:** version and build date; "No account, no internet. Your data
  stays on this phone; backups go only where you choose."; credits for the
  fonts (Rajdhani and Inter, SIL Open Font License, with license text), the
  Kenney sound packs (CC0) and the open-source library licenses (the
  `oss-licenses` plugin or a generated list); "Original work, inspired in mood
  by system-style manhwa; no characters, names or art from any franchise."
- **Report a problem:** writes the last 7 days of the app's local log plus
  device model, Android version and app version to a text file and opens the
  share sheet. No personal data beyond what the log already holds; the player
  sees the file before sending.
- **What's new:** shown once after an update, from a short changelog in
  assets.

---

## 6. Distribution

**Now: a signed APK, shared directly.**
1. `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`,
   renamed `ASCENT-1.0.0.apk`.
2. Send it by Drive, Telegram or similar. Friends allow their browser or chat
   app to "Install unknown apps", install, and accept any Play Protect
   warning ("Install anyway") — expected for an app outside the Play Store.
3. **Updates:** same `applicationId`, same signing key, higher `versionCode`.
   Friends install the new APK over the old one; their data stays.
4. Ask friends to set up backups on day one (Phase 5).

**From 2027: developer verification.**
- Google's new rules link sideloaded apps to verified developers. They take
  effect on 30 September 2026 in Brazil, Indonesia, Singapore and Thailand,
  with a global rollout planned for 2027. India isn't in the first wave, so
  direct APKs install as usual for now.
- For students and hobbyists, Google offers a **free limited-distribution
  account**: no government ID or fee, apps shareable to up to 20 devices.
  Register ASCENT there before enforcement reaches your friends' countries;
  check the current timeline first, since it has changed before.
- The paid Play Console route (one-time fee, ID verification, store review)
  is only worth it if you ever want more than ~20 users.

---

## 7. Tests and checks before sending anything

- Release APK on a **fresh** emulator (no prior data) at `minSdk` and at the
  latest API: full Awakening, a full day, the share bridge round trip (Ask the
  System → paste a real Claude reply → Import), a level-up, a Gate, a power
  cast, backup and restore.
- Release-only regressions: every bridge block version (v1, v2, Phase 4/5
  fields) parses in the **release** build; content files load; backups
  written by the debug build restore in the release build.
- Upgrade path: install 1.0.0, play a day, install 1.0.1 over it, data
  intact.
- Personal-content audit re-run on the release APK itself (unzip and grep
  `assets/` and `resources.arsc` strings).
- Icon: home screen in light and dark mode, themed icons on, and a circular
  and a squircle launcher mask.

---

## 8. Build order

0. **Approve section 0.** Back up your own data. Note decisions in DEVLOG.md.
1. **Identity** — check `applicationId` (R2), app name, versioning.
2. **Icon** — install the resource files, remove the template icons, verify
   on device.
3. **Personal-content audit and generic seeds** (section 2).
4. **Release build** — signing config, R8 and keep rules, debug code
   excluded, no `INTERNET`.
5. **Share-bridge onboarding** — template, Copy System instructions, How to
   set it up.
6. **About, Report a problem, What's new.**
7. **Release test pass** (section 7).
8. **Ship 1.0.0** to one friend first; fix what they hit in 1.0.1; then the
   rest.

**Exit:** one friend has installed 1.0.0, set up their own System in Claude,
played three days, and installed an update without losing data.
