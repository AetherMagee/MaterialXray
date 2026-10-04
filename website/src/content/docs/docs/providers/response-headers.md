---
title: Response headers
description: Every subscription header Material Xray reads, with formats and examples.
---

Material Xray reads the following headers from a subscription response. Names are case-insensitive. A blank value, or the literal `null`, counts as absent.

## Quick reference

| Header | Purpose |
| --- | --- |
| [`profile-title`](#profile-title) | Subscription name |
| [`subscription-userinfo`](#subscription-userinfo) | Traffic used, quota and expiry |
| [`announce`](#announce) | Message shown under the subscription |
| [`support-url`](#support-url) | Support link in the subscription menu |
| [`profile-web-page-url`](#profile-web-page-url) | Provider's web page |
| [`profile-update-interval`](#profile-update-interval) | Suggested refresh interval, in hours |
| [`new-url`](#new-url) | Permanently move the subscription |
| [`new-domain`](#new-domain) | Permanently move to a new host |
| [`fallback-url`](#fallback-url) | Backup endpoint |
| [`subscription-always-hwid-enable`](#subscription-always-hwid-enable) | Require a hardware ID |
| [`routing`](/docs/providers/routing-header/) | Routing rules |
| [`routing-enable`](/docs/providers/routing-header/#routing-enable) | Turn routing import off |
| [`per-app-proxy-mode`](/docs/providers/per-app-routing/) | Per-app routing mode |
| [`per-app-proxy-list`](/docs/providers/per-app-routing/) | Apps to route |
| [`per-app-proxy-list-invert`](/docs/providers/per-app-routing/#inverted-lists) | Apps excluded from the mode |
| `content-type` | Selects the [body parser](/docs/providers/subscription-format/#body-formats) |
| `content-disposition` | Stored with the subscription, not displayed |

## Text encoding

`profile-title` and `announce` may carry non-ASCII text. Prefix a value with `base64:` to send UTF-8 safely:

```http
profile-title: base64:0JzQvtC5INCf0YDQvtCy0LDQudC00LXRgA==
```

Unprefixed values are used as-is.

## Metadata

### profile-title

The subscription's display name. It's used when the user leaves the name field empty while adding the subscription.

```http
profile-title: My Provider
```

### subscription-userinfo

Traffic and expiry, as `key=value` pairs separated by `;`. Every key is optional.

```http
subscription-userinfo: upload=0; download=73443041280; total=214748364800; expire=1798761600
```

| Key | Unit | Display |
| --- | --- | --- |
| `download` | bytes | Shown as traffic used. |
| `total` | bytes | The quota. Omit it or send `0` for unlimited. |
| `expire` | Unix seconds | Expiry date and days left. Omit it or send `0` for no expiry. |
| `upload` | bytes | Stored, but not counted toward usage. |

:::caution
Usage is taken from `download` alone. If your panel counts upload against the quota, report the combined figure in `download`.
:::

### announce

A message shown under the subscription on the home screen. Users can hide it.

```http
announce: base64:TWFpbnRlbmFuY2Ugb24gU3VuZGF5LCAwMzowMCBVVEM=
```

### support-url

Adds a **Support** item to the subscription's menu, which opens this URL.

```http
support-url: https://t.me/myprovider_support
```

### profile-web-page-url

The provider's web page. Stored with the subscription.

### profile-update-interval

A suggested refresh interval in whole hours. It's stored with the subscription. The refresh schedule itself is the user's choice in the subscription settings, and defaults to every hour.

```http
profile-update-interval: 12
```

## Moving and backup endpoints

### new-url

Permanently replaces the stored subscription URL. Must be a valid HTTPS URL that differs from the current one.

```http
new-url: https://sub.new-domain.example/abc123
```

### new-domain

Permanently swaps the host of the stored URL and keeps the scheme, path and query. Ignored when `new-url` is present.

```http
new-domain: sub.new-domain.example
```

### fallback-url

A backup endpoint. When a refresh of the primary URL fails, the client tries this one. The stored URL doesn't change, and the fallback policy (this header and the HWID requirement) is kept even if the mirror doesn't repeat it.

```http
fallback-url: https://mirror.example.net/abc123
```

## Access policy

### subscription-always-hwid-enable

Send `1`, `true`, `yes` or `on` to require a hardware ID. When the user has turned off **Send hardware ID**, the client asks them to turn it back on before connecting to a server from this subscription.

```http
subscription-always-hwid-enable: true
```

## Body comments

Panels that can't set response headers can put the same headers in the body as comment lines:

```text
#profile-title: My Provider
#subscription-userinfo: upload=0; download=73443041280; total=214748364800
#announce: Maintenance on Sunday, 03:00 UTC
vless://…#Frankfurt
trojan://…#Helsinki
```

- Only the header names on this page are recognized. Other comments are ignored.
- Comments work in plain bodies and inside base64-encoded bodies.
- When a name appears both as a real header and as a comment, the real header wins. List headers like `per-app-proxy-list` merge both sources.
