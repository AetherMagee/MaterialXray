---
title: Subscription format
description: Body formats, the /json endpoint, redirects and URL migration.
---

## Body formats

Material Xray detects the format from `Content-Type` first, then falls back to best-effort detection.

| `Content-Type` | Parsed as |
| --- | --- |
| `application/json` or `*/*+json` | Xray JSON. Falls back to a share-link list if no configs come out. |
| `text/*` | A share-link list, plain or base64-encoded. |
| Anything else, or missing | JSON, then a plain list, then base64-decoded JSON or list. |

An empty body is treated as an error.

### Share-link list

One link per line, either as plain text or base64-encoded as a whole. When both readings produce servers, the one with more servers wins.

```text
vless://uuid@de.example.com:443?security=reality&sni=www.example.com&pbk=…#Frankfurt
hysteria2://password@nl.example.com:443?sni=nl.example.com#Amsterdam
trojan://password@fi.example.com:443?sni=fi.example.com#Helsinki
```

Subscription lines can use any [supported scheme](/docs/reference/share-links/), including `http://`, `socks://` and `wireguard://`.

### Xray JSON

A full Xray configuration object, or an array of them. Each one becomes a server that runs as-is, with the TUN inbound injected by the client.

```json
[
  {
    "remarks": "Frankfurt",
    "outbounds": [
      { "tag": "proxy", "protocol": "vless", "settings": { … }, "streamSettings": { … } },
      { "tag": "direct", "protocol": "freedom" }
    ],
    "routing": { "rules": [ … ] }
  }
]
```

- The server name comes from `remarks`, `remark` or `name`.
- The outbound tagged `proxy` drives the summary (protocol, address, transport) shown in the list. Without one, the client uses the first non-special outbound.
- Routing inside a profile shows up as **Profile-specific** rules on the Routing tab. DNS inside a profile is used when the user turns on **Prefer profile DNS**.

## JSON first

With **JSON first** on, the client requests `<url>/json` before the URL itself, a convention among panels that serve Xray JSON on a sibling path. If that request fails or returns no configs, the client fetches the original URL. A URL that already ends in `/json` is fetched once.

## Redirects

A `301` or `308` redirect permanently updates the stored subscription URL. `302` and `307` are followed without saving the new location.

## Moving a subscription

When your domain gets blocked or you migrate panels, tell the client where to go:

| Header | Effect |
| --- | --- |
| [`new-url`](/docs/providers/response-headers/#new-url) | Replaces the whole stored URL. Must be HTTPS. |
| [`new-domain`](/docs/providers/response-headers/#new-domain) | Swaps only the host and keeps the path and query. |
| [`fallback-url`](/docs/providers/response-headers/#fallback-url) | A backup endpoint, tried when the primary request fails (network error, error status or empty body). The stored URL stays the same. |

`new-url` wins when both are present. Values equal to the current URL are ignored.
