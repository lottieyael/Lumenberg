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
| **GitHub Copilot** | Device flow: GitHub shows a box, you type a short code. Uses the Copilot seat you already pay for. Needs a client id, see below. |
| OpenAI, Anthropic, Google, DeepSeek | Paste a key from their console. There is a button that takes you to the right page. |
| Ollama / LM Studio | Type the address of your own machine. No credential at all. |

### GitHub Copilot needs a client id

Copilot is the one subscription that a third-party app may legitimately use: GitHub
documents applications making Copilot requests on behalf of a user who authorised them.
It needs an OAuth App that belongs to *your* build, because borrowing another product's
client id is the pattern the other vendors ban.

Register one at [github.com/settings/developers](https://github.com/settings/developers)
with device flow enabled, then put its id in `CLIENT_ID` in
[GitHubAuth.kt](app/src/main/java/dev/lumenberg/ai/GitHubAuth.kt). A device-flow client id
carries no secret, so shipping it in the APK is safe. Until you set one, the Copilot option
says so instead of failing.

Once connected, Lumenberg asks the provider which models the account can use and picks a
sensible one. You never type a URL, a model id, or a path.

**A ChatGPT Plus or Claude Pro subscription will not work here.** Subscription sign-in
does exist — it is what backs OpenAI's Codex CLI and Anthropic's Claude Code — but both
vendors have closed it to everyone else. Anthropic's terms have prohibited subscription
OAuth tokens in third-party tools since February 2026 and it has been enforced since April,
with account bans. OpenAI's credential is scoped to Codex; their own documentation points
you at a Platform API key for anything else, and third-party clients reusing the Codex
client id are refused at token exchange.

So this is a licensing wall, not a technical one, and a launcher that climbed it would get
its users banned. Google went the same way: it banned Gemini CLI's OAuth in third-party
tools in February 2026, enforced it from March, and removed Code Assist for consumer
accounts entirely in June.

GitHub is the exception, which is why Copilot is in the table above. Otherwise OpenRouter
is the closest honest equivalent: one account, one sign-in, every major model.

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
  signed in to OpenRouter or Copilot from a real build yet.
- Copilot's seat-token exchange uses `copilot_internal/v2/token`, which is what every
  client outside GitHub's own SDK uses, but GitHub has not committed to it. If it moves,
  Copilot breaks and the other providers do not.
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
