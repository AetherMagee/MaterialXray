---
title: FAQ
description: Frequently asked questions.
---

## Does Material Xray provide servers?

No. It's a client. You need a proxy server or a subscription from a provider.

## Is it free and open source?

Yes. The [source is on GitHub](https://github.com/AetherMagee/MaterialXray), and every release ships the corresponding Xray-core source.

## Is it on Google Play?

Not currently. Download the APK from [GitHub Releases](https://github.com/AetherMagee/MaterialXray/releases/latest). The app can update itself.

## Does it work on Android TV?

Yes. The app adapts to TV, tablets and foldables, and can be navigated with a remote.

## Can other apps tell I'm using a VPN?

In rootless mode, yes: it's a standard Android VPN. [Rootful mode](/docs/guide/root-vs-rootless/) avoids the Android VPN entirely, but no setup is undetectable.

## Can I use it alongside Tailscale or another VPN?

In rootful mode, usually yes, as long as the routing setups don't conflict. In rootless mode, Android allows only one VPN at a time.

## Does it collect data?

It sends private crash and core-reliability diagnostics by default, with no sensitive information. You can turn them off in Settings.

## My provider only supports Happ. Will it work?

Usually. Material Xray reads Happ's subscription headers, routing links and per-app lists. If the panel gates content by User-Agent, switch the subscription to the **Happ** User-Agent preset.

## Which languages are supported?

English and Russian.
