#!/usr/bin/env bash
#
# UniDrive end-user installer.
#
# Installs:
#   ~/.local/lib/unidrive/unidrive-<ver>.jar       (fat shadowJar, CLI)
#   ~/.local/bin/unidrive                          (wrapper -> CLI)
#   ~/.config/systemd/user/unidrive.service        (user-mode systemd unit)
#   ~/.config/systemd/user/unidrive@.service       (per-profile template)
#   ~/.config/systemd/user/unidrive-mount@.service (per-profile FUSE mount template)
#   ~/.local/share/unidrive/                       (log dir)
#
# Usage:
#   bash dist/install.sh                # use locally-built JAR from core/app/cli/build/libs
#   bash dist/install.sh <path/to.jar>  # use the given CLI fat JAR (e.g. from a GitHub release)
#
# After install:
#   systemctl --user daemon-reload
#   systemctl --user enable --now unidrive.service
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

INSTALL_LIB="${HOME}/.local/lib/unidrive"
INSTALL_BIN="${HOME}/.local/bin"
LOG_DIR="${HOME}/.local/share/unidrive"
SYSTEMD_DIR="${HOME}/.config/systemd/user"

# Fingerprint of the release signing key (release.yml signs the published JAR).
# When set and a .asc signature sits next to the JAR being installed, the
# signature is verified against it. Empty (default) skips the signature check
# with a note; never fill this in with anything but the real fingerprint.
RELEASE_KEY_FINGERPRINT="${UNIDRIVE_RELEASE_KEY_FINGERPRINT:-}"

resolve_jar() {
    local dir="$1"
    local prefix="$2"
    local found=""
    if [[ -d "${dir}" ]]; then
        # shellcheck disable=SC2012  # ls -1 is fine; filenames are project-controlled
        found="$(ls -1 "${dir}"/"${prefix}"-*.jar 2>/dev/null \
            | sort -V \
            | tail -1)"
    fi
    printf '%s' "${found}"
}

CLI_JAR=""

