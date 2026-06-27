---
name: build-apk
description: Build the signed foss release APK with the buildFoss Gradle task, then deliver it automatically via the global /after-build skill (adb push if a phone is connected, else scp to skhw — no transfer prompt). Use whenever the user asks to build the app, build the APK, make a release build, or build and send to the phone.
---

# Build the foss release APK and optionally send to phone

## Steps

1. **Note the output filename.** Read the current version and build number:
   - `grep -E 'VERSION_NAME|BUILD_NUMBER' gradle.properties`
   - The APK will be `shiroikuma-messeji_<VERSION_NAME>+<BUILD_NUMBER>_arm64-v8a.apk`, using the `BUILD_NUMBER` value **before** the build (the task bumps it afterward).
   - versionCode for that build = `VERSION_CODE * 10000 + BUILD_NUMBER`.

2. **Build** (needs JDK 21 — the default `java` on this machine is JDK 11):
   - `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildFoss < /dev/null`
     (the `< /dev/null` guarantees it never blocks on stdin)
   - This runs `assembleFossRelease`, copies the signed APK to `~/tmp/<apk name>`, and auto-increments `BUILD_NUMBER` in `gradle.properties`.
   - The task prints `>>> <path>` and `>>> versionCode <n>`; use those to confirm the exact filename and code, and confirm `BUILD SUCCESSFUL`.

3. **Deliver automatically via the global /after-build skill** — every build, no asking. After the signed APK is in `~/tmp/`, invoke **/after-build**: it runs `/adb-check` UNSANDBOXED (a sandboxed check falsely reports no device), then `/adb-push` to `/sdcard/tmp/` if a phone is connected, otherwise `/scp` to `skhw:~/tmp/`, and announces the filename. Never `adb install` — the user installs manually from `/sdcard/tmp/`.

## Note — deliver directly, do not rely on a task prompt

Unlike some sibling forks, this repo's `buildFoss` task (`app/build.gradle.kts`) intentionally has **no**
interactive `read` / adb-push prompt — it only builds, copies to `~/tmp`, and bumps `BUILD_NUMBER`.
Delivering the APK via **/after-build** is Claude's job (step 3), done automatically after the build.

## Signing

Release signing is non-interactive: `app/build.gradle.kts` reads credentials from `keystore.properties`
(falling back to `SIGNING_*` env vars). `keystore.properties` points to
`~/.android-keystores/shiroikuma-messages.jks`. If neither is present the build is unsigned and the APK
will not install.

## Prerequisite — patched Commons in mavenLocal

This app builds against our patched Fossify Commons (`commons = "6.1.6-sk2"` in
`gradle/libs.versions.toml`), resolved from `mavenLocal()` (`~/.m2`). On this machine it is already
published, so `buildFoss` just works. **On a fresh machine, or if `~/.m2` was cleared**, the build fails
with `Could not resolve org.fossify:commons:6.1.6-sk2` — publish it first:

```bash
cd ~/git/shiroikuma-commons && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew :commons:publishToMavenLocal -PVERSION=6.1.6-sk2
```

See the `shiroikuma-commons` repo's CLAUDE.md for the patch details.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
