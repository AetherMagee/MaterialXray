# Mozilla CA certificate bundle

`app/src/main/res/raw/mozilla_ca_bundle.pem` is curl's PEM conversion of the Mozilla CA certificate store. Material Xray uses it only as an additive trust fallback on Android 7 (API 24–25), after the system trust manager rejects a certificate chain.

- Upstream data: [Mozilla NSS `certdata.txt`](https://hg.mozilla.org/projects/nss/file/tip/lib/ckfw/builtins/certdata.txt)
- PEM conversion and archive: [curl CA Extract](https://curl.se/docs/caextract.html)
- Bundle version: `VERSION`
- Pinned digest: `CHECKSUMS.sha256`
- License: Mozilla Public License 2.0

To refresh the bundle to a dated curl archive, run:

```shell
./scripts/update-ca-bundle.sh YYYY-MM-DD
```
