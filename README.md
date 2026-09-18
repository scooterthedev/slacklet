<div align="center">

<img src="https://user-cdn.hackclub-assets.com/01a0b335-2a48-76e2-b015-4a9c2ec7b108/logo.png" alt="Slack for Wear OS" width="300">



# Slacklet - A Slack Client for WearOS

[![CI](https://github.com/scooterthedev/slacklet/actions/workflows/ci.yml/badge.svg)](https://github.com/scooterthedev/slacklet/actions/workflows/ci.yml)
[![Tests](https://img.shields.io/badge/tests-337%20passing-brightgreen)](.github/workflows/ci.yml)
[![Coverage](https://img.shields.io/badge/coverage-73%25%20logic-brightgreen)](#tests-and-coverage)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Wear OS](https://img.shields.io/badge/Wear%20OS-3%2B%20%28API%2030%29-4285F4?logo=wearos&logoColor=white)](https://developer.android.com/training/wearables)
[![Relay](https://img.shields.io/badge/relay-Ktor%203.5-087CFA?logo=ktor&logoColor=white)](docs)

</div>

> [!WARNING]
> This app and it's underlying features included are for educational purposes only, and are not affiliated with, endorsed by, or supported by Slack.



Slacklet is a standalone Slack client for wearOS. This app is fully independant from your other Slack devices. This is done via internal Slack APIs to demostrate the capabilities outside of it's normal usage.

I've built this because the official Slack app has no wearOS version, and I've always felt the need for one. Originally, I was planning on just using Public APIs to create a clone on my watch, but it ended up having a slew of issues including lag, missing features etc.

## What works

- Channel and DM lists, with unread and mention badges
- Reading conversations, including threads
- Sending messages
- Reactions (synced from your slack devices)
- An Activity feed for mentions, replies and reactions
- Search
- Custom emoji rendering
- Push notifications, with inline reply from the notification
- Slack markdown, permalink previews and DND

Huddles, Canvas's and any AI features have not been built in yet and are planned for the v2 release.

## How it works

```
Slack  <--- HTTPS ---  watch
  |
  |  Events API (signed)
  v
relay (Ktor, self-hosted)  --- FCM --->  watch
```

The watch calls Slack directly for everything it reads and writes. The relay exists only
because Slack has no way to push to a device: it receives Events API callbacks, works out
which messages should wake your watch, and sends a data-only FCM message containing the
notification text.

A signed-in watch enrolls itself with the relay on first launch. It sends a device id, a capability digest and its FCM token, and the relay establishes who
you are by asking Slack (`auth.test`) rather than trusting anything in the request. Slack
session tokens are never stored on the relay.


## Requirements

- Wear OS 3 or newer (minSdk 30); this was developed and tested against Wear OS 7 (API 37)
- JDK 17
- A Firebase project, for push
- A host to run the relay on, if you want notifications

## Build and run

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Tests and coverage

```bash
./gradlew test
./gradlew koverHtmlReport
```

If you want to run it on an emulator:

```bash
avdmanager create avd -n SlackWear7 \
  -k "system-images;android-37.0;android-wear-signed;arm64-v8a" -d wearos_large_round
```

Then set `hw.lcd.circular=yes` in `~/.android/avd/SlackWear7.avd/config.ini`. `avdmanager`
does not set it, and without it you get a square screen with none of the round crop or
bezel transformations, which makes the whole UI look wrong.

## Push notifications

Notifications need two things you have to provide yourself: a Firebase project and a host
for the relay.

1. Create a Firebase project and add an Android app for `com.scooter.slackwear` (and
   `com.scooter.slackwear.debug` if you want push in debug builds). Put the resulting
   `google-services.json` in `app/`. It is git-ignored.
2. Create a Slack app, subscribe to message events **on behalf of users** (not the bot
   section), and point the Request URL at your relay's `/slack/events`.
3. Deploy the relay and give it your Slack signing secret and a Firebase service account
   key.

## Credits and licensing

Lato is used under the SIL Open Font License. Colour values are Slack's published design
tokens.

This project is not affiliated with, endorsed by, or supported by Slack.

## AI Usage

AI was used to assist in the decompliation steps and the writting of parts of this codebase. All code written by an LLM was monitored and reviewed with passing tests.