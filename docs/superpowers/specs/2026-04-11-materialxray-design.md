# MaterialXray Design Spec

Root-based Android proxy app using xray-core's native TUN inbound with nftables traffic routing. Jetpack Compose UI with Material Design 3.

## Target

- **Audience:** Distributable to rooted Android users
- **Min SDK:** API 31 (Android 12+)
- **Architecture:** Native TUN + nftables (no VpnService)

---

## 1. System Architecture

```
┌─────────────────────────────────────┐
│         MaterialXray App            │
│  ┌───────────┐  ┌────────────────┐  │
│  │ Compose UI │  │SubscriptionMgr │  │
│  │ (MD3)     │  │(fetch/parse)   │  │
│  └─────┬─────┘  └───────┬────────┘  │
│        │                │           │
│  ┌─────┴────────────────┴────────┐  │
│  │      XrayServiceManager       │  │
│  │  (config gen, lifecycle)      │  │
│  └──────────────┬────────────────┘  │
│                 │                   │
│  ┌──────────────┴────────────────┐  │
│  │       RootCommandRunner       │  │
│  │  (su, xray binary, nftables) │  │
│  └──────────────┬────────────────┘  │
└─────────────────┼───────────────────┘
                  │ root shell
    ┌─────────────┴──────────────┐
    │        xray-core           │
    │  TUN inbound ("wlan2")     │
    │  + outbound (fwmark=255)   │
    └─────────────┬──────────────┘
                  │
    ┌─────────────┴──────────────┐
    │   Linux kernel (nftables)  │
    │  ip rule / ip route        │
    │  UID-based app bypass      │
    └────────────────────────────┘
```

### Data Flow

1. App fetches subscription URL, decodes base64, parses share links, stores server configs in Room DB.
2. User taps Connect. App generates xray JSON config with TUN inbound + selected outbound server.
3. App executes via root shell: starts xray binary, configures IP on TUN, sets up ip rules/routes, applies nftables rules.
4. Traffic: App packets -> kernel routing -> TUN interface -> xray -> outbound proxy -> internet.
5. xray's own outbound packets are marked (fwmark 255) -> nftables accepts them -> they go directly to the real network interface, preventing routing loops.

### Loop Prevention

xray config sets `"sockopt": {"mark": 255}` on all outbounds. nftables output chain accepts packets with mark 255 before any redirection. A separate ip rule sends fwmark 255 traffic to the main routing table, while unmarked traffic goes to the TUN routing table.

---

## 2. Module Structure

```
com.materialxray
├── app/                    # Application class, Hilt DI setup
├── core/
│   ├── root/               # RootShell (persistent su session), command execution
│   ├── xray/               # XrayBinary (extract/run), ConfigGenerator, TunManager
│   ├── nftables/           # NftablesManager (rules lifecycle)
│   └── model/              # ServerConfig, Subscription, AppInfo data classes
├── data/
│   ├── db/                 # Room database, DAOs
│   ├── repository/         # ServerRepository, SubscriptionRepository, SettingsRepository
│   └── parser/             # ShareLinkParser (vless://, vmess://, trojan://, ss://), SubscriptionFetcher
├── service/
│   └── XrayService         # Foreground service (keeps xray alive, boot start)
├── ui/
│   ├── home/               # Connection toggle, current server, status
│   ├── servers/            # Server list, latency test, subscription management
│   ├── apps/               # Per-app bypass selection (package list + UID)
│   ├── routing/            # Xray routing rules editor (geoip/geosite)
│   ├── settings/           # TUN name, DNS, misc config
│   └── theme/              # MD3 theme, dynamic color
```

### Database (Room) — 3 Tables

| Table | Key Fields |
|-------|-----------|
| `servers` | id, subscriptionId, name, protocol, address, port, configJson, latencyMs, sortOrder |
| `subscriptions` | id, name, url, lastUpdated, autoUpdate |
| `app_bypass` | uid, packageName, excluded (boolean) |

