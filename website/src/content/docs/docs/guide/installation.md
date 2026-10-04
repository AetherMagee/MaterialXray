---
title: Installation
description: Requirements, downloading the APK and verifying releases.
---

## Requirements

- Android 7.0 (API 24) or newer.
- An arm64-v8a, armeabi-v7a or x86_64 device. The APK is universal, so one download covers all three.
- A proxy server or subscription of your own.
- Optional: root through `su` for [rootful mode](/docs/guide/root-vs-rootless/). KernelSU is preferred.

Material Xray runs on phones, tablets, foldables and Android TV.

## Download and install

1. Download the APK from the [latest release](https://github.com/AetherMagee/MaterialXray/releases/latest).
2. Open it. Android may ask you to allow installs from your browser or file manager.
3. Launch Material Xray and [add a server or subscription](/docs/guide/adding-servers/).

## Updates

The app can check GitHub Releases for new versions on its own. Turn this on or off, and set how often it checks, under **Settings → Automatically check for updates**. When an update is available, a banner on the home screen downloads it, verifies it and opens the installer. With root, the update installs silently.

## Verifying a release

Releases from `v0.5.0` onwards carry build-provenance attestations. With the GitHub CLI:

```sh
gh attestation verify MaterialXray-0.9.4.apk --repo AetherMagee/MaterialXray
```

Each release also ships an archive of the corresponding Xray-core source.
