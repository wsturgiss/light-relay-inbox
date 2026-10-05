# light-relay-inbox

**Relay Inbox**: the Light Phone III end of the agent relay. Messages from the
agent are fetched from the reply inbox on the Unraid box (and also arrive as pushes
through Light's push server, if LightOS gives the tool an endpoint), and wait here
as an inbox. Replies go back to the same reply inbox. The server side is
[`light-relay`](../light-relay).

Sibling of `light-agent-inbox` (tool + vendored `sdk/`). Do not hand-edit `sdk/`.

**Status:** running on the LP3. Messages arrive by polling, and replies get back to the
agent. Light's push server accepts pushes, but they don't reach the tool yet; see
`HANDOFF.md`.

## What it does

- **Inbox:** one row per conversation, most recently active first. Each row shows the
  conversation's title (its first headline), where it has got to, the last activity
  and how many messages it has. `•` marks unread ones, and a conversation with a
  choice still to make says so. **New** starts a conversation: type it, and the first
  line becomes the title.
- **Conversation:** laid out like LightOS Messages, titled with the conversation and
  opening on the latest: the agent's messages on the left and yours on the right, each
  under its date, in the order they happened. A message still waiting on a choice shows
  its choices. The compose button replies to the latest message. A reply says *sending* or *not sent* until it reaches the relay,
  and **Retry** re-queues failures.
- **Pairing** (the **Pair** button, then the settings icon once paired): push
  registration status, plus the settings the Unraid box needs, as a QR code and as
  text. **New keys** (two taps) rotates both secrets.

Replies are queued, then sent by a `LightWork` job that retries with backoff, so
replying offline is fine. Each reply carries an id the inbox de-duplicates on,
so a resend never doubles up.

A `sync` job fetches new messages (`GET /messages`) and the replies the inbox holds
(`GET /replies`) when the tool opens and every 15 minutes after. Fetched messages
carry the same signature as a push and are checked the same way, and a message that
arrives both ways is kept once. The replies let a reinstalled tool rebuild its inbox,
and confirm a reply as *sent* if the phone never heard back. The relay and the tool
both keep 90 days.

## Honest limits

- **No alert while the tool is closed.** The SDK wakes the tool for a push or a sync
  but has no way to raise a notification, so a message waits until you open the tool.
- **Without push, a message can take up to 15 minutes** to reach the phone
  (WorkManager's floor), or arrives as soon as you open the tool.
- **A rebuilt inbox comes back only once the relay has the new keys.** A reinstall or **New
  keys** makes a fresh `PUSH_KEY`; the relay re-signs what it keeps when it restarts
  with it, and until then fetched messages fail their check and are skipped.
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
PUSH_ENDPOINT=https://…   (the tool's UnifiedPush endpoint on Light's server, if any)
PUSH_KEY=…                (signs every message; unsigned ones are dropped)
# inbox container
REPLY_TOKEN=…             (bearer for the inbox: post replies, fetch messages and past replies)
```

Put these in the two containers' settings on Unraid. To get them off the phone:

- scan the QR code with another phone, then send the text to yourself; or
- with the phone on USB, run `adb logcat -s RelayInbox`. A debug build logs the block
  when Pairing opens.

If there's no endpoint yet, the block shows `# PUSH_ENDPOINT=` commented out. Leave it
unset on the relay: messages are fetched instead, and push can be added later.

## Push format

`v1.<hex HMAC-SHA256(PUSH_KEY, json)>.<json>`, the same whether it's pushed or fetched.
`PushCodecTest` checks a vector produced by the relay's own `sign()`, and `SyncTest` a
`GET /messages` response from its inbox, so the two sides agree byte for byte.
