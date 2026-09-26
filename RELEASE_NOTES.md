# Hermes-Relay Android v1.18.0

**Release Date:** September 26, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.18.0-sideload-release.apk` and tap it for the full feature set, or install from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

A saved connection can now require the tailnet. With **Always connect via Tailscale** on, Hermes Relay refuses every other route for that connection instead of quietly falling back to LAN or public, and a blocked connection reports why instead of downgrading.

## Added

- Per-connection **Always connect via Tailscale**, directly under the Gateways row in Settings. When it is on, only tailnet routes are eligible for that connection; LAN and public candidates are refused.

## Changed

- The Routes tab carries a read-only Tailscale status row, and the connection's subpage holds the only switch.
- A blocked state names the reason and the steps to fix it instead of falling back silently.

## Install / Verify

- App version: **1.18.0** (versionCode **58**).
- The mode applies to Hermes Relay's own connections only. Which apps the device sends through Tailscale stays a Tailscale app setting, which the app explains but does not control.
- Connections that do not opt in keep today's LAN and public route order and fallback.
- Verification for this release: both Android debug flavors compile in CI on the exact commit, the Android repository gates pass, and the feature's unit tests run in the focused CI lane. Physical-device testing of an excluded-app split tunnel was not performed.

---

# Hermes-Relay Android v1.17.0

**Release Date:** September 13, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.17.0-sideload-release.apk` and tap it for the full feature set, or install from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

Google Play gains optional voice controls over other apps. This release also makes Clarify batches, profile identity, and chat context easier to follow while preserving confirmed answers and saved conversations.

## Added

- Start Voice Overlay from Voice Focus after granting microphone, notification, and display-over-other-apps access. Permission grants require a separate Start action. Stop voice from the overlay or persistent notification; screen lock, task removal, and permission loss end the session.

## Changed

- Standalone response cards use one surface, assistant bubbles are subtler, and timestamps share a row with delivery status.

## Fixed

- Answer upstream Clarify batches one question at a time, with independent choices, custom answers, and confirmed progress across reconnects. (#474)
- Context previews show that Gateway chats cannot send phone status or general turn context. Automatic phone-status sharing remains supported for API-only chats. (#556)
- Profiles display their Hermes names and group the resolved server default under its agent identity, preserving explicit selection and saved conversations.

## Install / Verify

- App version: **1.17.0** (versionCode **57**).
- Standard Chat, sessions, profiles, Manage, voice, and ordinary media use current upstream Hermes. Speech-to-text still requires a configured provider on the host.
- Hermes-Relay Plugin **1.11.3** is the optional release for Hermes-Relay tools and current Dashboard WebSocket compatibility.
- Explicit Direct API/API-only connections remain supported and are not used as silent failover for Dashboard-owned chats.
- Voice Overlay is available in Google Play and sideload builds. Device Control remains sideload-only.
- Gateway phone-status delivery and automatic Android identification remain unavailable pending upstream support.
- Physical Android 14-16 and OEM voice-overlay testing was not performed for this release. Code, rendered UI, existing emulator evidence, CI, and signed-package preflight provide the recorded verification.
