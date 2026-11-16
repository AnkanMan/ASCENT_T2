# TODO — Release: ASCENT for friends

**Phase goal (`RELEASE.md`, SPEC "Release (friends build)"):** turn the
personal build into an app friends can install, set up for themselves and
update without losing data. No new game features.

**Exit (RELEASE.md):** one friend has installed 1.0.0, set up their own System
in Claude, played three days, and installed an update without losing data.

**Out of scope:** Phase 6 (Health Connect, focus-timer verification, the
home-screen widget, check-in notifications, weekly charts) comes after the
release as a normal update. Smarter templates and boss decomposition stay
shelved.

Rules: one task per request, in order. A task is done when
`./gradlew test assembleDebug` passes (plus `./gradlew assembleRelease` from
Task 4 on) and its **Check** is confirmed. Tasks follow the RELEASE.md build
order (section 8).

---

## Carried over (do these first)

SPEC: "never start a phase until the previous one has survived a week of
daily use." Friends get the app only after these.

- [ ] **Phase 5 Task 10, exit review:**
      - tests green, migration 3 → 4 present, no `INTERNET`;
      - the guard property test passes;
      - Previews for every new screen.
      - Then a week of real use, and the real test: back up, uninstall,
        reinstall, restore from the Awakening, and confirm level, XP, streak
        and every strength score match.
- [ ] **Phase 5 phone checks (Tasks 4–9):**
      - the guard (four of your own quests, "pays" and "paid", the form's
        warning, undo);
      - strength chips and the detail after a reset;
      - Settings → Backup: folder, Back up now, journal switch, restore and
        Undo restore, the Status warning;
      - Export data, opened on the Mac;
      - STATUS v4 sent to Claude, after pasting the updated
        `docs/ASCENT-System-Project-Instructions.md` into the Project.
- [ ] **Phase 4 Task 29, exit review** and the Phase 4 phone checks (D–N)
      (see DEVLOG).
- [ ] **Phase 3 Task 37 and phone checks** (see the Phase 3 entries in
      DEVLOG).
- [ ] **Phase 2 exit week** and the Phase 2 and Phase 1 phone checks (see
      DEVLOG for the lists).

---

## 0. Decisions

- [x] **0. Apply section 0 to SPEC.md** (approved 2026-11-16).
      - R1 name ASCENT; R2 keep `com.tirthankar.ascent` forever.
      - R3 personal content out; every friend starts blank.
      - R4 flavour names stay; project seeds go.
      - R5 signed APK now; R6 limited-distribution account before 2027.
      - Phase 6 un-shelved in SPEC and CLAUDE.md.
      - About credits include JetBrains Mono.
      - The template launcher icons were deleted (they broke the build).

      **Check:** SPEC.md reads correctly; DEVLOG entry.
- [ ] **Your step:** back up your own data on the phone (Settings → Backup →
      Back up now) and keep the zip off the phone, before any release build
      is installed over your debug build.

## 1. Identity

- [ ] **1. Identity:**
      - `applicationId` stays `com.tirthankar.ascent` (R2);
      - the home-screen name is ASCENT, from `strings.xml` (R1);
      - `versionName` "1.0.0" and `versionCode` 1, with a note on bumping
        both for every APK.

      **Check:** `aapt2 dump badging` on the release APK shows the id,
      label, version name and code.

## 2. Icon

- [ ] **2. Icon:**
      - the `ascent-icon.zip` files are in place: `mipmap-anydpi-v26` with
        background, foreground and monochrome layers; vector layers on the
        108 × 108 grid inside the safe zone; PNG fallbacks;
      - template icons removed (done in Task 0);
      - the manifest uses `@mipmap/ic_launcher` and `ic_launcher_round`.

      **Check:** on the phone, the home screen in light and dark mode,
      themed icons on, and a circular and a squircle mask.

## 3. Personal content

