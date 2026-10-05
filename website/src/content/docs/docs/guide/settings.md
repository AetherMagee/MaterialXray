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
| **TUN interface name** | Names the rootful TUN interface. With advanced options enabled, shown below **Tunnel tethered clients** when **TUN interface** is selected; **Transparent proxy** shows **When another VPN is active** in the same place. |
| **Route app traffic through Xray** | Sends Material Xray's own data updates through the active connection. Latency checks always bypass it. |
| **Connection health watchdog** | Watches the local Xray API, the tunnel and root routing for failures, without sending external probes. |
| **Always-on VPN** | Shows whether Android's always-on VPN is enabled for Material Xray. Tap the row below **Auto-connect on boot** to open Android's VPN settings and manage it there. The row is disabled while root service is enabled. |

If root access is unavailable, **Use root service** shows a **Retry** button. Granting access and retrying restores an off switch; enabling root mode remains your choice. Opening Settings reuses the known root-access result without a new permission check. Root access is checked when you tap Retry, enable root mode, or connect; a running root connection also monitors it. Losing access switches the app back to rootless mode. Android's VPN permission is required to connect in rootless mode.

## DNS

Xray answers every port-53 query itself, so names routed through the proxy are never looked up on the network you're on.

- **Proxied domains**: the resolver for every name routed through the proxy.
- **Direct domains**: the resolver for LAN hosts and direct rules. These queries leave over your normal connection.
- **Encrypt queries**: use encrypted DNS.
- **Prefer profile DNS**: when a JSON profile defines its own DNS, use it.

Resolvers are comma-separated and tried in order. Each one is an IP address or a DoH URL like `https://dns.quad9.net/dns-query`.

## Core

**Choose Xray-core version** opens the core manager. **Check for new Xray-core versions automatically** has a separate switch and interval picker: tap the text to choose **3 days**, **1 week** (default), **2 weeks**, or **1 month** (30 days). Changing this interval does not affect app update checks. When checks are enabled, choose whether to be notified of a new core or install it automatically. The interval is included in backups. Loaded version pages are cached across app restarts; reopening the picker reuses them. Tap Refresh to fetch the latest list. A failed refresh keeps the cached list available, while automatic update checks always request current releases.

Advanced options cover the Xray log level, buffer size, TUN MTU, a RAM threshold that restarts the core, the latency-check URL, and geodata URLs and update interval.

## Appearance and notifications

The navigation bar and rail stay in place while tab content moves within its viewport, so the selected highlight animates continuously. The active tab label smoothly changes to medium weight. Detail screens open over the navigation controls; on wide windows, their sheet dims the controls beneath it.

- **App language**: English or Russian, or follow the system.
- **App icon**: the default icon or a Material-style alternative.
- **Floating connect button**: a small corner button instead of the large power button.
- **Notification fields**: pick what the connection notification shows and how often it updates.
  In rootful mode, **Pinned interface** can be selected alongside metrics such as ping and traffic speed, and reordered with them. It is hidden in rootless mode. With no fields selected, the notification still shows the connection status and interface as before.

## Quick Settings tile

Add the Material Xray tile to toggle the connection from the notification shade. It shows whether you're connected, connecting, or have no server selected. Long-press it to open the app.

## Data

- **Export / Import**: a backup of subscriptions, servers, app routes, settings and a hand-edited runtime config. Downloaded geodata and Xray cores are not included; they download again. Importing replaces the current configuration.
- **Clear geodata**: available with advanced options enabled. Deletes downloaded geoip and geosite files. It shares a row with Export and Import when there is enough room and wraps below them on narrower screens.
- **Reset app**: deletes all app data, the same as reinstalling the app, then closes it.

## Diagnostics

Material Xray sends private crash and core-reliability data by default, with no sensitive information. Turn it off under **Diagnostics and usage statistics**.
