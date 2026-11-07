#!/usr/bin/env sh
# merged-pr-guard.sh: refuse a push to a branch whose pull request is already merged or closed.
#
# Why: GitHub keeps a merged PR's head fixed. A commit pushed to that branch afterwards reaches no PR and never main; it is
# lost unless someone notices and re-files it. That happened three times between 2026-10-20 and 2026-11-03 in a sibling
# repository, each caught and recovered by hand.
#
# Called by the pre-push hook with git's pre-push arguments and stdin:
#   $1 = remote name, $2 = remote URL, stdin = "<local ref> <local sha> <remote ref> <remote sha>" per ref.
# For every push to refs/heads/<branch> (not main or master, not a deletion) it asks GitHub for the PRs whose head is
# <branch>. A branch with a MERGED or CLOSED PR and no OPEN one is refused.
#
# Fails OPEN: without gh, without a login, offline, or for a remote that is not on github.com it warns and allows the push.
# A push that must go through anyway (rare: a branch kept on purpose after its merge): UNIDRIVE_ALLOW_PUSH_TO_MERGED=1.
# UNIDRIVE_GH names the gh executable (the tests point it at a stub).
set -u

remote_url=${2:-}
gh=${UNIDRIVE_GH:-gh}
zero=0000000000000000000000000000000000000000

# owner/repo of a github.com remote, empty for anything else.
slug=$(printf '%s\n' "$remote_url" | sed -n \
    -e 's#^git@github\.com:\([^/]*/[^/]*\)$#\1#p' \
    -e 's#^ssh://git@github\.com/\([^/]*/[^/]*\)$#\1#p' \
    -e 's#^https://\([^@/]*@\)\{0,1\}github\.com/\([^/]*/[^/]*\)$#\2#p' | sed -e 's#\.git$##' -e 's#/$##')

refused=0
while read -r local_ref local_sha remote_ref remote_sha; do
    [ -n "${remote_ref:-}" ] || continue
    [ "$local_sha" = "$zero" ] && continue                    # a deletion: nothing is pushed
    case "$remote_ref" in refs/heads/*) ;; *) continue ;; esac  # tags and other refs
    branch=${remote_ref#refs/heads/}
    case "$branch" in main|master) continue ;; esac
    if [ -z "$slug" ]; then
        continue                                              # not a github.com remote: nothing to ask
    fi
    if ! command -v "$gh" >/dev/null 2>&1; then
        echo "pre-push: merged-PR check skipped for '$branch': '$gh' not found (the push is allowed)" >&2
        continue
    fi
    if ! prs=$("$gh" pr list --repo "$slug" --head "$branch" --state all --json number,state \
            --jq '.[] | "\(.number) \(.state)"' 2>/dev/null); then
        echo "pre-push: merged-PR check skipped for '$branch': gh could not list its PRs (offline or not logged in; the push is allowed)" >&2
        continue
    fi
    if printf '%s\n' "$prs" | grep -q ' OPEN$'; then
        continue                                              # an open PR takes the push
    fi
    done_prs=$(printf '%s\n' "$prs" | awk '$2=="MERGED"||$2=="CLOSED" {printf "%s#%s (%s)", sep, $1, tolower($2); sep=", "}')
    [ -n "$done_prs" ] || continue                            # no PR at all: a new branch
    if [ "${UNIDRIVE_ALLOW_PUSH_TO_MERGED:-}" = "1" ]; then
        echo "pre-push: '$branch' belongs to PR $done_prs; UNIDRIVE_ALLOW_PUSH_TO_MERGED=1, pushing anyway" >&2
        continue
    fi
    echo "pre-push: refused: '$branch' belongs to PR $done_prs." >&2
    echo "pre-push: a merged or closed PR does not take new commits, so this push would never reach main." >&2
    echo "pre-push: open a new PR from origin/main instead (a new branch, then cherry-pick the commits)." >&2
    refused=1
done

exit $refused
