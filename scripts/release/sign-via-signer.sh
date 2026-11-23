#!/usr/bin/env bash
# Sign the release jar and its checksum file through pkg-signer on the krost-infra VPS.
#
# The GPG private key lives only in the pkg-signer volume; this runner holds SSH keys and
# nothing else. Reached over the tailnet (the workflow joins it first): the host (user
# gernot, key restricted to port-forwarding to 127.0.0.1:2222) is the bastion, the signer
# sits behind it. Transfer uses the chrooted signer-sftp account, commands the signer
# account (krost-infra#292, docs/ci-signing-access.md there).
#
# usage: sign-via-signer.sh <dir> <version>
#   <dir> holds unidrive-<version>.jar and unidrive-<version>.jar.sha256; on success it also holds
#   unidrive-<version>.jar.asc and unidrive-<version>.jar.sha256.asc.
#
# env (all required unless noted):
#   BASTION_SSH_KEY  path to the bastion private key
#   SIGNER_SSH_KEY   path to the signer private key
#   BASTION_HOST     default 100.84.30.57 (the host's tailnet address)
set -euo pipefail

[[ $# -eq 2 ]] || {
  echo "usage: $0 <dir> <version>" >&2
  exit 64
}
dir=$1
ver=$2
: "${BASTION_SSH_KEY:?}" "${SIGNER_SSH_KEY:?}"
bastion_host=${BASTION_HOST:-100.84.30.57}

jar="unidrive-${ver}.jar"
sum="unidrive-${ver}.jar.sha256"
for f in "$jar" "$sum"; do
  [[ -f $dir/$f ]] || {
    echo "missing $dir/$f" >&2
    exit 1
  }
done

cfg=$(mktemp)
batch=$(mktemp)
trap 'rm -f "$cfg" "$batch"' EXIT
cat >"$cfg" <<CFG
Host bastion
    HostName $bastion_host
    User gernot
    Port 22
    IdentityFile $BASTION_SSH_KEY
    IdentitiesOnly yes
    StrictHostKeyChecking accept-new

Host pkg-signer
    HostName 127.0.0.1
    Port 2222
    IdentityFile $SIGNER_SSH_KEY
    IdentitiesOnly yes
    ProxyJump bastion
    StrictHostKeyChecking accept-new
CFG

ssh_() { ssh -F "$cfg" "$@"; }

echo "[sign] uploading $jar and $sum"
{
  echo "cd /var/lib/signer/drop"
  echo "lcd $dir"
  echo "put $jar"
  echo "put $sum"
} >"$batch"
sftp -F "$cfg" -b "$batch" signer-sftp@pkg-signer

echo "[sign] sign-drop"
ssh_ signer@pkg-signer sign-drop

echo "[sign] fetching signatures"
{
  echo "cd /var/lib/signer/signed"
  echo "lcd $dir"
  echo "get ${jar}.asc"
  echo "get ${sum}.asc"
} >"$batch"
sftp -F "$cfg" -b "$batch" signer-sftp@pkg-signer

echo "[sign] clear-signed"
ssh_ signer@pkg-signer clear-signed

for f in "${jar}.asc" "${sum}.asc"; do
  [[ -s $dir/$f ]] || {
    echo "signature $f missing or empty" >&2
    exit 1
  }
done
echo "[sign] done"
