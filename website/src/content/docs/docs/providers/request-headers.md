---
title: Request headers
description: The User-Agent, HWID and device headers Material Xray sends with each refresh.
---

Every subscription request carries identification headers. Which ones depends on the subscription's **User-Agent** mode.

## Headers

| Header | Example | Sent in |
| --- | --- | --- |
| `User-Agent` | `Material Xray/0.9.4 (Android 16; Google Pixel 9)` | All modes |
| `x-hwid` | `a1b2c3d4e5f60789` | All modes, while **Send hardware ID** is on (the default) |
| `x-device-os` | `Android` | Auto, Happ |
| `x-ver-os` | `16` | Auto, Happ |
| `x-device-model` | `Google Pixel 9` | Auto, Happ |

## User-Agent modes

### Auto (default)

```http
User-Agent: Material Xray/0.9.4 (Android 16; Google Pixel 9)
x-hwid: a1b2c3d4e5f60789
x-device-os: Android
x-ver-os: 16
x-device-model: Google Pixel 9
```

The format is `Material Xray/<app version> (Android <OS version>; <manufacturer> <model>)`. Match on the `Material Xray/` prefix.

### Happ

For panels that only serve content to Happ, the client sends a Happ-compatible User-Agent with the same device headers:

```http
User-Agent: Happ/3.23.0
x-hwid: a1b2c3d4e5f60789
x-device-os: Android
x-ver-os: 16
x-device-model: Google Pixel 9
```

### Custom

The user sets the User-Agent, or leaves it blank to keep the Auto value, and adds any headers they like, one `Name: Value` per line. Custom headers override built-in ones with the same name. The `x-device-*` headers are not sent in this mode. `x-hwid` is still added unless the user supplies their own or has turned sending off.

## The hardware ID

`x-hwid` is the device's `ANDROID_ID`. On Android 8.0 and newer, Android scopes it to the app's signing key and the user profile. When it's unavailable, the client generates a random UUID once and keeps it.

The value survives refreshes and app updates. It changes after a factory reset.

:::tip
To insist on a hardware ID, send [`subscription-always-hwid-enable: true`](/docs/providers/response-headers/#subscription-always-hwid-enable). The client will ask users who have turned sending off to turn it back on before they connect.
:::
