#!/usr/bin/env bash
# test-merge-loss-scan.sh: regression tests of merge-loss-scan.sh's temporary refs. Exit 0 when every case passes.
#   bash scripts/dev/test-merge-loss-scan.sh
#
# The scan fetches the PR heads into a per-run namespace it allocates. A clone may legitimately track a remote named
# origin-pr, so its refs must survive the scan untouched — both when the PR-heads fetch succeeds and when it fails —
# and no ref of the scan's own namespace may be left behind afterwards.
set -u

here=$(cd "$(dirname "$0")" && pwd)
scan="$here/merge-loss-scan.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0
ok() { pass=$((pass + 1)); echo "ok   $1"; }
bad() { fail=$((fail + 1)); echo "FAIL $1: $2"; }

git init -q --bare -b main "$tmp/origin.git"
git init -q --bare -b main "$tmp/origin-pr.git"
git init -q -b main "$tmp/seed"
cd "$tmp/seed" || exit 1
git config user.email t@example.com
git config user.name t
echo base > base.txt
git add .
git commit -qm base
git remote add origin "$tmp/origin.git"
git push -q origin HEAD:refs/heads/main

# A remote named origin-pr with refs the clone keeps: exactly the namespace the scan must never touch.
echo r > retained.txt
git add .
git commit -qm "a commit origin-pr tracks"
git remote add origin-pr "$tmp/origin-pr.git"
git push -q origin-pr HEAD:refs/heads/retained
git push -q origin-pr HEAD:refs/heads/other
git fetch -q origin-pr
retained_oid=$(git rev-parse refs/remotes/origin-pr/retained)
other_oid=$(git rev-parse refs/remotes/origin-pr/other)

# A local branch whose tip is on no remote ref: part (a) reports it, so the test also sees the scan work end to end.
git checkout -qb fix/local
echo l > local.txt
git add .
git commit -qm local-only

# A git shim that fails only the PR-heads fetch while a marker file exists, so the failed-fetch case is deterministic.
real_git=$(command -v git)
mkdir -p "$tmp/bin"
cat > "$tmp/bin/git" <<EOF
#!/usr/bin/env sh
if [ -f "$tmp/failfetch" ]; then
    for a in "\$@"; do
        case "\$a" in *refs/pull*) exit 101 ;; esac
    done
fi
exec "$real_git" "\$@"
EOF
chmod +x "$tmp/bin/git"

run() { (cd "$tmp/seed" && PATH="$tmp/bin:$PATH" bash "$scan" --since 2026-01-01); }
leftover() { "$real_git" -C "$tmp/seed" for-each-ref --format='%(refname)' 'refs/remotes/unidrive-pr-scan-*'; }

rm -f "$tmp/failfetch"
out=$(run 2>&1)
if [ $? = 0 ] && [ "$(git rev-parse refs/remotes/origin-pr/retained)" = "$retained_oid" ] \
    && [ "$(git rev-parse refs/remotes/origin-pr/other)" = "$other_oid" ] \
    && [ -z "$(leftover)" ] && ! grep -q 'PR heads not fetched' <<< "$out" \
    && grep -q 'summary: 1 local commit(s) in no PR, 0 merged PR(s) with later pushes' <<< "$out"; then
    ok "a successful scan leaves the origin-pr refs and OIDs alone, cleans its namespace, still reports (a)"
else
    bad "a successful scan leaves the origin-pr refs and OIDs alone, cleans its namespace, still reports (a)" "$out"
fi

: > "$tmp/failfetch"
out=$(run 2>&1)
if [ $? = 0 ] && [ "$(git rev-parse refs/remotes/origin-pr/retained)" = "$retained_oid" ] \
    && [ "$(git rev-parse refs/remotes/origin-pr/other)" = "$other_oid" ] \
    && [ -z "$(leftover)" ] && grep -q 'could not fetch the PR heads' <<< "$out" \
    && grep -q '(PR heads not fetched)' <<< "$out"; then
    ok "a failed PR-heads fetch keeps the origin-pr refs and OIDs, cleans the namespace, says so"
else
    bad "a failed PR-heads fetch keeps the origin-pr refs and OIDs, cleans the namespace, says so" "$out"
fi

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
