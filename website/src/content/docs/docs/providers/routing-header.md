---
title: Routing header
description: Push routing rules to clients as Xray JSON or Happ routing links.
---

The `routing` response header delivers routing rules with the subscription. They're applied when the user's **Routing policy** is *Subscription provider*, which is the default. They appear on the Routing tab as **Subscription-wide** rules, and stay up to date on each refresh until the user switches to manual mode.

## Formats

The header value can take any of these forms:

| Form | Example |
| --- | --- |
| Happ routing link | `happ://routing/onadd/eyJOYW1lIjoi…` or `happ://routing/add/…` |
| Raw JSON | `{"domainStrategy":"IPIfNonMatch","rules":[…]}` |
| Prefixed base64 JSON | `base64:eyJkb21haW5TdHJhdGVneSI6…` |
| Bare base64 JSON | `eyJkb21haW5TdHJhdGVneSI6…` |

## Xray JSON

A subset of Xray's [`routing` object](https://xtls.github.io/config/routing.html):

```json
{
  "domainStrategy": "IPIfNonMatch",
  "domainMatcher": "hybrid",
  "rules": [
    {
      "type": "field",
      "__name__": "Block ads",
      "outboundTag": "block",
      "domain": ["geosite:category-ads-all"]
    },
    {
      "type": "field",
      "name": "Local services direct",
      "outboundTag": "direct",
      "domain": ["domain:example.ru"],
      "ip": ["geoip:ru"]
    },
    {
      "type": "field",
      "outboundTag": "proxy",
      "port": "443",
      "protocol": ["quic"],
      "enabled": false
    }
  ]
}
```

| Rule field | Required | Notes |
| --- | --- | --- |
| `type` | yes | Must be `"field"`. |
| `outboundTag` | yes | Usually `proxy`, `direct` or `block`. |
| `domain` | | String or array, in Xray domain syntax. |
| `ip` | | String or array of CIDRs or `geoip:` lists. |
| `port` | | `"443"`, `"1000-2000"`. |
| `protocol` | | `http`, `tls`, `quic`, `bittorrent`. |
| `__name__` / `name` | | Display name. Defaults to `Rule N`. |
| `id` | | Stable identifier. Defaults to `subscription-rule-N`. |
| `enabled` | | Defaults to `true`. |

:::caution
Rules that use `inboundTag`, `balancerTag`, `network`, `source`, `sourcePort`, `user` or `attrs` are skipped as a whole. The client controls inbounds and outbounds itself.
:::

## Happ routing links

`happ://routing/add/` and `happ://routing/onadd/` are treated the same. The rest of the value is base64-encoded Happ routing JSON:

```json
{
  "Name": "My Provider",
  "GlobalProxy": "true",
  "DomainStrategy": "IPIfNonMatch",
  "RouteOrder": "block-proxy-direct",
  "Geoipurl": "https://cdn.example.com/geoip.dat",
  "Geositeurl": "https://cdn.example.com/geosite.dat",
  "BlockSites": ["geosite:category-ads-all"],
  "BlockIp": [],
  "ProxySites": ["geosite:youtube"],
  "ProxyIp": [],
  "DirectSites": ["geosite:category-ru"],
  "DirectIp": ["geoip:ru", "geoip:private"]
}
```

| Key | Effect |
| --- | --- |
| `BlockSites` / `BlockIp` | A rule to `block`. |
| `ProxySites` / `ProxyIp` | A rule to `proxy`. |
| `DirectSites` / `DirectIp` | A rule to `direct`. |
| `RouteOrder` | Order of the three rules, for example `direct-proxy-block`. Defaults to `block-proxy-direct`. |
| `GlobalProxy` | `false` sends unmatched traffic `direct`. Anything else sends it through the `proxy`. |
| `DomainStrategy` | Defaults to `IPIfNonMatch`. |
| `Geoipurl` / `Geositeurl` | Custom geodata for these rules. The client downloads it and offers to apply it. Until then, rules that need it run in compatibility mode. |
| `Name` | Prefix for the rule names, as in "My Provider: Block". |

Other Happ keys, such as DNS settings, are ignored.

## routing-enable

Send `routing-enable: 0` (or `false`, `off`, `no`) to stop the client from importing the `routing` header for this subscription. Any other value, or no header at all, leaves import enabled.

## Routing inside JSON profiles

Xray JSON servers can carry their own `routing` block. Those rules appear as **Profile-specific** rules and apply only while that server is selected.
