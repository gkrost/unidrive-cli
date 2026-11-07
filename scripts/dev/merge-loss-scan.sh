#!/usr/bin/env bash
# merge-loss-scan.sh: find work that is in no pull request and never reached main (the audit of 2026-11-07 as a script).
#
#   scripts/dev/merge-loss-scan.sh [--since YYYY-MM-DD]     (default: 14 days ago)
#
# Reports:
#   (a) local branches with non-merge commits that are on no remote ref and in no PR's head (refs/pull/*/head is fetched
#       for the check, so a commit that lives only in a PR counts as pushed);
#   (b) merged PRs whose branch on GitHub moved after the merge (commits pushed after the merge reach no PR and never main).
# A hit is a candidate, not a proven loss: the work may have reached main another way (a later PR, a cherry-pick). Check
# each one's content against origin/main before re-filing it.
#
# Read-only apart from a unique temporary namespace under refs/remotes/ (unidrive-pr-scan-…), which it verifies unused
# before it fetches and deletes again afterwards: nothing already in refs/remotes/ is touched, so a clone may track a
# remote named origin-pr in peace. Needs gh (logged in) for (b). Always exits 0.
set -u

since=$(date -d '14 days ago' +%F 2>/dev/null || date -v-14d +%F 2>/dev/null || echo 1970-01-01)
if [ "${1:-}" = "--since" ] && [ -n "${2:-}" ]; then since=$2; fi
gh=${UNIDRIVE_GH:-gh}

repo=$(git rev-parse --show-toplevel) || exit 0
cd "$repo" || exit 0
url=$(git remote get-url origin 2>/dev/null || true)
slug=$(printf '%s\n' "$url" | sed -n -e 's#^git@github\.com:\(.*\)$#\1#p' -e 's#^https://\([^@/]*@\)\{0,1\}github\.com/\(.*\)$#\2#p' \
    | sed -e 's#\.git$##' -e 's#/$##')

echo "merge-loss scan of $slug ($repo), since $since"
git fetch -q --prune origin 2>/dev/null || echo "  (fetch failed; using the refs already here)"

# The PR heads go into a per-run namespace under refs/remotes/, allocated until one is unused: a fixed name could sit on
# top of legitimate remote-tracking refs (a remote named origin-pr), and the cleanup below deletes the whole namespace,
# so anything it had not fetched itself would be lost. The namespace is under refs/remotes/ so that (a)'s
# `--not --remotes` keeps treating commits that live only in a PR as pushed. The cleanup is registered before the
# fetch, so even a scan killed mid-fetch leaves nothing behind.
scan_ns=
cleanup_pr_refs() {
    [ -n "$scan_ns" ] || return 0
    git for-each-ref --format='delete %(refname)' "refs/remotes/$scan_ns" | git update-ref --stdin 2>/dev/null || :
}
pr_refs_ok=1
tries=0
while :; do
    scan_ns="unidrive-pr-scan-$(date +%s)-$$-$((RANDOM % 10000))"
    if [ -z "$(git for-each-ref --format='%(refname)' "refs/remotes/$scan_ns")" ]; then break; fi
    tries=$((tries + 1))
    if [ "$tries" -ge 10 ]; then
        scan_ns=
        pr_refs_ok=0
        echo "  (no free temporary namespace for the PR heads: (a) may over-report)"
        break
    fi
done
if [ -n "$scan_ns" ]; then
    trap cleanup_pr_refs EXIT
    git fetch -q origin "+refs/pull/*/head:refs/remotes/$scan_ns/*" 2>/dev/null || { pr_refs_ok=0; echo "  (could not fetch the PR heads: (a) may over-report)"; }
fi

echo
echo "(a) local commits on no remote ref and in no PR:"
a=0
while read -r branch date; do
    [[ "$date" < "$since" ]] && continue
    for c in $(git rev-list "$branch" --not --remotes 2>/dev/null); do
        [ "$(git rev-list --parents -n1 "$c" | wc -w)" -gt 2 ] && continue   # a merge commit: its sides are checked on their own
        echo "  $date $branch $(git log -1 --format='%h %s' "$c" | cut -c1-110)"
        a=$((a + 1))
    done
done < <(git for-each-ref --format='%(refname:short) %(committerdate:short)' refs/heads)
[ $a -eq 0 ] && echo "  none"

echo
echo "(b) merged PRs whose branch moved after the merge:"
b=0
if [ -z "$slug" ] || ! command -v "$gh" >/dev/null 2>&1; then
    echo "  skipped (no github.com origin, or gh not found)"
else
    branches=$("$gh" api "repos/$slug/branches?per_page=100" --paginate --jq '.[] | "\(.name) \(.commit.sha)"' 2>/dev/null) || branches=
    merged=$("$gh" pr list --repo "$slug" --state merged --limit 1000 --search "merged:>=$since" \
        --json number,headRefName,headRefOid --jq '.[] | "\(.number) \(.headRefName) \(.headRefOid)"' 2>/dev/null) || merged=
    if [ -z "$merged" ]; then
        echo "  no merged PRs since $since (or gh could not list them)"
    else
        while read -r number head_branch head_sha; do
            tip=$(printf '%s\n' "$branches" | awk -v b="$head_branch" '$1 == b {print $2}')
            [ -n "$tip" ] && [ "$tip" != "$head_sha" ] || continue
            ahead=$(git rev-list --count "$head_sha..$tip" 2>/dev/null || echo '?')
            echo "  #$number $head_branch: merged at ${head_sha:0:8}, the branch is now ${tip:0:8} ($ahead commit(s) after the merge)"
            b=$((b + 1))
        done <<< "$merged"
        [ $b -eq 0 ] && echo "  none"
    fi
fi

echo
echo "summary: $a local commit(s) in no PR, $b merged PR(s) with later pushes$([ $pr_refs_ok -eq 1 ] || echo ' (PR heads not fetched)')"
exit 0
