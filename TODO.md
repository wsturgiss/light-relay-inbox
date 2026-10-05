# TODO

- [x] Inbox / Message / Pairing screens, reply queue with retry
- [x] Signed pushes, checked against a vector from light-relay
- [x] Fetch messages from the inbox when push isn't available (open + every 15 min)
- [x] History: one-thread conversation view, rebuilt from the relay after a reinstall
- [ ] See HANDOFF.md for the order. Install on the LP3; confirm LightOS issues a push endpoint to a sideloaded tool
- [ ] End-to-end: agent → relay → Light push server → phone, and reply → inbox → agent
- [ ] Check whether Light's Tool Library is live (would replace sideloading)
- [ ] If Light adds closed-tool notifications, raise one from `onPushNotification`
