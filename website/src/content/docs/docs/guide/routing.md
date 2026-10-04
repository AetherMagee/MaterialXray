---
title: Routing rules
description: Send traffic through the proxy, around it, or nowhere, by domain, IP, port or protocol.
---

Open **Routing → Rules** to manage the rules Xray applies to each connection. Rules are checked top to bottom, and the first match wins.

## Built-in rules

A fresh install includes:

- **Bypass Russian domains and IPs** (on): `.ru`, `.su`, `.рф`, `geosite:category-ru` and `geoip:ru` go to `direct`.
- **Block ads** (off): `geosite:category-ads-all` goes to `block`.

Toggle them, edit them, or use **Reset to default** from the menu.

## Writing a rule

| Field | Example | Notes |
| --- | --- | --- |
| **Outbound tag** | `proxy`, `direct`, `block` | Where matching traffic goes. |
| **Domains** | `domain:example.com, geosite:google` | Comma-separated, in Xray's domain syntax. |
| **IPs** | `geoip:ru, 1.2.3.0/24` | Comma-separated CIDRs or geoip lists. |
| **Port** | `443` or `1000-2000` | A single port or a range. |
| **Protocols** | `http`, `tls`, `quic`, `bittorrent` | Leave empty to match everything. |

**Match mode** decides how filled fields combine:

- **All conditions (AND)** keeps everything in one Xray rule. Traffic must match every filled field.
- **Any condition group (OR)** turns each filled field into its own Xray rule. Traffic can match any one of them.

:::caution
A rule with no conditions matches **all** traffic, so no rule below it is ever reached. The editor warns you before you save one.
:::

## Geodata

The APK bundles current `geoip.dat` and `geosite.dat` from [v2fly](https://github.com/v2fly), so a new install can connect without downloading anything. Settings lets you update them or point to custom download URLs.

## Provider-managed routing

A subscription can supply [its own routing](/docs/providers/routing-header/), including custom geodata URLs. With **Routing policy** set to *Subscription provider*, those rules are applied and updated automatically. Rules are labeled **Subscription-wide**, **Profile-specific** or **Custom**. Editing a provider rule offers to switch to manual mode.

If the provider's geodata can't be downloaded, the app runs in **compatibility mode** and skips the rules that need it. Tap the banner to retry.
