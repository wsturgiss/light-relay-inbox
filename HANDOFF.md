# Handoff: Relay Inbox (phone side)

State as of 2026-10-05. Installed on the LP3, paired, and the first real message has
gone end to end by polling. What's left is push delivery (see **Next steps**).

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
- Paired: `PUSH_ENDPOINT`, `PUSH_KEY` and `REPLY_TOKEN` match the phone (checked 2026-10-05).
  `PUSH_ENDPOINT` is optional: without it, messages wait in the relay's outbox and the
  tool fetches them.
- `docker` needs the `docker` group, which a login session only picks up after logging
  in again. Until then, run `up.sh` as `newgrp docker <<< ~/Projects/light-relay/scripts/up.sh`.

**Verified:**
- Serve passes the real tailnet IP, and the relay's device allowlist works.
- The inbox over the real internet via Funnel:
  - 401 without a token, 404 for anything but POST, 202 for a real reply;
  - it logs the real public sender IP.
- Reply → inbox → relay → `relayctl.py replies --ack` works end to end.
- A push signed by the relay passes the tool's check (`PushCodecTest`).
- **On the phone (2026-10-05):**
  - LightOS issues a UnifiedPush endpoint to the sideloaded tool
    (`https://production.lightphonecloud.com/api/webhooks/unified_push/deliver/<uuid>`).
  - `relayctl.py notify` → outbox → `GET /messages` → tool works end to end. Replying
    **Yes** reached `relayctl.py replies` about a second later.
  - The inbox synced the Sept 30 test replies back onto their messages.
  - Light's push server **accepts** the relay's push (`"pushed": true`).

**Not working yet:** a push accepted by Light's server never reaches the tool. Nothing
was logged, even with the tool in the foreground.

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

1. **Push delivery.** Light's server answers 2xx, but `onPushNotification` never runs.
   Look at what Light's server wants from the sender (VAPID? a different body?), and at
   the SDK's `LightPushService` and the `LightPushDistributor` on the LightOS side.
   Polling covers it meanwhile.

   Earlier notes on registration, kept for reference:
   - Check with `adb logcat -s RelayInbox`: it logs `Push endpoint: …`, and a debug
     build prints the whole settings block when Pairing opens.
   - If it stays "waiting for LightOS…", start with the SDK's
     `LightSdkApplication.registerWithLightServer` and the `LightPushDistributor` on the
     LightOS side. Registration goes through `pushEndpointFetcher`.
   - If LightOS never issues an endpoint, the tool still works: it fetches messages
     from the inbox (`GET /messages`, with the reply token) when opened and every 15
     minutes. This was decided with Will on 2026-10-04, along with rebuilding the inbox from the relay.
2. **Pair.** On baconstation:
   - put `PUSH_KEY` in `relay.env`, and `PUSH_ENDPOINT` too if there is one;
   - replace `REPLY_TOKEN` in `inbox.env` with the phone's value;
   - re-run `scripts/up.sh`.
3. **First message.** From baconstation or muse:
   ```bash
   RELAY_URL=https://baconstation.tailda71f7.ts.net ~/Projects/light-relay/agent/relayctl.py \
     notify "Relay test" "If you can read this on your Light Phone, the path works." --choice Yes --choice No
   ```
   - The answer's `"pushed"` says whether Light's push server took it. If it's `false`
     with an endpoint set, the relay logs the HTTP status. Light hasn't published the
     production push details, so check whether it wants VAPID or other headers. The one
     place to change is `push()` in `lightrelay/relay.py`.
   - Either way the message is in the outbox: open the tool and it's fetched. If it
     doesn't show, check logcat for `failed signature check` (the keys don't match) or
     `Can't sync`.
4. **Reply from the phone,** then `relayctl.py replies --ack`.
5. **Rebuilding the inbox.** To check it, reinstall (or clear the app's data), re-pair, put the new `PUSH_KEY` and `REPLY_TOKEN` on the box
   and re-run `up.sh`. The relay re-signs its kept messages under the new key at startup,
   so the whole conversation should come back on the next sync.
6. **Pushes that arrive with the tool closed.** `onPushNotification` gets no Context
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
- **Sync** (`Sync.kt`): `GET /messages` and `GET /replies` on the inbox, with the reply
  token, paged from the phone's own cursor in `sync.json`. Fetched messages go through
  `PushCodec` like pushes. Both sides keep 90 days.
- **No separate History screen.** One was tried and removed on 2026-10-05: with replies
  shown on each message, it only re-sorted the inbox.

## Later

- Move the server to Unraid properly: `scripts/deploy-unraid.sh root@<box>` (needs SSH
  key auth), or an Unraid template with per-container Tailscale. Moving it changes the
  inbox URL, which means rebuilding the tool with a new `relay.inboxUrl`.
- Check whether Light's Tool Library is live (it would replace sideloading).
