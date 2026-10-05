---
title: Settings
description: Connectivity, DNS, IPv6, appearance, backups and more.
---

A tour of the Settings tab. Some options only show up after you turn on **Show advanced options**.

## Connectivity and routing

| Setting | What it does |
| --- | --- |
| **Bypass LAN** | Routes private IPs and LAN domains directly. |
| **Routing policy** | *Subscription provider* (default) applies routing and per-app rules from the selected subscription. *User* keeps your own. |
| **Default outbound** | Where rootful *Default outbound* app routes go. |
| **IPv6 connections** | **Off**: IPv4 only. **Auto**: uses IPv6 once a quick check through the server passes. **On**: always prefers IPv6 when the network has it. |
| **Tunnel tethered clients** | Rootful only. Routes Wi-Fi hotspot and USB tethering traffic through Xray. |
| **Route app traffic through Xray** | Sends Material Xray's own data updates through the active connection. Latency checks always bypass it. |
| **Connection health watchdog** | Watches the local Xray API, the tunnel and root routing for failures, without sending external probes. |
| **Always-on VPN** | Shows whether Android's always-on VPN is enabled for Material Xray. Tap the row below **Auto-connect on boot** to open Android's VPN settings and manage it there. |

## DNS

Xray answers every port-53 query itself, so names routed through the proxy are never looked up on the network you're on.

- **Proxied domains**: the resolver for every name routed through the proxy.
- **Direct domains**: the resolver for LAN hosts and direct rules. These queries leave over your normal connection.
- **Encrypt queries**: use encrypted DNS.
- **Prefer profile DNS**: when a JSON profile defines its own DNS, use it.

Resolvers are comma-separated and tried in order. Each one is an IP address or a DoH URL like `https://dns.quad9.net/dns-query`.

## Core

Advanced options cover the Xray log level, buffer size, TUN MTU, a RAM threshold that restarts the core, the latency-check URL, and geodata URLs and update interval.

## Appearance and notifications

- **App language**: English or Russian, or follow the system.
- **App icon**: the default icon or a Material-style alternative.
- **Floating connect button**: a small corner button instead of the large power button.
- **Notification fields**: pick what the connection notification shows and how often it updates.

## Quick Settings tile

Add the Material Xray tile to toggle the connection from the notification shade. It shows whether you're connected, connecting, or have no server selected. Long-press it to open the app.

## Data

- **Export backup / Import backup**: a backup of subscriptions, servers, app routes, settings and a hand-edited runtime config. Downloaded geodata and Xray cores are not included; they download again. Importing replaces the current configuration.
- **Reset app**: deletes all app data, the same as reinstalling the app, then closes it.

## Diagnostics

Material Xray sends private crash and core-reliability data by default, with no sensitive information. Turn it off under **Diagnostics and usage statistics**.
