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
wallpaper-derived Material You; four card backgrounds, from solid through to glass, which
samples the wallpaper behind each card and blurs it; and a top bar that can show the time,
the date, both or neither.

**Swipe navigation.** Optional, off by default. Swipe in from either side for Back, up from
the bottom for Home, up and hold for Recents. This exists because MIUI and HyperOS switch
third-party launchers back to on-screen buttons, and `performGlobalAction` is the only way
to press Back or open Recents without root, so it is implemented as an accessibility
service. The service is declared `canRetrieveWindowContent="false"`, so it cannot read the
screen, and it handles no accessibility events. The swipe targets are
`TYPE_ACCESSIBILITY_OVERLAY` windows, which a service may add without the draw-over-other-apps
permission, so enabling the service is the whole of the setup.

Note that hiding the on-screen button bar is a separate matter that Lumenberg cannot do for
you. `policy_control immersive.navigation` stopped working in Android 11. It now needs
`adb shell pm grant dev.lumenberg android.permission.WRITE_SECURE_SETTINGS`, and without it
the buttons remain and these gestures sit alongside them.

Using an accessibility service this way is against Google Play policy. That does not affect
GitHub releases, but it would rule out Play distribution.

**Doing things.** Optional, off by default, and separate from swipe navigation. With it on,
a request is carried out in your apps rather than answered with instructions: the assistant
is shown what is on screen, replies with one action at a time (open, tap, type, scroll,
back), and Lumenberg performs it. The user sees progress and the result, not the steps.

`performGlobalAction` and reading the screen both require an accessibility service, so this
is a second one, enabled separately from the gesture service and declared with the
screen-reading capability the gesture service deliberately lacks.

What leaves the device while a request runs: the foreground package name, the visible text,
and the labels of controls, sent to the assistant provider you configured. Password fields
are skipped. Nothing is sent when no request is running. There is no other redaction, so a
banking app or a visible one-time code would be included if it is on screen at the time.

Before pressing anything whose label looks like sending, buying or deleting, it asks first,
as an overlay above whatever app is in front. That check is a word list: it does not read
unlabelled icons and it only knows English, so it treats an unlabelled control as worth
asking about and errs towards asking too often. It is a speed bump, not a guarantee.

Sending accessibility-derived screen content to a third-party server is against Google Play
policy, as is using an accessibility service for gesture navigation. Neither affects GitHub
releases.

## Connecting an assistant

An assistant is optional. The launcher is fully usable without one.

| Provider | Sign-in |
|---|---|
| OpenRouter | OAuth with PKCE in the browser, or paste a key if the browser does not return. |
| GitHub Copilot | GitHub OAuth device flow, using an existing Copilot seat. Requires a client id, see below. |
| OpenAI, Anthropic, Google Gemini, DeepSeek | Paste an API key. Each option links to the provider's key page. |
| Ollama / LM Studio | Type the machine's address on the local network. No credential. |

Several assistants can be connected at once. Settings lists them, tapping one makes it the
assistant that answers, and the thread header has a swap control for switching mid
conversation. Each keeps its own chosen model.

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
Lumenberg-0.5.0.apk`), then press Home and select Lumenberg.

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
