# light-relay-inbox

**Relay Inbox**: the Light Phone III end of the agent relay. Messages from the
agent arrive as pushes through Light's push server, and wait here as an inbox.
Replies go back to the reply inbox on the Unraid box. The server side is
[`light-relay`](../light-relay).

Sibling of `light-agent-inbox` (tool + vendored `sdk/`). Do not hand-edit `sdk/`.

**Status:** builds and its tests pass. It has not yet been run on a phone or against
Light's real push server.

## What it does

- **Inbox:** messages newest first, each showing the headline, two lines of detail,
  and the time it arrived. `•` marks unread messages. A message that has choices and
  no answer yet says so.
- **Message:** the full text, its choices as tappable rows, and **Reply** for free
  text. Each reply is shown as *sending*, *sent* or *not sent*, and **Retry** re-queues
  failures.
- **Pairing:** push registration status, plus the three settings the Unraid box needs,
  as a QR code and as text. **New keys** (two taps) rotates both secrets.

Replies are queued, then sent by a `LightWork` job that retries with backoff, so
replying offline is fine. Each reply carries an id the inbox de-duplicates on,
so a resend never doubles up.

## Honest limits

- **No alert while the tool is closed.** The SDK wakes the tool for a push but has no
  way to raise a notification, so a message waits until you open the tool.
- **Push is unverified on real hardware.** Registration is the SDK's own UnifiedPush
  path (`enablePushNotifications = true`). Whether LightOS issues an endpoint to a
  sideloaded tool, and what Light's server expects from the sender, can only be
  checked on the phone.
- **The push handler gets no `Context`** (SDK gap). A push that arrives before any
  screen has opened in the process is written to `/data/user/0/<package>/files`,
  which is the app's normal files dir, reached by path. See `ToolFiles` in `Storage.kt`.

## Build

```bash
export JAVA_HOME=/home/will/.jdks/jbr-21.0.11
./gradlew :tool:testDebugUnitTest :tool:assembleDebug
/home/will/Projects/Android/platform-tools/adb install -r tool/build/outputs/apk/debug/tool-debug.apk
```

System Java 26 breaks Android jlink, so always use JBR 21. `sdk/keys/lightsdk-dev.jks` is
untracked; copy it from a sibling project.

`serverPackage` in `tool/lighttool.toml` is `com.lightos` for the real phone. Use
`com.thelightphone.sdk.emulator` for the emulator.

## Config

In untracked `local.properties`:

```properties
relay.inboxUrl=https://relay-inbox.<tailnet>.ts.net/replies
```

This is the reply inbox's public Funnel address, and it isn't a secret. Neither
secret is baked into the APK: both are generated on the phone the first time
**Pairing** is opened.

## Pairing

Open **Pairing**. It shows:

```
# relay container
PUSH_ENDPOINT=https://…   (the tool's UnifiedPush endpoint on Light's server)
PUSH_KEY=…                (signs every push; unsigned pushes are dropped)
# inbox container
REPLY_TOKEN=…             (bearer for POST /replies)
```

Put these in the two containers' settings on Unraid. To get them off the phone:

- scan the QR code with another phone, then send the text to yourself; or
- with the phone on USB, run `adb logcat -s RelayInbox`. A debug build logs the block
  when Pairing opens.

If `PUSH_ENDPOINT` says *not registered yet*, LightOS hasn't issued an endpoint.
That is the first thing to look into on hardware.

## Push format

`v1.<hex HMAC-SHA256(PUSH_KEY, json)>.<json>`. `PushCodecTest` checks a vector produced
by the relay's own `sign()`, so the two sides agree byte for byte.
