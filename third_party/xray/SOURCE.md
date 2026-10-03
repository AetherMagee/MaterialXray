# Xray-core Corresponding Source

Material Xray distributes the unmodified official Android arm64-v8a and x86_64 Xray-core executables. Upstream publishes no Android armeabi-v7a build, so Material Xray builds that one, unmodified, from the same source commit with upstream's Android build flags and Go toolchain.

- Version: `VERSION`
- Exact source commit: `COMMIT`
- Go toolchain: `GO_TOOLCHAIN`
- Verified archive and executable hashes of the official builds: `CHECKSUMS.sha256`
- Upstream source and releases: https://github.com/XTLS/Xray-core
- Xray-core license: MPL-2.0, reproduced in `LICENSE`

The pinned source tree's `go.mod` records dependency versions. The corresponding-source archive preserves each vendored module's source and license files.

Material Xray release pages attach an `Xray-core-<version>-source.tar.gz` archive containing the commit-pinned Xray-core source tree and vendored Go modules, including their original license files. `scripts/prepare-xray-source.sh` creates that archive.
