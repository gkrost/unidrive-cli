#!/usr/bin/env sh
# test-install-git-hooks.sh: real `git push` tests of the installed pre-push hook. Exit 0 when every case passes.
#   sh scripts/dev/test-install-git-hooks.sh
#
# The installed hook is shared by every worktree of a clone, while the guard it runs is the copy next to it, so pushes
# must also work from a worktree whose checked-out branch does not carry scripts/dev/git-hooks/ at all — and the hook
# that was there before the install must still run behind the guard, on the same stdin.
set -u

here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0
ok() { pass=$((pass + 1)); echo "ok   $1"; }
bad() { fail=$((fail + 1)); echo "FAIL $1: $2"; }

git init -q --bare -b main "$tmp/origin.git"
git init -q -b main "$tmp/seed"
git -C "$tmp/seed" config user.email t@example.com
git -C "$tmp/seed" config user.name t
echo base > "$tmp/seed/base.txt"
git -C "$tmp/seed" add .
git -C "$tmp/seed" commit -qm base
git -C "$tmp/seed" remote add origin "$tmp/origin.git"
git -C "$tmp/seed" push -q origin HEAD:refs/heads/main

# The clone the hooks are installed into. Its pre-existing pre-push appends to a marker file, so the test can tell
# whether the preserved hook ran and with what stdin.
git clone -q "$tmp/origin.git" "$tmp/cloneA"
git -C "$tmp/cloneA" config user.email t@example.com
git -C "$tmp/cloneA" config user.name t
cat > "$tmp/chained-hook" <<'EOF'
#!/usr/bin/env sh
printf 'chained %s | %s\n' "$1" "$(cat)" >> "$CHAINED_LOG"
EOF
mkdir -p "$tmp/cloneA/.git/hooks"
cp "$tmp/chained-hook" "$tmp/cloneA/.git/hooks/pre-push"
chmod +x "$tmp/cloneA/.git/hooks/pre-push"
: > "$tmp/chained.log"
# The installing checkout carries the scripts, the way a branch of this repository does; the installer runs the copy on
# that branch, not the harness checkout's.
git -C "$tmp/cloneA" checkout -qb fix/current
mkdir -p "$tmp/cloneA/scripts/dev"
cp -r "$here/git-hooks" "$tmp/cloneA/scripts/dev/git-hooks"
cp "$here/install-git-hooks.sh" "$tmp/cloneA/scripts/dev/install-git-hooks.sh"
git -C "$tmp/cloneA" add scripts
git -C "$tmp/cloneA" commit -qm "a current-branch commit carrying the scripts"

(cd "$tmp/cloneA" && sh scripts/dev/install-git-hooks.sh) > "$tmp/install.out" 2>&1 || bad "the installer exits 0" "$(cat "$tmp/install.out")"
grep -q 'unidrive merged-PR pre-push hook' "$tmp/cloneA/.git/hooks/pre-push" \
    && grep -q 'merged-pr-guard.sh' "$tmp/cloneA/.git/hooks/pre-push" \
    && [ -f "$tmp/cloneA/.git/hooks/merged-pr-guard.sh" ] \
    && [ -x "$tmp/cloneA/.git/hooks/pre-push.chained" ] \
    && ok "the install wrote the shim, copied the guard, kept the old hook as pre-push.chained" \
    || bad "the install wrote the shim, copied the guard, kept the old hook as pre-push.chained" "$(cat "$tmp/install.out")"

# A worktree on the bare origin's commit: its tree carries no scripts/ at all — the state of every branch created before
# this PR's scripts. The hook must still run the guard from the shared copy, and the preserved hook behind it.
git -C "$tmp/cloneA" worktree add -q "$tmp/oldwt" -b fix/old origin/main
[ ! -e "$tmp/oldwt/scripts" ] || bad "the older worktree carries no scripts" "scripts exists in $tmp/oldwt"

export CHAINED_LOG="$tmp/chained.log"
if git -C "$tmp/cloneA" push -q origin fix/current:refs/heads/fix/current > "$tmp/push-current.out" 2>&1; then
    ok "a push from the installing checkout succeeds"
else
    bad "a push from the installing checkout succeeds" "$(cat "$tmp/push-current.out")"
fi
if git -C "$tmp/oldwt" push -q origin HEAD:refs/heads/fix/old > "$tmp/push-old.out" 2>&1; then
    ok "a push from an older worktree without the scripts succeeds"
else
    bad "a push from an older worktree without the scripts succeeds" "$(cat "$tmp/push-old.out")"
fi
lines=$(wc -l < "$tmp/chained.log")
if [ "$lines" = 2 ] && grep -q 'chained .* refs/heads/fix/current' "$tmp/chained.log" \
    && grep -q 'chained .* refs/heads/fix/old' "$tmp/chained.log"; then
    ok "the preserved hook ran after the guard on both pushes, with git's arguments and stdin"
else
    bad "the preserved hook ran after the guard on both pushes, with git's arguments and stdin" "$(cat "$tmp/chained.log")"
fi

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