### Settings (DataStore Preferences)

TUN interface name, DNS servers, fwmark value, auto-connect on boot, last-used server ID, theme preference.

---

## 3. Root & Network Layer

### RootShell

Maintains a persistent `su` session (single shell, reused across commands). Avoids repeated superuser prompts. Commands are written to stdin, output read from stdout/stderr with delimiters.

### Connect Sequence

```
1. ensureCleanState()           ← idempotent cleanup of any prior state
2. writeStateFile(partial)      ← record intent
3. Extract xray binary to filesDir (if needed)
4. Write generated xray config JSON
5. Start xray: "xray run -c config.json &"
6. Poll for TUN interface: "ip link show $tunName" (with timeout)
7. Configure TUN:
   - ip addr add 10.0.0.1/30 dev $tunName
   - ip link set $tunName up
8. Apply nftables rules
9. Apply ip rules + routes
10. updateStateFile(complete)
```

### nftables Ruleset

```nft
table inet xray {
    set bypass_uids {
        type uid_t
        elements = { ... }   # populated from app_bypass table
    }

    chain output {
        type route hook output priority 0; policy accept;
        meta mark 255 accept              # xray's own traffic
        oifname "lo" accept               # loopback
        meta skuid @bypass_uids accept    # excluded apps
        ip protocol icmp accept           # ICMP (TUN can't handle)
        ip6 nexthdr icmpv6 accept
        meta mark set 100                 # everything else -> TUN
    }
}
```

### IP Routing

```
ip rule add fwmark 255 table main prio 10
ip rule add fwmark 100 table 100 prio 20
ip route add default dev $tunName table 100
```

### DNS Handling

xray config includes a `dns` section with user-configured upstream DNS servers (default: `1.1.1.1`, `8.8.8.8`). DNS queries from apps enter the TUN like all other traffic and are handled by xray's internal DNS resolver, which routes them through the proxy outbound. No system DNS settings are modified — the TUN captures DNS traffic (port 53) via the routing rules already in place. xray's routing section includes a rule to intercept UDP/TCP port 53 and send it to the dns outbound tag.

### Disconnect / Teardown

```
1. Kill xray process (SIGTERM, SIGKILL after 3s timeout)
2. nft delete table inet xray
3. ip rule del fwmark 255 table main prio 10
4. ip rule del fwmark 100 table 100 prio 20
5. ip route flush table 100
6. ip link del $tunName (if lingering)
7. Restore DNS
8. Delete state file
```

---

## 4. Resilience & Cleanup

### State File

Written to app filesDir as `state.json`:

```json
{
  "xrayPid": 12345,
  "tunName": "wlan2",
  "nftTableCreated": true,
  "ipRulesApplied": true,
  "routeTable": 100,
  "fwmark": 255,
  "timestamp": 1712838400
}
```

### ensureCleanState() — Idempotent

Runs at: app start, before connect, service onDestroy, boot receiver.

1. Kill orphaned xray process (from state file PID + `pkill -f "xray run"` fallback).
2. Delete nft table if exists (`nft list tables` check, then `nft delete table inet xray`). Atomic — removes all chains/rules regardless of partial creation state.
3. List and delete ip rules referencing our table/marks (`ip rule list` -> parse -> delete matching).
4. Flush routing table (`ip route flush table 100`).
5. Delete TUN interface if lingering (`ip link del $tunName`).
6. Restore DNS if saved original state exists.
7. Delete state file.

Every step is try/catch — logs errors but does not abort. Partial cleanup is better than no cleanup.

---

## 5. Subscription & Config

### Share Link Parsing

Supports: `vless://`, `vmess://`, `trojan://`, `ss://`

Each parser extracts: protocol, address, port, UUID/password, transport (tcp/ws/grpc/xhttp), security (tls/reality), and protocol-specific fields (flow, pbk, sni, fp, path, host, etc). Fragment is parsed as the server display name.

### Xray Config Generation

`ConfigGenerator` takes a `ServerConfig` + user settings and produces a full xray JSON config:

