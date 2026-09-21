# Telemetry operations

MaterialXray sends diagnostics only while the user has diagnostics enabled. Telemetry must use
fixed event names and low-cardinality attributes. Never send server names, addresses, command
output, generated configuration, subscription data, package names, or user-entered text.

## Metrics

### Connection health

`connection.attempted` counts connection attempts. Its attributes are:

- `service_mode`: `root` or `vpn`
- `root_backend`: `tproxy`, `tun`, or `none`

`connection.completed` counts every attempt that finishes or is interrupted. It has the connection
attributes plus:

- `outcome`: `success`, `failure`, or `interrupted`
- `failure_stage`: present for failures, using the fixed application taxonomy
- `failure_reason`: present for failures, using the fixed application taxonomy

`connection.duration` records milliseconds with the same attributes as `connection.completed`.
Filter this distribution to `outcome=success` before using it for normal setup latency.

Use these dashboard panels:

1. Attempts by `service_mode` and `root_backend`.
2. Success rate by backend: successful `connection.completed` events divided by all completed
   events for that backend.
3. Interrupted rate by backend.
4. Failure count by `failure_stage` and `failure_reason`.
5. Successful connection duration at p50 and p95 by backend.

Do not combine the old `connection.succeeded` and `connection.failed` metrics with
`connection.completed`. Those names were replaced when interrupted attempts and duration outcomes
became explicit.

### TPROXY compatibility

`tproxy.compatibility.checked` has these attributes:

- `result`: `supported` or `unsupported`
- `reason`: `none` for supported devices or a fixed unsupported reason
- `ipv6_supported`: whether the probe found IPv6 TPROXY support
- `cached`: whether the result came from either compatibility cache

Create panels for fresh checks with `cached=false`, grouped by `result` and `reason`. Use a separate
panel for `ipv6_supported`. Cached checks describe usage frequency, not the device population, so do
not use them to calculate compatibility rates.

Probe timeouts and cleanup failures also create the fixed issue "TPROXY compatibility probe
malfunctioned". Unsupported kernel or device capabilities do not create issues.

### Core recovery

`core.recovery` has fixed `cause` and `succeeded` attributes. Chart recovery attempts by cause and
the successful recovery percentage. An unexpected process exit can also create the rate-limited
issue "Xray core exited unexpectedly".

## Initial alerts

Start with these alert rules and adjust them after two weeks of production traffic:

- Alert when the connection success rate is below 90% over 30 minutes with at least 20 completed
  attempts. Split the alert by backend.
- Alert when a backend's success rate drops by 10 percentage points against its seven-day baseline,
  with at least 20 completed attempts.
- Alert on any fresh TPROXY probe malfunction over 15 minutes.
- Alert when successful connection p95 exceeds 15 seconds over one hour with at least 20 samples.
- Alert on any failed core recovery, or more than five recovery attempts in one hour.
- Use Sentry's crash-free session alert for application crashes and ANRs. Start at 99.5% over one
  hour and tune it to the observed release volume.

Keep production trace sampling in mind when investigating step timings. Metrics are not trace
sampled, but only a sample of `connection.setup` transactions is available in production.

## Release symbolication check

Run this check before publishing a release after changing Sentry, R8, Gradle, or native build
configuration:

1. Build the signed release through `.github/workflows/release.yml`. The workflow must fail if
   `SENTRY_AUTH_TOKEN` is missing.
2. Confirm the build log shows successful ProGuard mapping, source context, and native symbol
   uploads. Treat warnings or skipped uploads as a release blocker.
3. Install the exact generated release APK on a test device with diagnostics enabled.
4. From a short-lived test branch, trigger one fixed Java exception after `TelemetryReporter` is
   enabled. Confirm Sentry shows original class and line names rather than obfuscated names.
5. From the same test build, trigger a deliberate fault inside `libxray_launcher.so`. Confirm the
   native event resolves the application library frame to a function and source line.
6. Confirm both events have the expected release and `production` environment, then delete the test
   events and remove the temporary crash triggers before publishing.

Never add a remotely accessible crash trigger or ship one in a published APK.

## Contract tests

`TelemetryReporterTest` checks opt-out behavior, event sanitization, metric names and attributes,
connection outcome accounting, failure classification, fixed trace IDs, and TPROXY compatibility
privacy. `ConnectionStepTest` checks retry-aware failure reporting and trace outcomes. Update these
tests whenever a metric name, attribute, fixed reason, or privacy rule changes.
