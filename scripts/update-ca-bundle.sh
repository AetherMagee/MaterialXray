#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

VERSION_FILE="third_party/mozilla-ca-bundle/VERSION"
CHECKSUM_FILE="third_party/mozilla-ca-bundle/CHECKSUMS.sha256"
DESTINATION="app/src/main/res/raw/mozilla_ca_bundle.pem"
VERSION="${1:-$(<"${VERSION_FILE}")}"
if [[ ! "${VERSION}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  echo "Invalid Mozilla CA bundle version: ${VERSION}" >&2
  exit 1
fi

FILE_NAME="cacert-${VERSION}.pem"
BASE_URL="https://curl.se/ca"
WORK_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

echo "Downloading Mozilla CA bundle ${VERSION}..."
curl -fL "${BASE_URL}/${FILE_NAME}" -o "${WORK_DIR}/${FILE_NAME}"
curl -fL "${BASE_URL}/${FILE_NAME}.sha256" -o "${WORK_DIR}/${FILE_NAME}.sha256"

read -r expected_sha256 _ < "${WORK_DIR}/${FILE_NAME}.sha256"
read -r actual_sha256 _ < <(sha256sum "${WORK_DIR}/${FILE_NAME}")
if [[ ! "${expected_sha256}" =~ ^[0-9a-f]{64}$ ]] || [[ "${actual_sha256}" != "${expected_sha256}" ]]; then
  echo "SHA-256 mismatch for ${FILE_NAME}" >&2
  exit 1
fi

certificate_count="$(grep -c -- "-----BEGIN CERTIFICATE-----" "${WORK_DIR}/${FILE_NAME}")"
if ((certificate_count == 0)); then
  echo "No certificates found in ${FILE_NAME}" >&2
  exit 1
fi

install -m 644 "${WORK_DIR}/${FILE_NAME}" "${DESTINATION}"
printf '%s\n' "${VERSION}" > "${VERSION_FILE}"
printf '%s  %s\n' "${actual_sha256}" "${DESTINATION}" > "${CHECKSUM_FILE}"

echo "Installed ${certificate_count} certificates from ${FILE_NAME}."