if [[ $# -ge 1 ]]; then
    CLI_JAR="$1"
    if [[ ! -f "${CLI_JAR}" ]]; then
        echo "ERROR: CLI JAR not found at: ${CLI_JAR}" >&2
        exit 1
    fi
else
    CLI_JAR="$(resolve_jar "${REPO_ROOT}/core/app/cli/build/libs" "unidrive")"

    if [[ -z "${CLI_JAR}" ]]; then
        echo "ERROR: unidrive CLI JAR not found in core/app/cli/build/libs/" >&2
        echo "Build it first:" >&2
        echo "  (cd core && ./gradlew :app:cli:shadowJar -q)" >&2
        exit 1
    fi
fi

CLI_BASENAME="$(basename "${CLI_JAR}")"

# Verify the artefacts when they sit next to the JAR (as published by the
# release workflow): sha256sum -c on the .sha256 file, and gpg --verify on the
# .asc file against RELEASE_KEY_FINGERPRINT. A mismatch fails the install,
# before any service is stopped or file touched.
sha_file="${CLI_JAR}.sha256"
if [[ -f "${sha_file}" ]]; then
    echo "Verifying checksum: ${sha_file}"
    (cd "$(dirname "${CLI_JAR}")" && sha256sum -c --ignore-missing "$(basename "${sha_file}")")
else
    echo "NOTE: no checksum file at ${sha_file}; skipping sha256 verification"
fi

# gpg --verify of <asc> against <file>, with the rules the JAR block had: a
# missing (or empty) .asc stays allowed — a checksum-only release installs —
# a present .asc without a configured fingerprint or without gpg warns, and a
# bad or foreign signature fails the install (exit 1) before anything is
# replaced. The co-daemon download runs the same check on its tarball.
verify_release_signature() { # <file> <asc>
    local file="$1" asc="$2"
    if [[ ! -s "${asc}" ]]; then
        return 0
    fi
    if [[ -z "${RELEASE_KEY_FINGERPRINT}" ]]; then
        echo "NOTE: signature file present at ${asc} but no release key fingerprint is configured"
        echo "      (set UNIDRIVE_RELEASE_KEY_FINGERPRINT); skipping signature verification"
        return 0
    fi
    if ! command -v gpg >/dev/null 2>&1; then
        echo "ERROR: ${asc} is present but gpg is not installed; cannot verify the signature" >&2
        exit 1
    fi
    echo "Verifying signature: ${asc}"
    verify_out="$(gpg --verify "${asc}" "${file}" 2>&1)" || {
        printf '%s\n' "${verify_out}" >&2
        echo "ERROR: signature verification failed for ${file}" >&2
        exit 1
    }
    # gpg prints the primary key fingerprint in spaced hex groups; strip
    # the spaces before comparing with the configured fingerprint.
    if ! tr -d ' \n' <<<"${verify_out}" | grep -qF -- "${RELEASE_KEY_FINGERPRINT}"; then
        printf '%s\n' "${verify_out}" >&2
        echo "ERROR: signature was made by a key other than ${RELEASE_KEY_FINGERPRINT}" >&2
        exit 1
    fi
}

verify_release_signature "${CLI_JAR}" "${CLI_JAR}.asc"

# Java is checked by the launcher on every run; warn here too so a missing runtime shows at install time.
java_ok=0
for cand in "${UNIDRIVE_JAVA:-}" "$(command -v java || true)" /usr/lib/jvm/*/bin/java; do
    if [[ -n "${cand}" && -x "${cand}" ]]; then
        major="$("${cand}" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
        if [[ -n "${major}" && "${major}" -ge 25 ]]; then java_ok=1; break; fi
    fi
done
if [[ "${java_ok}" == "0" ]]; then
    echo "WARNING: no Java 25+ runtime found; unidrive will not start until one is installed" >&2
    echo "         (Debian/Ubuntu: sudo apt install openjdk-25-jre-headless) or UNIDRIVE_JAVA is set." >&2
fi

# Stop every active unidrive service (daemon, per-profile, mount units) before replacing the jar they
# run, and restart exactly those afterwards; a deliberately stopped service stays stopped. One stop and
# one start call each so systemd applies the units' own ordering.
ACTIVE_UNITS=()
if command -v systemctl >/dev/null 2>&1; then
    while read -r unit _; do
        if [[ "${unit}" == unidrive*.service ]]; then ACTIVE_UNITS+=("${unit}"); fi
    done < <(systemctl --user list-units --state=active --no-legend --plain 'unidrive*.service' 2>/dev/null || true)
    if [[ ${#ACTIVE_UNITS[@]} -gt 0 ]]; then
        echo "Stopping: ${ACTIVE_UNITS[*]}"
        systemctl --user stop "${ACTIVE_UNITS[@]}"
    fi
fi

# Processes still running the previous jar from this install, not started by a unit. Captured while the
# active units are stopped: a service daemon restarted further down must not be mistaken for one of
# these. A hand-started JVM is safe (it holds the old jar's inode) but keeps the old code until restarted.
STALE_PIDS=()
for cmdline in /proc/[0-9]*/cmdline; do
    pid="${cmdline#/proc/}"; pid="${pid%/cmdline}"
    if [[ "${pid}" != "$$" ]] && { tr '\0' ' ' < "${cmdline}"; } 2>/dev/null | grep -qF -- "-jar ${INSTALL_LIB}/unidrive"; then
        STALE_PIDS+=("${pid}")
    fi
done

echo "Installing UniDrive..."

# JAR
mkdir -p "${INSTALL_LIB}"
# Sweep legacy / stale jars before the copy. Pre-slim builds shipped
# `unidrive-mcp-*.jar` alongside the CLI jar; the MCP module was deleted
# but old artifacts linger from prior installs.
find "${INSTALL_LIB}" -maxdepth 1 -type f -name 'unidrive*.jar' \
    ! -name "${CLI_BASENAME}" -print -delete 2>/dev/null \
  | sed 's|^|  pruned stale jar: |'
# Unlink, then copy to a fresh inode: a JVM that still holds the old jar open (a daemon or mount not
# started by a service) keeps reading the old, intact file instead of a truncated one.
rm -f "${INSTALL_LIB}/${CLI_BASENAME}"
cp "${CLI_JAR}" "${INSTALL_LIB}/${CLI_BASENAME}"
echo "  ${INSTALL_LIB}/${CLI_BASENAME}"
# The startup caches beside the jar are keyed to the jar that built them. After a jar replacement a stale
# unidrive.jsa is rejected on every start ("shared class paths mismatch") and AutoCreateSharedArchive never
# regenerates a rejected archive (verified on JDK 27), so a stale one degrades every launch until removed by
# hand; a stale unidrive.aot is equally dead. The deploy that replaces the jar deletes them in the same step,
# and any other deploy that copies the jar must do the same; the next start recreates whichever cache the
# launcher then picks.
rm -f "${INSTALL_LIB}/unidrive.jsa" "${INSTALL_LIB}/unidrive.aot"

# jvm-flags.txt beside the jar: DaemonAutospawn reads the same single source the wrapper above was
# rendered from when it spawns the daemon itself.
cp "${SCRIPT_DIR}/launcher/jvm-flags.txt" "${INSTALL_LIB}/"
echo "  ${INSTALL_LIB}/jvm-flags.txt"

# Coroutine-debug probe jar (archive#613 ask 1): deployed beside the fat jar so the
# launcher's UNIDRIVE_COROUTINE_DEBUG gate can -javaagent it at daemon JVM start. Optional:
# a tree without the probe jar (an older build, a slim release tarball) installs fine without it.
DEBUG_AGENT_JAR=""
for candidate in "$(dirname "${CLI_JAR}")"/kotlinx-coroutines-debug-*.jar; do
    if [[ -f "${candidate}" ]]; then
        cp "${candidate}" "${INSTALL_LIB}/"
        DEBUG_AGENT_JAR="${INSTALL_LIB}/$(basename "${candidate}")"
        echo "  ${DEBUG_AGENT_JAR}"
        break
    fi
done

# CLI wrapper, rendered from the same template and flag list as the Gradle deploy launcher.
mkdir -p "${INSTALL_BIN}"
STATIC_FLAGS=""
while IFS= read -r flag; do
    flag="${flag%%#*}"; flag="${flag//[[:space:]]/}"
    [[ -n "${flag}" ]] && STATIC_FLAGS+="\"${flag}\" "
done < "${SCRIPT_DIR}/launcher/jvm-flags.txt"
sed -e "s|@STATIC_FLAGS_SH@|${STATIC_FLAGS% }|" -e "s|@JAR@|${INSTALL_LIB}/${CLI_BASENAME}|" \
    -e "s|@COROUTINES_DEBUG_AGENT@|${DEBUG_AGENT_JAR}|" \
    "${SCRIPT_DIR}/launcher/unidrive.sh.tmpl" > "${INSTALL_BIN}/unidrive"
chmod +x "${INSTALL_BIN}/unidrive"
echo "  ${INSTALL_BIN}/unidrive"

# Log directory
mkdir -p "${LOG_DIR}"

# Co-daemon (unidrive-mount Rust binary): downloaded from the release of
# gkrost/unidrive-mount-linux and SHA256-verified before it is installed, the
# same verify-then-install order the JAR above follows. MOUNT_RELEASE pins the
# release this script installs by default — bump the default deliberately when
# a new mount release should become what a plain install ships (the URL is
# derived from it). UNIDRIVE_MOUNT_RELEASE (tag) and UNIDRIVE_MOUNT_RELEASE_URL
# (full URL) override the default for tests and pinning. UNIDRIVE_MOUNT_REPO
# (set by scripts/dev/redeploy-local.sh) skips the download entirely: that
# deploy builds the co-daemon from the local repo and installs it itself, so
# this script leaves the binary untouched. While the pinned release does not
# exist yet, the download is skipped with a note and the install continues
# without the mount.
MOUNT_RELEASE="${UNIDRIVE_MOUNT_RELEASE:-v0.1.0}"
MOUNT_BIN="${INSTALL_LIB}/unidrive-mount"
# The mount release ships x86_64 and aarch64; installing the x86_64 binary on
# an ARM host would verify cleanly and still not run, so the host architecture
# picks the tarball and a host the release does not build for skips the
# download with a note.
case "$(uname -m)" in
    x86_64 | amd64) MOUNT_ARCH=x86_64 ;;
    aarch64 | arm64) MOUNT_ARCH=aarch64 ;;
    *) MOUNT_ARCH="" ;;
