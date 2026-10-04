---
title: Troubleshooting
description: Logs, common problems and how to report an issue.
---

## Read the logs first

The **Logs** tab shows live output from both the app and Xray-core. Raise the **Xray log level** in Settings (advanced) for more detail. With advanced options on, you can also open the generated configuration in the viewer.

## Common problems

**The subscription won't refresh.**
Check whether the error mentions an insecure connection. By default Material Xray refuses plain HTTP, bad certificates and HTTPS-to-HTTP redirects. If you trust the source, turn on **Allow insecure updates** for that subscription.

**The provider says my device isn't recognized.**
The subscription may need a hardware ID or a specific User-Agent. Make sure **Send hardware ID** is on, or try the **Happ** User-Agent preset. See [request headers](/docs/providers/request-headers/).

**Routing rules were skipped.**
The provider's geodata couldn't be downloaded, so the app is in compatibility mode. Tap the banner on the Routing tab to retry.

**Rootful mode falls back to rootless.**
Android's always-on VPN forces rootless mode. Turn it off for Material Xray if you want rootful.

## Reporting an issue

[Open an issue](https://github.com/AetherMagee/MaterialXray/issues) with:

- your Android version and device model,
- the service mode (rootless or rootful, and which root solution),
- steps to reproduce.

:::danger
Remove credentials, subscription URLs and other private information from any logs or configurations you share.
:::
