---
title: Per-app proxy
description: Choose how each app's traffic is routed.
---

Open **Routing → Apps** to choose a route for each app. Search by name, show system apps, and include work-profile apps when you have one.

## Routes

| Route | Effect | Mode |
| --- | --- | --- |
| **Default selected server** | Uses the server selected on the home screen. This is the default. | Both |
| **Not proxied** | Uses the device network directly. | Both |
| **Default outbound** | Uses the default outbound chosen in Settings. | Rootful |
| **Specific server** | Sends the app through a server of your choice. Routing rules still apply. | Rootful |
| **Always proxied** | With a specific server: sends *all* of the app's traffic through it, ignoring routing rules. | Rootful |

The route picker fades into the dialog background at both ends, matching the main scrolling lists.

In rootless mode, rootful-only routes are skipped, and the log says how many were.

## Bulk actions

The menu has **Bypass all apps**, which sets every app to *Not proxied*, and **Reset to defaults**.

## Provider-managed app routing

A subscription can [supply a per-app list](/docs/providers/per-app-routing/). While **Settings → Routing policy** is set to *Subscription provider* (the default), the selected subscription's list is applied automatically and the Apps tab shows who manages it.

If you edit a route, the app offers to **switch to manual mode**, which stops automatic updates. Your manual changes to apps the provider doesn't list survive refreshes.
