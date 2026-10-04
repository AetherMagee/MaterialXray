---
title: Root or rootless?
description: The two service modes, what they trade off, and how to choose.
---

Material Xray runs the same Xray-core build either way. The two modes differ in how traffic reaches it.

- **Rootless** creates a standard Android VPN and hands its TUN file descriptor to Xray.
- **Rootful** uses `su` to create its own TUN interface and routing tables, so Android never sees a VPN.

## Comparison

| | Rootless | Rootful |
| --- | --- | --- |
| **Detection** | ⚠️ Establishes an Android VPN, which apps can detect through the system's network APIs. | ✅ Routing tables keep the tunnel hidden from apps that bypass it. |
| **Setup** | ✅ Approve Android's VPN permission, like any other VPN app. | ⚠️ Requires superuser access through `su`. KernelSU is preferred. |
| **Android VPN slot** | ⚠️ Occupied, and easily detected by other apps. | ✅ Free. Can coexist with other VPNs like Tailscale. |
| **Per-app control** | ✅ Proxy or bypass per app. | ✅ Proxy or bypass per app, **plus** a specific server per app. |
| **Hotspot and tethering** | ⚠️ Tethered clients are not tunneled. | ✅ Can tunnel tethered clients. |
| **Always-on VPN** | ✅ Supported. | ⚠️ Turning on Android's always-on VPN switches the app to rootless mode. |
| **Auto-connect after reboot** | ✅ Supported. | ✅ Supported. |
| **If Android kills the app** | ⚠️ The proxy stops too. Always-on VPN can restart it. | ✅ The proxy process keeps running on its own. |
| **Stability** | ✅ Android does the routing: standard and battle-tested. | ⚠️ Rigorously tested, but may have rough edges. |

:::tip[TL;DR]
Use **rootful** when hiding the VPN from other apps matters most. Use **rootless** when you don't have root or prefer Android's standard VPN integration.
:::

## What rootful does not do

Rootful does not make you undetectable. Apps can still look at other signals, such as root detection, routing quirks and networking edge cases. It does not hide another VPN running alongside it, and coexisting still depends on compatible routing.

If the app falls back to rootless mode, or always-on VPN forces it there, the connection becomes an ordinary Android VPN again.

## How rootful routing works

- Every per-app proxy group shares one TUN interface. Each group's routing table gives its traffic a distinct source address, and Xray routes on that address.
- Outbound connections are bound to the physical interface to avoid routing loops.
- The service watches Wi-Fi and cellular changes and retargets the connection when the network changes.