esac
MOUNT_TARBALL="unidrive-mount-${MOUNT_RELEASE#v}-${MOUNT_ARCH}.tar.gz"
MOUNT_RELEASE_URL="${UNIDRIVE_MOUNT_RELEASE_URL:-https://github.com/gkrost/unidrive-mount-linux/releases/download/${MOUNT_RELEASE}/${MOUNT_TARBALL}}"
mount_tmp=""

# The EXIT trap only fires with a live staging directory; the if-form keeps a
# non-empty ${mount_tmp} check from overriding the script's own exit status.
trap 'if [[ -n "${mount_tmp:-}" ]]; then rm -rf "${mount_tmp}"; fi' EXIT

mount_manual_hint() {
    echo "  cd <repo>/unidrive-mount-linux && cargo build --release \\"
    echo "    && cp target/release/unidrive-mount ${INSTALL_LIB}/"
}

if [[ -n "${UNIDRIVE_MOUNT_REPO:-}" ]]; then
    echo "Co-daemon: UNIDRIVE_MOUNT_REPO is set; the local build is deployed by"
    echo "scripts/dev/redeploy-local.sh, ${MOUNT_BIN} is left untouched."
elif ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
    echo ""
    echo "Co-daemon download skipped: neither curl nor wget is available. Build manually:"
    mount_manual_hint
    echo ""
elif [[ -z "${MOUNT_ARCH}" ]]; then
    echo ""
    echo "Co-daemon download skipped: the pinned release has no build for $(uname -m). Build manually:"
    mount_manual_hint
    echo ""
