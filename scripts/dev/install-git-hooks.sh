#!/usr/bin/env sh
# install-git-hooks.sh: install the repository's pre-push hook (scripts/dev/git-hooks/pre-push) into this clone.
#
# The installed file is a one-line shim that runs the tracked hook, so a later change to the hook needs no reinstall.
# A pre-push hook that was there before (and is not this shim) is kept as pre-push.chained and still runs after the guard.
# Hooks are shared by all worktrees of a clone: installing once covers them all.
# With core.hooksPath set, git reads hooks from that folder instead: the script installs there and says so.
#
# Uninstall: delete the pre-push shim; rename pre-push.chained back to pre-push if there is one.
set -eu

repo=$(git rev-parse --show-toplevel)
hooks=$(git rev-parse --git-path hooks)
case "$hooks" in /*|[A-Za-z]:*) ;; *) hooks="$repo/$hooks" ;; esac
custom=$(git config --get core.hooksPath || true)
if [ -n "$custom" ]; then
    case "$custom" in /*|[A-Za-z]:*) hooks="$custom" ;; *) hooks="$repo/$custom" ;; esac
    # A hooks folder inside the work tree holds tracked hooks: never move or overwrite them. Their pre-push calls the
    # merged-PR check itself where the repository wires it (scripts/hooks/pre-push in the engine).
    case "$hooks/" in
        "$repo"/*)
            echo "install-git-hooks: core.hooksPath points into the work tree ($custom): git already runs the tracked hooks there."
            echo "install-git-hooks: nothing installed; make sure that folder's pre-push calls scripts/dev/git-hooks/merged-pr-guard.sh."
            exit 0 ;;
    esac
    echo "core.hooksPath is set: installing into $hooks"
fi
mkdir -p "$hooks"

marker='# unidrive pre-push shim'
target="$hooks/pre-push"
if [ -f "$target" ] && ! grep -q "$marker" "$target"; then
    if [ -e "$hooks/pre-push.chained" ]; then
        echo "install-git-hooks: $hooks/pre-push.chained already exists; resolve it by hand first" >&2
        exit 1
    fi
    mv "$target" "$hooks/pre-push.chained"
    echo "kept the existing pre-push hook as pre-push.chained (it runs after the merged-PR check)"
fi

cat > "$target" <<EOF
#!/usr/bin/env sh
$marker
exec sh "\$(git rev-parse --show-toplevel)/scripts/dev/git-hooks/pre-push" "\$@"
EOF
chmod +x "$target"
echo "installed: $target -> scripts/dev/git-hooks/pre-push (merged-PR check)"
