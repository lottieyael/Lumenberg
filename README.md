# Lumenberg

An open-source Android launcher written in Kotlin and Jetpack Compose. One home screen over
the wallpaper, with widgets and a single command bar at the bottom. There is no app grid, no
pages and no folders. minSdk 28, compileSdk 37.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## What it does

**Command bar.** Typing filters the installed app list. Pressing send instead passes the text
to a configured AI assistant, whose reply streams into a card above the bar. The assistant is
given the labels of installed apps, so it can launch one when asked.

**Search.** Instant, in-memory, no network. It matches on exact name, prefix, word prefix,
initials (`gm` finds Google Maps), substring, and dropped-letter subsequence (`spty` finds
Spotify). Local launch counts break ties between equal matches.

**Widgets.** Real Android widgets hosted through `AppWidgetHost`. They are added from
Lumenberg's own in-app catalogue rather than the system `ACTION_APPWIDGET_PICK` picker, which
is a bare list and crashes on some builds.

**Appearance.** Light, dark or follow-system; a choice of accent colour including
wallpaper-derived Material You; adjustable surface opacity for the cards over the wallpaper.

## Connecting an assistant

An assistant is optional. The launcher is fully usable without one.

| Provider | Sign-in |
|---|---|
| OpenRouter | OAuth with PKCE in the browser. No API key is shown or typed. |
| GitHub Copilot | GitHub OAuth device flow, using an existing Copilot seat. Requires a client id, see below. |
| OpenAI, Anthropic, Google Gemini, DeepSeek | Paste an API key. Each option links to the provider's key page. |
| Ollama / LM Studio | Type the machine's address on the local network. No credential. |

After connecting, Lumenberg queries the provider's `/models` endpoint and picks a default.
There is no URL or model id to type.

Credentials are encrypted with a hardware-backed AES-GCM key from the Android Keystore before
being written to disk.

### GitHub Copilot client id

Copilot requires a GitHub OAuth App client id compiled into the build. Register one at
[github.com/settings/developers](https://github.com/settings/developers) with device flow
enabled, then set it as `CLIENT_ID` in
[GitHubAuth.kt](app/src/main/java/dev/lumenberg/ai/GitHubAuth.kt). A device-flow client id has
no secret, so shipping it in an APK is safe, but it must be your own. Until one is set, the
Copilot option says so rather than failing.

### Subscription logins are not supported

ChatGPT Plus and Claude Pro subscriptions cannot be used. Subscription OAuth exists, and backs
OpenAI's Codex CLI and Anthropic's Claude Code, but both vendors restrict it to their own
clients. Anthropic prohibited subscription OAuth tokens in third-party tools in its February
2026 terms and began enforcing in April 2026 with account bans. Google banned third-party use
of Gemini CLI's OAuth in February 2026, enforced it from March, and removed Gemini Code Assist
for consumer accounts in June 2026.

GitHub is the exception: it documents third-party applications making Copilot requests on
behalf of an authorising user, which is why Copilot is supported.

## Privacy

App launch counts are stored locally, used only to order search results, and never uploaded.
The AI provider receives the text you type and the labels of your installed apps, only when
you press send. There is no analytics or telemetry.

## Install

Download the APK from [Releases](../../releases), install it (`adb install
Lumenberg-0.2.0.apk`), then press Home and select Lumenberg.

## Build

Requires JDK 17 and Android SDK platform 37.

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

Release builds run through R8 with resource shrinking (2.4 MB versus 44 MB unshrunk) and are
signed with the debug key for convenience. Replace `signingConfig` in
[app/build.gradle.kts](app/build.gradle.kts) before distributing a build. Releases are
published locally with `gh`; there is no CI.

## Source layout

```
ai/        Provider definitions, account storage and Keystore sealing, streaming
           client, OpenRouter PKCE and GitHub device flow
core/      Installed app list, search ranking, icon cache
widgets/   Widget catalogue, and which widgets are placed in what order at what height
ui/        Theme, command bar, sheets, home screen
```

Roughly 2,000 lines of Kotlin, with 33 unit tests covering search ranking, host address
normalisation, model selection, conversation history repair, and the rule deciding whether the
assistant may launch an app.

## Known limits

- Tested on an API 35 x86_64 emulator and one physical device. OEM skins are largely untested.
- OpenRouter and Copilot sign-in have not been verified end to end against live endpoints.
- Copilot's seat token exchange uses `api.github.com/copilot_internal/v2/token`. Every client
  outside GitHub's own SDK uses it, but GitHub has not committed to it as a stable API.
- Work profiles and secondary users are not listed. Current user only.
- Widget heights are chosen from four preset steps rather than dragged, and cycling wraps from
  the largest step back to the smallest.
- A hosted widget keeps the provider metadata it had when it was added until the launcher
  process restarts.
- `usesCleartextTraffic` is enabled so a self-hosted machine on the LAN can be reached over
  HTTP. Addresses that are not plainly local default to HTTPS.

## Licence

MIT. See [LICENSE](LICENSE).
