# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**白い熊 メッセージ** — a personal fork of [Fossify Messages](https://github.com/FossifyOrg/Messages),
an open-source, privacy-focused Android SMS/MMS app. Part of the Fossify ecosystem.
Written entirely in Kotlin targeting Android API 26–36.

This repository (`ShiroiKuma0/shiroikuma-messeji`, local dir `shiroikuma-messages`) is a fork. We track upstream
(`FossifyOrg/Messages`) and layer our own customizations on top of it.

## Fork Workflow — READ THIS FIRST

This is the most important section. The whole point of this repo is to maintain a small set of
customizations on top of upstream and rebuild as upstream releases new versions.

### Git remotes & branches

- `origin` → `git@github.com:ShiroiKuma0/shiroikuma-messeji` — our fork (push here).
- `upstream` → `https://github.com/FossifyOrg/Messages.git` — the original (read-only, for rebasing).
- **`main`** mirrors upstream's `main`. We do **not** develop on it.
- **`custom`** is our development branch. **All our work lives here.** This is the default working branch.

### Our customizations (what makes this a fork)

| What | Value | Where |
| --- | --- | --- |
| Installed app ID | `shiroikuma.messeji` | `gradle.properties` → `APP_ID` |
| Code namespace | `org.fossify.messages` (unchanged from upstream) | `gradle.properties` → `APP_NAMESPACE` |
| App launcher label | `白い熊 メッセージ` | `app_launcher_name` in `values/strings.xml` + `values-ja/strings.xml` |
| Signing | per-app keystore | `keystore.properties` (gitignored) → `~/.android-keystores/shiroikuma-messages.jks` |
| UI default palette | black `#000000` + **pure yellow `#FFFF00`** (never material `#FFEB3B`) | `PALETTE_BLACK` / `PALETTE_YELLOW` in `helpers/Constants.kt` |

The app ID is deliberately changed so this fork installs **alongside** upstream / other apps without
conflict. The namespace is intentionally kept as `org.fossify.messages` so `R`/`BuildConfig` and all
source packages remain unchanged — only the installed package id differs.

### Versioning & APK naming

We base our version on upstream and add a fork increment (`BUILD_NUMBER`).

- `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` **track upstream** (currently `1.10.3` / `27`).
- `BUILD_NUMBER` is **our** increment. It starts at `1` and bumps by `1` on every build with changes.
  It is stored bare in `gradle.properties` and **always rendered zero-padded to three digits** —
  `+001`, `+014`, `+011` — so `~/tmp`, the release page and the tag list keep sorting in build order
  (unpadded, a string sort puts `+10` before `+9`). The padding is applied once, in
  `app/build.gradle.kts`'s `forkVersionName`, which is the single source of both the `versionName`
  and the APK filename.
- Fork `versionName` = `"<VERSION_NAME>+<NNN>"` (e.g. `1.9.1+011`).
- Fork `versionCode` = `VERSION_CODE * 10000 + BUILD_NUMBER` (e.g. `23 * 10000 + 11 = 230011`) —
  arithmetic, so the padding never touches it.
- Output APK filename = `shiroikuma-messeji_<VERSION_NAME>+<NNN>_arm64-v8a.apk`
  (e.g. `shiroikuma-messeji_1.9.1+011_arm64-v8a.apk`).

So the first build is `+001` (`200001`), the next build with changes is `+002` (`200002`), and so on.

**Releases published before 2026-09-04 are unpadded** (`1.9.1+8`, `1.9.1+10`). Never retag or rename
what is already published — the padding simply starts from the current build. Expect the padded tags
to sort below the older unpadded ones for a while.

### Building

