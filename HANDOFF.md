# Handoff: Relay Inbox (phone side)

State as of 2026-09-30. The server side is done and running. What's left is the
phone: install, pair, and the first real message end to end.

Spec: the *Unraid → Light Phone Relay — Blueprint* Google Doc. Server repo:
[`wsturgiss/light-relay`](https://github.com/wsturgiss/light-relay).

## What's running

On **baconstation**, in Docker, restarting on its own:

| | Address | Reachable from |
|---|---|---|
| relay (`light-relay`) | `https://baconstation.tailda71f7.ts.net` | tailnet only (Serve, 443) |
| inbox (`light-relay-inbox`) | `https://baconstation.tailda71f7.ts.net:8443/replies` | the internet (Funnel, 8443) |

- Settings: `~/.local/share/light-relay/relay.env` and `inbox.env` on baconstation.
  After editing either, re-run `~/Projects/light-relay/scripts/up.sh` there.
- `ALLOWED_PEERS` = muse (`100.68.208.23`) and baconstation (`100.67.123.119`).
- `REPLY_TOKEN` in `inbox.env` is a **temporary** value until the phone is paired.
- The relay is unpaired, so `/notify` answers 503 until `PUSH_ENDPOINT` and `PUSH_KEY` are set.

**Verified:**
- Serve passes the real tailnet IP, and the relay's device allowlist works.
- The inbox over the real internet via Funnel:
  - 401 without a token, 404 for anything but POST, 202 for a real reply;
  - it logs the real public sender IP.
- Reply → inbox → relay → `relayctl.py replies --ack` works end to end.
- A push signed by the relay passes the tool's check (`PushCodecTest`).

**Not verified:** anything on the phone, and anything involving Light's push server.

## Setting up this machine

```bash
git clone git@github.com:wsturgiss/light-relay-inbox.git && cd light-relay-inbox
```

Two untracked files are needed:

- `local.properties`:
  ```properties
  sdk.dir=/path/to/Android/sdk
  relay.inboxUrl=https://baconstation.tailda71f7.ts.net:8443/replies
  ```
- `sdk/keys/lightsdk-dev.jks`: the Light SDK dev signing key (password `android`).
  Copy it from any sibling tool checkout, such as `light-agent-inbox` or `light-training`.

Build with JBR 21 (system Java 26 breaks jlink):

```bash
./gradlew :tool:testDebugUnitTest :tool:assembleDebug
adb install -r tool/build/outputs/apk/debug/tool-debug.apk
```

`tool/lighttool.toml` has `serverPackage = "com.lightos"` for the real LP3.

## Next steps, in order

1. **Install and open Pairing.** The first real unknown is whether LightOS issues a push
   endpoint to a sideloaded tool.
   - Check with `adb logcat -s RelayInbox`: it logs `Push endpoint: …`, and a debug
     build prints the whole settings block when Pairing opens.
   - If it stays "waiting for LightOS…", start with the SDK's
     `LightSdkApplication.registerWithLightServer` and the `LightPushDistributor` on the
     LightOS side. Registration goes through `pushEndpointFetcher`.
2. **Pair.** On baconstation:
   - put `PUSH_ENDPOINT` and `PUSH_KEY` in `relay.env`;
   - replace `REPLY_TOKEN` in `inbox.env` with the phone's value;
   - re-run `scripts/up.sh`.
3. **First message.** From baconstation or muse:
   ```bash
   RELAY_URL=https://baconstation.tailda71f7.ts.net ~/Projects/light-relay/agent/relayctl.py \
     notify "Relay test" "If you can read this on your Light Phone, the path works." --choice Yes --choice No
   ```
   - A **502** means Light's push server refused it. The relay logs the HTTP status.
     Light hasn't published the production push details, so check whether it wants
     VAPID or other headers. The one place to change is `notify()` in `lightrelay/relay.py`.
   - A **202** where nothing shows up means check logcat for
     `Push failed signature check` (the keys don't match) or `Push arrived before pairing`.
4. **Reply from the phone,** then `relayctl.py replies --ack`.
5. **Pushes that arrive with the tool closed.** `onPushNotification` gets no Context
   (SDK gap), so it writes to `/data/user/0/com.thelightphone.relayinbox/files` by
   path. Confirm that a message pushed while the tool is closed is there when you
   open it.

## Design notes, in brief

- **Push body:** `v1.<hex HMAC-SHA256(PUSH_KEY, json)>.<json>`. The tool drops anything
  that doesn't verify, so the endpoint URL alone can't put text on the phone.
- **Both secrets are generated on the phone** (`Pairing.kt`), which differs from the
  blueprint, where the box generated the push secret. Nothing secret is baked into the APK.
- **Replies** get a phone-generated id. The inbox stores each id once, and the
  `send-replies` LightJob retries with backoff, so offline replies go out later.
- **The screens** are Inbox (the `@InitialScreen`), Message, and Pairing.
- **The SDK can't raise a notification while the tool is closed.** Messages wait
  in the inbox until it's opened.

## Later

- Move the server to Unraid properly: `scripts/deploy-unraid.sh root@<box>` (needs SSH
  key auth), or an Unraid template with per-container Tailscale. Moving it changes the
  inbox URL, which means rebuilding the tool with a new `relay.inboxUrl`.
- Check whether Light's Tool Library is live (it would replace sideloading).