else
    mount_tmp="$(mktemp -d)"
    mount_downloaded=1
    mount_err=""
    if command -v curl >/dev/null 2>&1; then
        mount_err="$(curl -fsSL --retry 3 -o "${mount_tmp}/${MOUNT_TARBALL}" "${MOUNT_RELEASE_URL}" 2>&1)" \
            && mount_err="$(curl -fsSL --retry 3 -o "${mount_tmp}/${MOUNT_TARBALL}.sha256" "${MOUNT_RELEASE_URL}.sha256" 2>&1)" \
            || mount_downloaded=0
    else
        mount_err="$(wget -q --tries=3 -O "${mount_tmp}/${MOUNT_TARBALL}" "${MOUNT_RELEASE_URL}" 2>&1)" \
            && mount_err="$(wget -q --tries=3 -O "${mount_tmp}/${MOUNT_TARBALL}.sha256" "${MOUNT_RELEASE_URL}.sha256" 2>&1)" \
            || mount_downloaded=0
    fi
    if [[ "${mount_downloaded}" == "1" ]]; then
        if (cd "${mount_tmp}" && sha256sum -c "${MOUNT_TARBALL}.sha256"); then
            # The checksum is fetched from the same release as the tarball, so it catches a corrupted
            # download, not a replaced release asset. The mount release attaches .asc files once its
            # signing secrets are set; verify when present, under the same rules as the JAR.
            if command -v curl >/dev/null 2>&1; then
                curl -fsSL --retry 3 -o "${mount_tmp}/${MOUNT_TARBALL}.asc" "${MOUNT_RELEASE_URL}.asc" 2>/dev/null || true
            else
                wget -q --tries=3 -O "${mount_tmp}/${MOUNT_TARBALL}.asc" "${MOUNT_RELEASE_URL}.asc" 2>/dev/null || true
            fi
            verify_release_signature "${mount_tmp}/${MOUNT_TARBALL}" "${mount_tmp}/${MOUNT_TARBALL}.asc"
            tar -xzf "${mount_tmp}/${MOUNT_TARBALL}" -C "${mount_tmp}"
            mount_dist_bin="${mount_tmp}/unidrive-mount-${MOUNT_RELEASE#v}/unidrive-mount"
            if [[ ! -f "${mount_dist_bin}" ]]; then
                echo "ERROR: ${MOUNT_TARBALL} does not contain unidrive-mount-${MOUNT_RELEASE#v}/unidrive-mount" >&2
                exit 1
            fi
            # Fresh inode like the jar copy above: a mount process still holding
            # the old binary keeps reading it intact.
            rm -f "${MOUNT_BIN}"
            cp "${mount_dist_bin}" "${MOUNT_BIN}"
            chmod +x "${MOUNT_BIN}"
            echo "  ${MOUNT_BIN} (${MOUNT_RELEASE})"
        else
            echo "ERROR: SHA256 verification failed for ${MOUNT_TARBALL}; refusing to install the co-daemon" >&2
            exit 1
        fi
    else
        echo ""
        echo "Co-daemon download skipped: ${MOUNT_RELEASE_URL} could not be fetched (${mount_err:-no error reported})."
        echo "Expected while no release tarball exists on github.com/gkrost/unidrive-mount-linux; otherwise the"
        echo "error above says what failed. Until then, build manually:"
        mount_manual_hint
        echo ""
    fi
fi

# Systemd user unit
mkdir -p "${SYSTEMD_DIR}"
for unit in unidrive.service unidrive@.service unidrive-mount@.service; do
    cp "${SCRIPT_DIR}/${unit}" "${SYSTEMD_DIR}/${unit}"
    echo "  ${SYSTEMD_DIR}/${unit}"
done

if command -v systemctl >/dev/null 2>&1; then
    systemctl --user daemon-reload || true
    if [[ ${#ACTIVE_UNITS[@]} -gt 0 ]]; then
        systemctl --user start "${ACTIVE_UNITS[@]}"
        echo "Restarted: ${ACTIVE_UNITS[*]}"
    fi
fi

if [[ ${#STALE_PIDS[@]} -gt 0 ]]; then
    echo ""
    echo "NOTE: still running the previous jar (pid ${STALE_PIDS[*]}); restart it to pick up the new code:"
    echo "  unidrive -p <profile> daemon stop   # then start it again; stop any manual mount first"
fi

echo ""
echo "Done. Usage:"
echo "  unidrive --help                                  # CLI help"
echo "  unidrive auth                                    # authenticate first"
echo "  unidrive autostart                              # start the selected profile's mode"
echo "  systemctl --user enable --now unidrive.service   # auto-start on login (default profile)"
echo "  systemctl --user enable --now unidrive@<profile>.service         # ... or one unit per profile"
echo "  systemctl --user enable --now unidrive-mount@<profile>.service   # FUSE mount, see dist/README.md"
echo "  systemctl --user status unidrive.service         # check daemon status"
echo "  journalctl --user -u unidrive.service -f         # follow logs"
