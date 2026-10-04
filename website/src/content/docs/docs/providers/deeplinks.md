---
title: Deeplinks
description: Add a subscription with one tap from your website, bot or app.
---

Material Xray handles the `mxray://` scheme. A tapped link opens the app and adds the subscription right away, with default settings.

## Add a subscription

```text
mxray://add/<subscription URL>
```

Append the full subscription URL, scheme included, right after `mxray://add/`:

```text
mxray://add/https://sub.example.com/abc123
```

| Rule | Detail |
| --- | --- |
| Scheme | The embedded URL must start with `https://` or `http://`. Plain HTTP won't refresh unless the user allows insecure updates. |
| Encoding | Don't percent-encode the embedded URL. Its query string can stay as it is. |
| Share links | Single `vless://` (and other) links aren't accepted here. Use a subscription URL. |
| Settings | The subscription is added with the defaults: JSON first, Auto User-Agent, hourly refresh. |

:::caution
`mxray://add/https%3A%2F%2Fsub.example.com%2Fabc123` won't work. The client expects the literal `https://` right after the prefix.
:::

## On a web page

```html
<a href="mxray://add/https://sub.example.com/abc123">Add to Material Xray</a>
```

Some browsers block custom-scheme redirects that the user didn't tap, so use a visible button rather than a redirect.

## In a Telegram bot

Telegram's URL buttons generally reject custom schemes. Link to an HTTPS page of yours that shows the `mxray://` button, or send the deeplink as plain message text.

## Other ways to hand over a subscription

- **QR code**: encode the plain subscription URL. The app's scanner reads it directly.
- **Clipboard**: users can paste the URL from **Add new server or subscription → Paste from clipboard**.
