---
title: Per-app routing
description: Tell the client which apps to proxy or bypass.
---

Three Happ-compatible headers let a subscription assign routes to apps by package name. They're applied when the user's **Routing policy** is *Subscription provider*.

## Headers

| Header | Value |
| --- | --- |
| `per-app-proxy-mode` | **Required.** How listed apps are routed. |
| `per-app-proxy-list` | Package names, separated by commas, semicolons or whitespace. Can repeat. |
| `per-app-proxy-list-invert` | Package names whose route is the opposite of the mode. See [inverted lists](#inverted-lists). |

Without a valid `per-app-proxy-mode`, the list is ignored.

### Modes

| Value | Listed apps |
| --- | --- |
| `bypass`, `direct`, `off` | Skip the proxy and use the device network. |
| `proxy`, `on`, `default`, `default_selected` | Use the server selected on the home screen. |
| `default_outbound`, `inherit` | Use the default outbound from Settings. Rootful only. |

Values are case-insensitive, and `-` works in place of `_`.

## Examples

Bypass banking apps:

```http
per-app-proxy-mode: bypass
per-app-proxy-list: com.bank.app, ru.sberbankmobile, com.idamob.tinkoff.android
```

Proxy only a few apps:

```http
per-app-proxy-mode: proxy
per-app-proxy-list: org.telegram.messenger com.google.android.youtube
```

## Inverted lists

`per-app-proxy-list-invert` means "every app *except* these". Every other installed app gets the mode, and the listed apps get the opposite:

```http
per-app-proxy-mode: proxy
per-app-proxy-list-invert: com.bank.app, ru.sberbankmobile
```

Here every app goes through the proxy except the two banking apps, which bypass it.

:::note
Inverted lists are written out against the apps installed at refresh time. Apps installed later keep the default route until the next refresh.
:::

## How it combines with user choices

- The provider's list replaces earlier provider-supplied assignments on every refresh.
- Manual assignments survive for apps the provider doesn't mention.
- If the user edits an app route, the client offers to switch to manual mode, which stops applying the header.
- Invalid package names are dropped.
