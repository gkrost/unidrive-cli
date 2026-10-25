# Starting the public history at the release commit

Plan and checklist for the step that makes this repository public with a history that does
not contain the personal data of the project's development. **This document is a plan: it
rewrites no history, force-pushes nothing and creates no tag.** Every step marked
*owner* is done by the owner, by hand.

## What the approach is

This repository was created as a new repository whose first commit is the tree of the
engine's main branch at that time (no history carried over). The development repository
it came from was renamed, made private and archived; its issues and pull requests stay
there and are cited in code and documents as `archive#N`. That is the only approach whose
result can be checked at once and that needs no request to GitHub Support.

An orphan squash *inside* the old repository would not have done it, and must never be
used as a shortcut:

- every pull request keeps `refs/pull/<n>/head` and a merge ref pointing at its commits,
  and these refs cannot be deleted from the repository side;
- a commit that no branch reaches is still served by its SHA until GitHub collects it,
  which a push does not trigger;
- forks and local clones keep their own full copies.

## What has to be gone (and where it can hide)

| Place | What to look for | How it gets there |
|---|---|---|
| Tree at HEAD | absolute paths of a developer's machine, user and host names, account e-mail addresses, SIDs, real file or folder names from a test drive, tokens and keys | fixtures, docs, test output pasted into comments |
| Commit metadata | author and committer e-mail addresses other than the project's noreply address, `Co-Authored-By` trailers with personal addresses, messages naming private repositories or issues | local git identity |
| Issues, comments, pull requests, titles, labels, milestones | the same values as above, log excerpts with paths, drive file names, private-repository issue links | issues copied or transferred from the archive; log lines pasted into bodies |
| Issue and comment edit history | an earlier revision of a body that held such a value | editing a body does not remove the old revision; GitHub offers no API to delete one |
| Releases and assets, Actions logs and artifacts, Pages, wiki, packages | build logs with paths, debug archives | CI runs, hand-attached files |
| Settings | description, topics, homepage, Dependabot and CodeQL alerts that quote code | repository creation |
| The old repository | still reachable by its old name or by a redirect | not archived, not private |

The tree is expected to hold only intended public data: the owner's name in `LICENSE` and
`NOTICE`, the project addresses used for security reports and releases, documentation
example values (RFC 5737/1918 addresses, `@example.*`, AWS's published example key).

## How to verify

Keep the list of values to search for **outside the repository** (a local file, one
pattern per line: real user name, host names, account e-mail addresses, SID prefix,
absolute-path prefixes, the names of private repositories, file names of the owner's
drive). The repository must never contain that list. Below it is called `patterns.txt`.

1. **A fresh mirror clone has one root and no foreign refs.**
   ```bash
   git clone --mirror https://github.com/<owner>/<repo>.git check.git && cd check.git
   git rev-list --max-parents=0 --all          # exactly one root commit
   git for-each-ref                             # only refs/heads/*, refs/tags/* and this repository's own refs/pull/*
   ```
   Pull-request refs of *this* repository's own pull requests are expected and fine; the
   check is that no ref points into history older than the root commit (the first command).
2. **Tree, all commits, all authors.**
   ```bash
   git grep -n -I -F -f patterns.txt $(git rev-list --all) | head
   git log --all --format='%an <%ae>%n%cn <%ce>%n%B' | grep -n -F -f patterns.txt
   git log --all --format='%ae%n%ce' | sort -u      # only noreply addresses
   gitleaks detect --source . --log-opts="--all" --config .gitleaks.toml
   ```
3. **Issues, comments and pull requests** (bodies and titles, every state):
   ```bash
   gh issue list --state all --limit 1000 --json number,title,body,comments > issues.json
   gh pr list --state all --limit 1000 --json number,title,body,comments,reviews > prs.json
   grep -n -F -f patterns.txt issues.json prs.json
   ```
   A hit is edited by hand. Then open the item's *edited* history and delete every
   earlier revision that held the value (*owner*, GitHub UI only).
4. **Releases, Actions, Pages, wiki, packages.**
   ```bash
   gh release list
   gh api repos/<owner>/<repo>/actions/artifacts --jq '.artifacts[].name'
   gh api repos/<owner>/<repo> --jq '{description,homepage,topics,has_wiki,has_pages,forks_count}'
   ```
   Delete what holds a hit. Do not download an artifact to inspect it on a machine that is
   not the owner's.
5. **The old repository** answers `gh repo view <owner>/<old-name> --json isPrivate,isArchived`
   with private and archived, and has no forks (`forks_count` 0).

A search engine or code-search index may still hold earlier public copies of the old
repository; that is outside what a repository operation can remove. If a verification
finds a *secret*, rotate it; do not rely on the purge.

## Steps for the owner

Done once, in this order. A box that is already true is ticked after its check.

- [ ] **Freeze.** Nothing merges in the old repository except what the release needs.
- [ ] **Re-export, do not squash.** Everything merged in the old repository after the
      start of this one is carried here as ordinary commits (re-applied diffs, one per
      change), not as one squashed commit, and with the noreply identity (check
      `git config user.email` on the machine that commits).
- [ ] **Old repository.** Rename it (for example to an `-archive` name), make it private,
      archive it. Check: step 5 above.
- [ ] **Settings of this repository.** Branch protection and the required check
      (`./gradlew check` is the only gate; the security scan stays advisory), secret
      scanning and push protection, CodeQL, Dependabot (restore the configuration file
      that was left out while the repository was private), release signing secrets, the
      automatic reviewer, Pages and site links. Turn Actions on once the repository is
      public (private repositories have no Actions minutes on this account).
- [ ] **Run the verification** (steps 1 to 5) on the public repository *before*
      announcing it, and again after the first release.
- [ ] **Issues.** The tracker here starts with fresh numbers; migrated issues carry a
      "moved from archive#N" line. Run step 3, edit hits, delete old revisions (UI).
- [ ] **References.** Point the Windows client's build, every sibling repository's
      documents and every local clone and worktree at the new remote; the old remote's
      push URL in a clone is disabled so a stray push cannot reach the archive.
- [ ] **Tag the release** on this repository after the checks (*owner*; never an agent).
- [ ] **Secrets.** Any token or key that ever appeared in the development repository's
      history is rotated now, whether or not a scan found it.

## Residual risks

- Forks of the old repository made while it was public keep their copy. Check the fork
  list before making it private; GitHub detaches the forks of a repository made private.
- Clones and mirrors on other machines (CI caches, backups, agent worktrees) keep the old
  history until deleted.
- The migrated issues are a copy of the archive's text: after the tree they are the
  largest remaining surface, and the only one that needs a content pass instead of a
  mechanical check.