- **inbounds:** TUN inbound with configured name and MTU
- **outbounds:** proxy outbound (from server config), direct outbound (with fwmark sockopt), DNS outbound
- **routing:** user-configured rules using geoip/geosite dat files, plus built-in rules (direct for private IPs, DNS hijack)
- **dns:** configured DNS servers, routed through proxy or direct per user preference
- **sockopt on all outbounds:** `"mark": 255` for loop prevention

### Full Config Passthrough

Users can also paste/import a raw xray JSON config. The app injects the TUN inbound and fwmark sockopt, leaving the rest untouched.

---

## 6. UI Design

### Navigation

Bottom navigation bar with 4 tabs: Home, Servers, Apps, Settings.

### Material Design 3

- Dynamic color (Material You) from wallpaper on Android 12+
- Dark/light theme following system setting
- MD3 components throughout: cards, switches, text fields, FABs, top app bars

### Home Tab

- Large connection toggle (FAB or prominent button) in center
- Current server name with flag emoji
- Connection state: Disconnected / Connecting / Connected / Disconnecting / Error
- Uptime timer when connected
- Current server latency display

### Servers Tab

- Subscription groups as expandable sections (grouped by subscription URL)
- Each server row: name, flag, protocol chip (VLESS/VMess/Trojan/SS), latency indicator
- Long-press for details / edit raw config JSON
- Pull-to-refresh updates all subscriptions
- "Test all" button to ping all servers in parallel
- FAB to add subscription URL or manual server config

### Apps Tab

- Scrollable list of installed apps with icon, name, package name
- Toggle switch per app (included/excluded from proxy)
- Search bar to filter by name/package
- "Select all" / "Deselect all" actions in top bar

### Settings Tab

- TUN interface name (text field, default "xray0")
- DNS server configuration
- Routing rules section (geoip/geosite dat file management, custom domain/IP rules)
- Auto-connect on boot toggle
- Backup / Restore (via SAF file picker)
- About section: app version, xray-core version

---

## 7. Service Layer

### XrayService (Foreground Service)

Persistent notification showing: connection status, current server name, disconnect action button.

Holds: root shell session, xray process reference, `StateFlow<ConnectionState>`.

```kotlin
enum ConnectionState {
    Disconnected,
    Connecting,
    Connected(serverName, uptime, bytesIn, bytesOut),
    Disconnecting,
    Error(message)
}
```

UI observes this StateFlow for all connection state rendering.

### Boot Start

`BootReceiver` on BOOT_COMPLETED: check DataStore for auto-connect preference. If enabled, read last-used server from Room, run `ensureCleanState()`, start `XrayService`.

### Latency Testing

TCP connect to `address:port` with 3s timeout, measure round-trip. Parallel execution with coroutineScope on limited dispatcher (4 concurrent). Results stored in Room `servers.latencyMs`.

### Config Backup/Restore

- **Export:** Serialize Room DB (servers, subscriptions, app bypass) + DataStore preferences into single JSON. User picks save location via SAF.
- **Import:** Read JSON, validate structure, disconnect if connected, replace DB + preferences. Prompt confirmation before overwrite.

---

## 8. Xray Binary Management

Bundled in APK under `assets/` or `jniLibs/` (per-ABI: arm64-v8a, armeabi-v7a, x86_64). Extracted to app's `filesDir/xray` on first launch or version change. Marked executable via root shell (`chmod 755`).

Version tracked in DataStore — on app update, re-extract if version changed.

---

## 9. Dependencies

- **Jetpack Compose** + Material 3
- **Hilt** for DI
- **Room** for database
- **DataStore** for preferences
- **Kotlin Coroutines + Flow** for async/reactive
- **OkHttp** or **Ktor** for subscription HTTP fetching
- **kotlinx.serialization** for JSON (xray config generation, share link parsing, backup format)
- **Navigation Compose** for bottom nav routing
- **xray-core** binary (bundled, not a library dependency)
