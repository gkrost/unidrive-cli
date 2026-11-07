#!/usr/bin/env sh
# install-git-hooks.sh: install the merged-PR pre-push check (scripts/dev/git-hooks/merged-pr-guard.sh) into this clone.
#
# The installer copies the guard into the clone's hooks folder and writes a pre-push hook that runs that copy. The copy
# matters: hooks are shared by every worktree of a clone, and most branches do not carry the script, so a hook that ran
# the checked-out branch's file would break their pushes. Run the installer again to pick up a newer guard.
# A pre-push hook that was there before (and is not ours) is kept as pre-push.chained and still runs after the guard.
# With core.hooksPath set, git reads hooks from that folder instead: the script installs there and says so, except into a
# folder inside the work tree (tracked hooks are never moved or overwritten; wire the guard into them instead).
#
#   sh scripts/dev/install-git-hooks.sh          (any clone of the repository; also works when run from another checkout)
# Uninstall: delete pre-push and merged-pr-guard.sh from the hooks folder; rename pre-push.chained back to pre-push if present.
set -eu

guard_src="$(cd "$(dirname "$0")" && pwd)/git-hooks/merged-pr-guard.sh"
[ -f "$guard_src" ] || { echo "install-git-hooks: $guard_src not found" >&2; exit 1; }

repo=$(git rev-parse --show-toplevel)
hooks=$(git rev-parse --git-common-dir)/hooks
case "$hooks" in /*|[A-Za-z]:*) ;; *) hooks="$repo/$hooks" ;; esac
custom=$(git config --get core.hooksPath || true)
if [ -n "$custom" ]; then
    case "$custom" in /*|[A-Za-z]:*) hooks="$custom" ;; *) hooks="$repo/$custom" ;; esac
    case "$hooks/" in
        "$repo"/*)
            echo "install-git-hooks: core.hooksPath points into the work tree ($custom): git already runs the tracked hooks there."
            echo "install-git-hooks: nothing installed; make sure that folder's pre-push calls scripts/dev/git-hooks/merged-pr-guard.sh."
            exit 0 ;;
    esac
    echo "core.hooksPath is set: installing into $hooks"
fi
mkdir -p "$hooks"

marker='# unidrive merged-PR pre-push hook'
target="$hooks/pre-push"
if [ -f "$target" ] && ! grep -q "$marker" "$target"; then
    if [ -e "$hooks/pre-push.chained" ]; then
        echo "install-git-hooks: $hooks/pre-push.chained already exists; resolve it by hand first" >&2
        exit 1
    fi
    mv "$target" "$hooks/pre-push.chained"
    echo "kept the existing pre-push hook as pre-push.chained (it runs after the merged-PR check)"
fi

cp "$guard_src" "$hooks/merged-pr-guard.sh"
cat > "$target" <<EOF
#!/usr/bin/env sh
$marker (installed by scripts/dev/install-git-hooks.sh; reinstall to update)
# 1. merged-pr-guard.sh (copied next to this file): refuse a push to a branch whose PR is merged or closed.
# 2. pre-push.chained, the hook that was here before, if any. Both get git's arguments and the same stdin.
hooks=\$(dirname "\$0")
input=\$(cat)
printf '%s\n' "\$input" | sh "\$hooks/merged-pr-guard.sh" "\$@" || exit 1
if [ -x "\$hooks/pre-push.chained" ]; then
    printf '%s\n' "\$input" | "\$hooks/pre-push.chained" "\$@" || exit 1
fi
exit 0
EOF
chmod +x "$target"
echo "installed: $target (merged-PR check, guard copied to $hooks/merged-pr-guard.sh)"
