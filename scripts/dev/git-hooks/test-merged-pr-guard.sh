#!/usr/bin/env sh
# test-merged-pr-guard.sh: tests of merged-pr-guard.sh against a stub gh. Exit 0 when every case passes.
#   sh scripts/dev/git-hooks/test-merged-pr-guard.sh
set -u

here=$(cd "$(dirname "$0")" && pwd)
guard="$here/merged-pr-guard.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# The stub answers like `gh pr list --json number,state --jq '.[] | "\(.number) \(.state)"'` from $STUB_PRS, or fails.
cat > "$tmp/gh" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$STUB_LOG"
[ "${STUB_FAIL:-}" = "1" ] && exit 1
printf '%s' "${STUB_PRS:-}"
EOF
chmod +x "$tmp/gh"

sha=1111111111111111111111111111111111111111
zero=0000000000000000000000000000000000000000
url=git@github.com:owner/repo.git
pass=0
fail=0

# check <name> <expected exit> <stdin> [VAR=value ...]
check() {
    name=$1; want=$2; input=$3; shift 3
    : > "$tmp/log"
    printf '%s\n' "$input" | env STUB_LOG="$tmp/log" UNIDRIVE_GH="$tmp/gh" "$@" sh "$guard" origin "$url" > "$tmp/out" 2>&1
    got=$?
    if [ "$got" = "$want" ]; then pass=$((pass + 1)); echo "ok   $name"
    else fail=$((fail + 1)); echo "FAIL $name: exit $got, expected $want"; sed 's/^/     /' "$tmp/out"; fi
}

push() { printf 'refs/heads/%s %s refs/heads/%s %s' "$1" "${2:-$sha}" "$1" "$zero"; }
nl='
'

check "a branch with a merged PR is refused" 1 "$(push fix/a)" STUB_PRS="12 MERGED"
check "a branch with a closed PR is refused" 1 "$(push fix/a)" STUB_PRS="12 CLOSED"
check "a merged PR plus an open one: allowed" 0 "$(push fix/a)" STUB_PRS="12 MERGED${nl}13 OPEN"
check "an open PR: allowed" 0 "$(push fix/a)" STUB_PRS="13 OPEN"
check "a new branch without PR: allowed" 0 "$(push fix/new)" STUB_PRS=""
check "the override lets a merged branch through" 0 "$(push fix/a)" STUB_PRS="12 MERGED" UNIDRIVE_ALLOW_PUSH_TO_MERGED=1
check "gh failing (offline, logged out): allowed" 0 "$(push fix/a)" STUB_FAIL=1
check "gh missing: allowed" 0 "$(push fix/a)" UNIDRIVE_GH="$tmp/no-such-gh"
check "main is never asked about" 0 "$(push main)" STUB_PRS="12 MERGED"
check "a deletion is never asked about" 0 "$(push fix/a "$zero")" STUB_PRS="12 MERGED"
check "a tag is never asked about" 0 "refs/tags/v1 $sha refs/tags/v1 $zero" STUB_PRS="12 MERGED"
check "one refused ref among several refuses the push" 1 "$(push fix/open)${nl}$(push fix/a)" STUB_PRS="12 MERGED"

# The stub must have been asked with the remote's slug and the branch.
: > "$tmp/log"
printf '%s\n' "$(push fix/a)" | env STUB_LOG="$tmp/log" UNIDRIVE_GH="$tmp/gh" STUB_PRS="" sh "$guard" origin "https://github.com/owner/repo.git" >/dev/null 2>&1
if grep -q -- '--repo owner/repo --head fix/a' "$tmp/log"; then pass=$((pass + 1)); echo "ok   an https remote is asked as owner/repo"
else fail=$((fail + 1)); echo "FAIL an https remote is asked as owner/repo: $(cat "$tmp/log")"; fi

: > "$tmp/log"
printf '%s\n' "$(push fix/a)" | env STUB_LOG="$tmp/log" UNIDRIVE_GH="$tmp/gh" STUB_PRS="12 MERGED" sh "$guard" origin "https://gitlab.com/owner/repo.git" >/dev/null 2>&1
code=$?
if [ "$code" = 0 ] && [ ! -s "$tmp/log" ]; then pass=$((pass + 1)); echo "ok   a remote outside github.com is not checked"
else fail=$((fail + 1)); echo "FAIL a remote outside github.com is not checked (exit $code, log: $(cat "$tmp/log"))"; fi

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
