<div align="center">

<img src="graphics/icon.webp" width="120" alt="白い熊 メッセージ icon" />

# 白い熊 メッセージ

**A black-and-pure-yellow SMS/MMS app with per-element theming, per-element fonts, and Sino-Japanese time & imperial-era dates.**

A fork of [Fossify Messages](https://github.com/FossifyOrg/Messages) with **major additions**: a granular per-element Theme & Colors system (black `#000000` / pure yellow `#FFFF00` by default), per-element font family/weight/size, an alpha-capable color picker, Japanese kanji clock readings and 令和 imperial-era dates, a 設定 toolbar launcher, and a fully black/yellow chrome — menus, action bar, dialogs, and toasts included.

Installs **side-by-side** with Fossify Messages (app id `shiroikuma.messeji`).

**📥 Latest release: [`1.8.0+23`](https://github.com/ShiroiKuma0/shiroikuma-messeji/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-messeji/releases)

</div>

---

## 🎨 Granular per-element theming, black & pure yellow

Every visible element — search bar, conversation list, message bubbles (received / sent / status & time), headers, menus, back arrows — has its own color slot on the **白い熊 メッセージ UI** page, with two-tier inheritance: foundation slots (background / primary / text) drive everything until you override a specific element. The app seeds a black background with pure-yellow (`#FFFF00`) text and accents on first launch, and the whole chrome follows: overflow menu, contextual action bar, popup menus, dialogs, and even toasts render black with a yellow frame.

---

## ✒️ Per-element fonts

Every themed text element can also pick its own **font family, weight, and size**, with a live sample line that redraws as you tweak. Import any font file via an “Add font…” picker and it becomes available to every element; the font picker previews each font in its own typeface.

---

## 🕐 Japanese time & imperial-era dates

Today’s messages show a Sino-Japanese clock reading (午前八時); older ones an imperial-era date (令和八年五月十四日（木曜日）) — in the conversation list, in-thread date separators, and search results. Both are configurable under 日時の表示形式: kanji / system / 24-hour / 12-hour time, and an imperial-dates toggle.

---

## 設定 One-tap settings from the toolbar

A tightly-set 設定 pair lives in the main toolbar: tap 設 to open the 白い熊 メッセージ UI page, 定 for the regular Settings screen. Each glyph is its own themeable text element.

---

## 🧑 人 avatar for pictureless contacts

Contacts without a photo get a brush-stroke yellow 人 glyph instead of the generated colored-letter avatar — everywhere: conversation list, thread, contacts, search, autocomplete.

---

## 🌈 Alpha-capable color picker with recents

The theme color picker adds an **alpha/transparency slider** (the stock one is opaque-only) and a shared recently-used-colors row, so translucent element colors are a first-class option.

---

## 🔓 No “fake version” nag

Built against a patched Fossify Commons that removes the anti-tamper/sideloading check entirely (it misfires on any renamed fork), and fixes Commons’ hard-coded `org.fossify.*` package assumptions — including reading the Contacts app’s shared private contacts.

## Built on Fossify Messages

A fork of [Fossify Messages](https://github.com/FossifyOrg/Messages) (app id `shiroikuma.messeji`, so it coexists with the official build). Fossify builds open-source, privacy-focused Android apps free of ads and unnecessary permissions; all credit for the messaging core goes to them. The code remains under the [GPL-3.0 license](LICENSE).

## Building

```bash
git clone git@github.com:ShiroiKuma0/shiroikuma-messeji.git
cd shiroikuma-messeji
# Requires JDK 17+, the Android SDK, and the patched Commons published to mavenLocal
# (see CLAUDE.md — github.com/ShiroiKuma0/shiroikuma-commons, branch custom).
./gradlew buildFoss        # signed foss release APK → ~/tmp/, auto-bumps BUILD_NUMBER
```
