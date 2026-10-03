#!/bin/bash
# Points the build at another Xray release. The Gradle build downloads the binaries itself; this
# script records what it may accept: the version, its source commit and license, and the SHA-256 of
# every official archive and of the executable inside it.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

VERSION_FILE="third_party/xray/VERSION"
CHECKSUM_FILE="third_party/xray/CHECKSUMS.sha256"
LICENSE_FILE="third_party/xray/LICENSE"
COMMIT_FILE="third_party/xray/COMMIT"
VERSION="${1:-}"
if [[ ! "${VERSION}" =~ ^v[0-9]+([.][0-9]+)*$ ]]; then
  echo "Usage: $0 <Xray release tag, e.g. v26.9.30>" >&2
  exit 1
fi

BASE_URL="https://github.com/XTLS/Xray-core/releases/download/${VERSION}"
WORK_DIR="$(mktemp -d)"
declare -a ARCHIVE_CHECKSUMS=()
declare -a BINARY_CHECKSUMS=()

cleanup() {
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

verify_xray() {
  local archive_name="$1"
  local abi="$2"
  local unpack_dir="${WORK_DIR}/${archive_name%.zip}"
  local archive_path="${WORK_DIR}/${archive_name}"
  local digest_path="${archive_path}.dgst"
  local expected_sha256=""
  local actual_sha256
  local algorithm
  local digest

  echo "Downloading ${archive_name}..."
  curl -fL "${BASE_URL}/${archive_name}" -o "${archive_path}"
  curl -fL "${BASE_URL}/${archive_name}.dgst" -o "${digest_path}"
  while IFS='=' read -r algorithm digest; do
    if [[ "${algorithm}" == "SHA2-256" ]]; then
      expected_sha256="${digest//[[:space:]]/}"
      break
    fi
  done < "${digest_path}"
  if [[ -z "${expected_sha256}" ]]; then
    echo "No SHA-256 digest found for ${archive_name}" >&2
    exit 1
  fi
  read -r actual_sha256 _ < <(sha256sum "${archive_path}")
  if [[ "${actual_sha256}" != "${expected_sha256}" ]]; then
    echo "SHA-256 mismatch for ${archive_name}" >&2
    exit 1
  fi

  mkdir -p "${unpack_dir}"
  unzip -qo "${archive_path}" xray LICENSE -d "${unpack_dir}"

  if [[ -f "${WORK_DIR}/xray-license" ]]; then
    cmp "${WORK_DIR}/xray-license" "${unpack_dir}/LICENSE"
  else
    cp "${unpack_dir}/LICENSE" "${WORK_DIR}/xray-license"
  fi

  read -r actual_sha256 _ < <(sha256sum "${unpack_dir}/xray")
  ARCHIVE_CHECKSUMS+=("${expected_sha256}  ${archive_name%.zip}-${VERSION}.zip")
  BINARY_CHECKSUMS+=("${actual_sha256}  lib/${abi}/libxray.so")
}

echo "Verifying xray-core ${VERSION}..."

# Both modes run the Android build. It only adopts a TUN as an open fd via xray.tun.fd: rootless
# mode hands it VpnService's, root mode creates one with the libxraytun.so launcher.
verify_xray "Xray-android-arm64-v8a.zip" "arm64-v8a"

XRAY_COMMIT=""
while read -r commit ref; do
  if [[ "${ref}" == "refs/tags/${VERSION}^{}" ]]; then
    XRAY_COMMIT="${commit}"
    break
  fi
  if [[ "${ref}" == "refs/tags/${VERSION}" ]]; then
    XRAY_COMMIT="${commit}"
  fi
done < <(git ls-remote "https://github.com/XTLS/Xray-core.git" "refs/tags/${VERSION}" "refs/tags/${VERSION}^{}")
if [[ ! "${XRAY_COMMIT}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Unable to resolve Xray commit for ${VERSION}" >&2
  exit 1
fi

./scripts/prepare-xray-source.sh \
  "${VERSION}" \
  "${WORK_DIR}/Xray-core-${VERSION}-source.tar.gz" \
  "${XRAY_COMMIT}"

cp "${WORK_DIR}/xray-license" "${LICENSE_FILE}"
printf '%s\n' "${VERSION}" > "${VERSION_FILE}"
printf '%s\n' "${XRAY_COMMIT}" > "${COMMIT_FILE}"
printf '%s\n' "${ARCHIVE_CHECKSUMS[@]}" "${BINARY_CHECKSUMS[@]}" > "${CHECKSUM_FILE}"

echo "Recorded Xray ${VERSION}."
