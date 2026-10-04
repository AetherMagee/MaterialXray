---
title: Share links
description: Supported URI schemes and the parameters Material Xray reads.
---

Material Xray reads the share-link formats that most Xray clients use. The text after `#` is the server name.

| Scheme | Add directly | In subscriptions |
| --- | :-: | :-: |
| `vless://` | ✅ | ✅ |
| `vmess://` | ✅ | ✅ |
| `trojan://` | ✅ | ✅ |
| `ss://` | ✅ | ✅ |
| `hysteria2://`, `hy2://` | ✅ | ✅ |
| `wireguard://`, `wg://` | | ✅ |
| `socks://`, `socks5://`, `socks5h://` | | ✅ |
| `http://`, `https://` proxies | | ✅ |
| Xray JSON | | ✅ |

## VLESS

```text
vless://<uuid>@<host>:<port>?type=<transport>&security=<tls|reality|none>&…#<name>
```

| Parameter | Meaning |
| --- | --- |
| `type` | Transport: `tcp` (default), `ws`, `grpc`, `xhttp`, `httpupgrade`, … |
| `path`, `host` | Transport path and host. |
| `serviceName` | gRPC service name. |
| `mode` | xHTTP mode. |
| `extra` | xHTTP extra settings (JSON). |
| `security` | `none` (default), `tls`, `reality`. |
| `sni`, `fp`, `alpn` | TLS server name, uTLS fingerprint, ALPN list. |
| `pbk`, `sid`, `spx`, `pqv` | REALITY public key, short ID, spider X, ML-DSA-65 verify key. |
| `flow` | For example `xtls-rprx-vision`. |
| `encryption` | VLESS encryption. |

## VMess

The v2rayN format: `vmess://` followed by base64-encoded JSON (`add`, `port`, `id`, `net`, `path`, `host`, `tls`, `sni`, `ps` for the name, and so on).

## Trojan

```text
trojan://<password>@<host>:<port>?security=tls&sni=…&type=…#<name>
```

Accepts the same transport and TLS parameters as VLESS. `security` defaults to `tls`.

## Shadowsocks

SIP002 (`ss://base64(method:password)@host:port#name`) and the legacy fully-base64 form.

## Hysteria2

```text
hysteria2://<auth>@<host>:<port>?sni=…&obfs=salamander&obfs-password=…#<name>
```

| Parameter | Meaning |
| --- | --- |
| `sni` (or `peer`) | TLS server name. |
| `alpn` | Defaults to `h3`. |
| `insecure` | Skip certificate verification. |
| `pinSHA256` | Pin the certificate. |
| `obfs`, `obfs-password` | Salamander obfuscation. |
| `congestion` | Congestion control. |

## WireGuard

```text
wireguard://<private key>@<host>:<port>?publickey=…&address=…&mtu=…&reserved=…#<name>
```

Also reads `presharedkey` (or `psk`), `keepalive` (or `persistentkeepalive`), `allowedips`, and the `peerPublicKey` / `public_key` spellings of the public key.