Requires **JDK 17+** and the **Android SDK**. On this machine the default `java` is JDK 11, so builds
must run with JDK 21 and the SDK on the environment:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildFoss
```

(`sdk.dir` lives in the gitignored `local.properties` → `/home/shiroikuma/android-sdk`.)

`buildFoss` (defined in `app/build.gradle.kts`):
1. builds `assembleFossRelease` (signed, via `keystore.properties`),
2. copies the APK to `~/tmp/shiroikuma-messeji_<version>_arm64-v8a.apk`,
3. **auto-increments `BUILD_NUMBER`** in `gradle.properties` for the next build.

### Rebasing onto a new upstream release

When the user says a new upstream version is out:
1. `git fetch upstream`
2. Update `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` to match the new upstream release,
   and **reset `BUILD_NUMBER` to `1`**.
3. Rebase `custom` onto the new upstream tag/`main`, resolving conflicts so all our customizations
   (above table) are preserved.
4. Build the new `+1` version with `./gradlew buildFoss`.
5. Continue developing further changes as `+2`, `+3`, …

### HARD RULES (do not violate)

- **After implementing a change the user asked for, always build it** with `buildFoss` (via the
  `build-apk` skill) **without waiting to be asked**, and confirm the build succeeds — **then ask**
  whether to push the APK to the phone.
- **Never install/push APKs to the phone automatically.** Only after the user confirms, `adb push`
  the APK to `/sdcard/tmp/` (the user installs it manually from there). Do **not** use `adb install`.
- **Never commit or push on your own.** Develop and build, let the user test, and **only commit/push
  when the user explicitly instructs**. Push goes to `origin` (`custom` branch).

## Build Commands

```bash
./gradlew buildFoss              # Our fork build: foss release → ~/tmp + bump BUILD_NUMBER (use this)
./gradlew assembleFossRelease    # Build foss release APK only (signed via keystore.properties)
./gradlew assembleDebug          # Build debug APK (app id gets .debug suffix)
./gradlew detekt                 # Run static analysis (detekt)
./gradlew lintFossRelease        # Run Android lint checks
```

**Product flavors:** `foss`, `gplay` (Google Play). We ship `foss`. (Upstream dropped `core` with 1.10.)
There are no unit or instrumented tests in this repository.

## Code Style

- Kotlin official style; 4-space indentation, LF line endings.
- Max ~160 chars/line (editorconfig) / 120 chars (detekt).
- Detekt and lint both use baseline files (`app/detekt-baseline.xml`, `app/lint-baseline.xml`) —
  new violations are not allowed.

## Architecture

### Entry points & UI

- `SplashActivity` → `MainActivity` (conversation list). `ThreadActivity` is the per-conversation
  message view. `NewConversationActivity`, `ConversationDetailsActivity`, `SettingsActivity`,
  archive/recycle-bin/blocked-keyword activities round out the screens. All extend `SimpleActivity`.

### Messaging pipeline

- `messaging/` holds the send path: `Messaging`, `MessagingUtils`, `SmsManager`, `SmsSender`,
  `ScheduledMessage`. `receivers/` holds the broadcast receivers for the SMS/MMS lifecycle
  (`SmsReceiver`, `MmsReceiver`, `Sms*StatusReceiver`, `DirectReplyReceiver`, `MarkAsReadReceiver`,
  scheduled-message alarms, etc.). MMS uses the `mmslib` dependency.
- `helpers/MessagesReader` / `MessagesWriter` read/write the system SMS/MMS content providers;
  `MessagesImporter` / `MessagesReader` handle backup/restore.

- **A downloaded MMS is acknowledged by the app itself** (`messaging/MmsAck.kt`, from 1.10.3+003): neither
  `downloadMultimediaMessage` nor the library does it, and an unanswered MMSC parks the message in the
  operator's web album (O2's “MMS Album” SMS). `receivers/MmsReceiver` is therefore a plain
  `BroadcastReceiver` that takes the library's result broadcast first and hands it to an inner
  `MmsReceivedReceiver` made for that one download — the library's `onReceive` is `final` and gives its
  callbacks no transaction id. The acknowledgement goes out through `SmsManager.sendMultimediaMessage` on
  the notification's subscription; the result lands in `receivers/MmsAckReceiver`.
- **Diagnostics** (`helpers/Diag.kt`, section “Diagnostics” on the UI page): every kind behind its own
  switch, off by default, files in `Android/data/<app id>/files/diag/` as `.txt`. The one kind so far is
  `mms` (“MMS transport”). Follows the global diagnostics-ui-settings skill.

### Persistence

- Room database `databases/MessagesDatabase` (KSP-generated, schemas under `app/schemas`) caches
  conversations/messages; `helpers/MessagingCache` fronts it. `models/` holds the data classes
  (`Conversation`, `Attachment`, `Draft`, `ScheduledMessage`, …).

### Cross-cutting

- **EventBus** for decoupled component messaging (`models/Events`).
- **`Config`** (`helpers/Config`) is a SharedPreferences wrapper, accessed via `context.config`.
- Heavy reliance on **`org.fossify:commons`** (version in `gradle/libs.versions.toml`) for base
  activities, theming, and shared UI. Check commons source when base-class behavior is unclear.

## Key Configuration Files

- `gradle.properties` — fork app id/namespace, version name/code, `BUILD_NUMBER`.
- `gradle/libs.versions.toml` — single source of truth for all dependency versions.
- `app/build.gradle.kts` — Android config, flavors, signing, the `buildFoss` task, fork version logic.
- `keystore.properties` — signing config (gitignored; points to `~/.android-keystores/shiroikuma-messages.jks`).
- `detekt.yml` / `lint.xml` — static-analysis config (at project root).

## Patched Fossify Commons (anti-tamper removed + fork-package fixes)

This fork builds against **our patched Fossify Commons**, not the upstream binary. Upstream Commons
6.1.x shows a "You are using a fake version of the app…" dialog (and silently breaks "Customize
colors") whenever the installed app id is not `org.fossify.*` — always the case for us (`shiroikuma.*`).

- **Source:** the `shiroikuma-commons` fork (`~/git/shiroikuma-commons`, branch `custom`), which strips
  Commons' anti-tamper "fake version" / sideloading checks out entirely **and** carries fork-package
  fixes for spots where Commons hard-codes `org.fossify.*` (documented in that repo's CLAUDE.md).
- **Delivery:** published to the local Maven repo, consumed as `commons = "7.1.3-sk4"` in
  `gradle/libs.versions.toml` (`mavenLocal()` is already a repository in `settings.gradle.kts`).
- Because Commons itself no longer nags, this app carries **no** in-app workaround — no `getPackageName`
  spoof, no `SIDELOADING_FALSE`, no `res/raw/keep.xml`.

**Fork-package fix relevant here:** `MyContactsContentProvider` used to return an empty list for
non-`org.fossify.*` consumers, hiding the Contacts app's shared private/local contacts from this app.
Fixed in the commons fork as of `-sk2` — nothing to change in this repo beyond the pin.

**Commons 7 (from 1.10.3):** upstream moved to Commons 7.x, which drops the "thank you" feature locks
(`FeatureLockedDialog`, `isOrWasThankYouInstalled`, `addLockedLabelIfNeeded`) and adds a timed donation
prompt. The fork was re-cut as `7.1.3-sk1` (all of `6.1.6-sk1`–`sk19` replayed; the new prompt is a
no-op). **Upstream code that arrives with a rebase may reference Commons drawables or colours the fork
removed** (`sk18`: no colour or shape in XML) — the build then fails at resource linking, and the fix is
to draw that view in code in role colours, as `setupAudio` in `helpers/AttachmentPreviews.kt` does.

**Commons `7.1.3-sk4` (from 1.10.3+006):** the restyled blocked-numbers screen, and nine new `UiRole`s.
The menu icon roles for view, share and block and `MENU_DESTRUCTIVE` are the same items as our
`ThemeSlot.MENU_ICON_VIEW` / `_SHARE` / `_BLOCK` and `MENU_WARNING`, so those slots are mapped onto them
in `ThemeSlot.uiRole` — one colour, one row. **Whenever a further slot is mapped, raise the number in
`UI_ROLES_MIGRATED_KEY`** (`ui_roles_migrated_2` now) so the one-time move of stored colours runs again;
the flag is kept out of settings archives, and a theme import clears it.

**On a fresh machine, or after an upstream bump changes the Commons version — republish before building:**

```bash
cd ~/git/shiroikuma-commons
git checkout <new-commons-tag>     # then re-apply all patches (anti-tamper strip + fork-package fixes)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :commons:publishToMavenLocal -PVERSION=<ver>-skN
```

Then set this app's `commons` pin to the same `<ver>-skN` (currently `7.1.3-sk4`; `-skN` is our patch
revision — see the commons fork's CLAUDE.md). The patched AAR lives only in `~/.m2`, not in the repo.

## Commit convention — no Claude attribution

Do **not** add any `Co-Authored-By: Claude …` trailer — nor a "🤖 Generated with Claude Code" / Anthropic-attribution line — to commit messages or PR bodies in this repo. 白い熊 does not want Claude attribution in the history; this **overrides** the harness's default to append such a trailer. End commit messages at the last line of the body. (The existing history was scrubbed of these trailers on 2026-06-08; the global rule lives in `~/.claude/CLAUDE.md`.)
