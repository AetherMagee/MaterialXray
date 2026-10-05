---
title: Adding servers
description: Paste a link, scan a QR code, add manually, or open a deeplink.
---

Tap **Add new server or subscription** on the home screen. You can:

- **Paste from clipboard**: a subscription URL or a single share link.
- **Scan QR code**: the camera is used only for scanning, and you can always paste instead.
- **Add manually**: type the link and set options yourself.

Material Xray also opens [`mxray://add/` deeplinks](/docs/providers/deeplinks/), so a button on your provider's site can add the subscription in one tap.

## What you can add

| Input | Result |
| --- | --- |
| An `https://` subscription URL | A subscription that refreshes on a schedule. |
| A single `vless://`, `vmess://`, `trojan://`, `ss://` or `hysteria2://` link | One server. |

Subscriptions can carry more: HTTP, SOCKS and WireGuard lines, plus raw Xray JSON configurations. See [subscription format](/docs/providers/subscription-format/) and [share links](/docs/reference/share-links/).

## Subscription options

Each subscription has its own settings, set when you add it manually or later by editing the subscription. Expand **Advanced** to show the fetch type, User-Agent, custom headers and insecure-update options; the dialog resizes smoothly and keeps its fields scrollable.

| Option | What it does |
| --- | --- |
| **Name** | Leave empty to use the provider's [`profile-title`](/docs/providers/response-headers/#profile-title). |
| **Auto update** | Every 1, 3, 6, 24 or 72 hours, or manual only. New subscriptions refresh every hour. |
| **JSON first** | Try `<url>/json` first and fall back to the saved URL. On by default. See [subscription format](/docs/providers/subscription-format/#json-first). |
| **Allow insecure updates** | Accept plain HTTP and untrusted or mismatched certificates. Off by default. |
| **User-Agent** | **Auto** identifies as Material Xray, **Happ** uses a Happ-compatible User-Agent, **Custom** lets you set the User-Agent and extra headers. See [request headers](/docs/providers/request-headers/). |

:::tip
To keep a server from being replaced on the next refresh, mark it **Guarded against updates**.
:::

## Hardware ID

Some providers require a device identifier (HWID). Material Xray sends one by default. You can turn it off under **Settings → Send hardware ID (HWID)**. If a subscription [requires HWID](/docs/providers/response-headers/#subscription-always-hwid-enable) while sending is off, the app asks before you connect to it.
