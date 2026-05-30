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

The app ID is deliberately changed so this fork installs **alongside** upstream / other apps without
conflict. The namespace is intentionally kept as `org.fossify.messages` so `R`/`BuildConfig` and all
source packages remain unchanged — only the installed package id differs.

### Versioning & APK naming

We base our version on upstream and add a fork increment (`BUILD_NUMBER`).

- `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` **track upstream** (currently `1.8.0` / `20`).
- `BUILD_NUMBER` is **our** increment. It starts at `1` and bumps by `1` on every build with changes.
- Fork `versionName` = `"<VERSION_NAME>+<BUILD_NUMBER>"` (e.g. `1.8.0+1`).
- Fork `versionCode` = `VERSION_CODE * 10000 + BUILD_NUMBER` (e.g. `20 * 10000 + 1 = 200001`).
- Output APK filename = `shiroikuma-messeji_<VERSION_NAME>+<BUILD_NUMBER>_arm64-v8a.apk`
  (e.g. `shiroikuma-messeji_1.8.0+1_arm64-v8a.apk`).

So the first build is `+1` (`200001`), the next build with changes is `+2` (`200002`), and so on.

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

**Product flavors:** `core` (F-Droid), `foss`, `gplay` (Google Play). We ship `foss`.
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