- [ ] **3. Audit and generic seeds** (RELEASE.md section 2):
      - search the repo (`assets/content/`, string resources, seed data,
        templates, tests and fixtures) for your name, institute,
        supervisor, project names and topics (hard rods, BMFT, μ∞, Wigner,
        ring vs line, KPZ, Toda, dipole), cluster names and personal
        baselines;
      - list every hit in DEVLOG before changing anything;
      - replace project seeds with a generic "Research or study project" arc
        (Literature → Work → Results → Write-up), keeping the Body and Self
        arcs;
      - R4: item and lore flavour names stay;
      - the Dossier, name, baselines, rest day and reset time start empty
        and come from the Awakening;
      - test-only physics fixtures may stay if they never reach the APK.

      **Check:** the audit list in DEVLOG; tests green; the content
      validator passes; a fresh install starts blank.

## 4. Release build

- [ ] **4. Release build** (RELEASE.md section 4):
      - signing config read from `~/.gradle/gradle.properties` or a
        gitignored `keystore.properties` (you create the keystore and keep
        it and both passwords in your password manager and on TM_SSD);
      - R8 with resource shrinking, and keep rules for kotlinx.serialization
        (bridge, content, backups) and Room;
      - debug code absent from release; verbose logging stripped;
      - no `INTERNET` in the merged manifest; fonts bundled.

      **Check:** `./gradlew assembleRelease` green; the signed APK installs;
      dex check shows no debug panel; merged manifest has no `INTERNET`.

## 5. Share-bridge onboarding

- [ ] **5. Friends' System** (RELEASE.md section 3):
      - a generic `assets/content/system-instructions-template.md` (the
        Project instructions with the Player section as placeholders and
        every personal example made generic);
      - Settings → The System → **Copy System instructions**, filled from
        the onboarding data, plus a button that shares `lore-bible.md` as
        a file;
      - Settings → The System → **How to set it up** (four steps; check the
        Claude free-plan Projects claim first);
      - onboarding says in one line that the app works without Claude;
        nothing nags.

      **Check:** Previews; the copied text has no personal content; a
      round trip with a fresh Claude Project.

## 6. About and support

- [ ] **6. About, Report a problem, What's new** (RELEASE.md section 5):
      - About: version and build date, the privacy line, font credits
        (Rajdhani, Inter, JetBrains Mono, with the OFL text), Kenney CC0,
        open-source licences (ask before adding the `oss-licenses` plugin),
        the originality line;
      - Report a problem: a local log (new), the last 7 days plus device,
        Android and app version, written to a file the player sees, then
        the share sheet;
      - What's new: shown once after an update, from a changelog asset.

      **Check:** Previews; on the phone.

## 7. Release test pass

- [ ] **7. Tests and checks before sending anything** (RELEASE.md section 7):
      - the release APK on a fresh emulator at API 28 and at the latest
        API: full Awakening, a full day, the bridge round trip, a level-up,
        a Gate, a power cast, backup and restore;
      - release-only: v1, v2, v3 blocks and Phase 4–5 fields parse in the
        release build; content loads; a debug-build backup restores in the
        release build;
      - upgrade path: install 1.0.0, play a day, install 1.0.1 over it,
        data intact;
      - the personal-content audit re-run on the release APK (`assets/` and
        `resources.arsc` strings);
      - the icon checks from Task 2.

      **Check:** every item ticked in DEVLOG.

## 8. Ship

- [ ] **8. Ship 1.0.0** to one friend first (`ASCENT-1.0.0.apk`); fix what
      they hit in 1.0.1; then the rest. Ask friends to set up backups on day
      one. Check the developer-verification timeline (R6).

      **Check:** the exit above.

---

### Risks

- **The signing key is unrecoverable.** Lose it and no update can install
  over a friend's copy. Two copies, password manager and TM_SSD.
- **R8 and kotlinx.serialization.** The most likely release-only bug; the
  release build gets its own parser and restore pass (Task 7).
- **Your own data.** Installing a release build over your debug build needs
  the same id and a signature match; if signatures differ, the install
  fails or needs an uninstall. Back up first and restore after.
- **The Claude free plan and Android's verification rules change.** Check
  both when you get to Tasks 5 and 8.
