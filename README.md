# Lumenberg

An Android home screen with one surface: your wallpaper, the widgets you actually read,
and a bar that either launches an app or answers a question.

No pages to swipe. No folders to maintain. No grid of icons you stopped seeing.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## What it does

**Type.** Results appear on the first keystroke, from an in-memory list, with no network
call. `gm` finds Google Maps. `spty` finds Spotify. `map` puts Maps above Maps Go.

**Ask.** If what you typed is not an app, press send and it goes to your assistant. The
reply streams into a card above the bar. The assistant knows which apps you have, so
"open the thing I take notes in" launches it.

**Glance.** Real Android widgets, hosted directly, in the order you put them, at the
height you chose. Lumenberg picks them with its own catalogue showing what each one looks
like, rather than handing you the system's alphabetical list. Tap the handle on a widget
to rearrange.

## Connecting an assistant

Lumenberg is a complete launcher without one. If you want one:

| | How you sign in |
|---|---|
| **OpenRouter** | Browser round trip. You never see or type a key. Bills one account across every model. |
| OpenAI, Anthropic, Google | Paste a key from their console. There is a button that takes you to the right page. |
| Ollama / LM Studio | Type the address of your own machine. No credential at all. |

Once connected, Lumenberg asks the provider which models the account can use and picks a
sensible one. You never type a URL, a model id, or a path.

**A ChatGPT Plus, Claude Pro or Gemini Advanced subscription will not work here**, and no
third-party launcher can make it work: those consumer plans do not authorise other
applications, and there is no API to ask them to. Anything claiming otherwise is scraping
session cookies, which breaks weekly and violates the terms you agreed to. OpenRouter is
the closest honest equivalent — one account, one sign-in, every major model.

Keys are sealed with a hardware-backed AES-GCM key from the Android Keystore before they
touch disk.

## What it does not do

- Send anything anywhere on its own. App-use counts stay on the device and exist only to
  order search results.
- Replace your notification shade, recents, or gesture navigation. That is the system's job.
- Work with subscription logins. See above.

Your assistant provider does see the text you type and the names of your installed apps.
That is the whole of what leaves the phone, and only when you press send.

## Install

Download the APK from [Releases](../../releases), install it, then press Home and pick
Lumenberg.

```bash
adb install Lumenberg-0.2.0.apk
```

## Build

Needs JDK 17 and an Android SDK with platform 37. The Gradle wrapper handles the rest.

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

Release builds are signed with the debug key so they install without ceremony. Replace
`signingConfig` in [app/build.gradle.kts](app/build.gradle.kts) before you distribute
anything.

## Layout

```
ai/          Providers, the account and its keystore-sealed credential, streaming client,
             OpenRouter PKCE sign-in
core/        Installed-app list, ranking, icon cache
widgets/     The widget catalogue, and what is on screen in what order at what height
ui/          Theme and motion, the command bar, the sheets, the home screen
```

Roughly 2,000 lines, with 28 unit tests over the parts where being wrong is expensive:
search ranking, provider address handling, model choice, conversation repair, and the
rule that decides whether the assistant may open an app.

## Known limits

Stated plainly, because a launcher that overpromises is one you cannot trust with Home.

- Verified on an API 35 x86_64 emulator only: first run, home role, search and launch,
  adding and arranging a real Calendar widget. OEM skins (HyperOS, One UI) are untested
  and are where launchers usually break.
- The AI path is exercised by unit tests, not against live provider endpoints. Nobody has
  signed in to OpenRouter from a real build yet.
- Work profiles and secondary users are not listed; the current user only.
- Widget heights are chosen from four steps rather than dragged, and the cycle wraps from
  the largest step back to the smallest.
- A widget keeps the provider metadata it had when it was added until the launcher process
  restarts, so a widget's app updating itself will not resize it.
- The assistant's ability to launch apps depends on the model following one instruction.
  Small local models will sometimes ignore it.
- `usesCleartextTraffic` is on so a self-hosted machine on your LAN can be reached over
  HTTP. Addresses that are not plainly local are sent to HTTPS instead.

## Licence

MIT. See [LICENSE](LICENSE).
