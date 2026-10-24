# Account name in the sign-in reply and the profile list

A front-end needs to tell the user which account a sign-in ended up on (a device-code sign-in uses
whichever account the browser session holds). The engine therefore reports the account's name.

## Replies

- `auth complete --json`, status `authenticated`, and `auth login --json` carry `"account": "<name>"`
  when the provider can say it. The key is absent otherwise; it is never `null` in these replies.
  A `pending` reply has none.
- `profile list --json` has `"account"` on every profile, next to `auth` (which stays a state word).
  It is `null` when no name is known: a provider without an account, or a profile signed in before the
  name was kept (it appears after the next sign-in).

The reply carries the name only: no token, user id or tenant.

## Sources

| Provider | Name |
|----------|------|
| OneDrive | `GET /me/drive` `owner.user.displayName` (`/me` is 403 for the app's file-only scopes) |
| Internxt | the e-mail of the login |
| Others   | none |

## Storage

The name is read once, at sign-in, and stored in the profile folder as `account_name` (next to the
credentials, owner-only like them). `profile list` reads that file; it makes no network call. A sign-in
whose lookup fails stores no name and removes an earlier one, so a list never shows the previous
account. `auth logout` and `profile remove` delete the file with the credentials. A failed lookup never
fails the sign-in.

## Account owner

The name is not an identity: two accounts of one person (a personal and a work Microsoft account)
usually have the same display name, and an Internxt user can change the e-mail. A profile therefore
also keeps the stable id of the user its sync state belongs to, as `account_id` in the profile folder.

| Provider | Id | Read from |
|----------|----|-----------|
| OneDrive | the drive owner id | `/me/drive` `owner.user.id`, with the token just issued |
| Internxt | the user uuid | `user.uuid` of the `/auth/login/access` answer |
| Dropbox  | the account id | `account_id` of the token answer |
| Others   | none (S3, SFTP, WebDAV, local folders have no user): never checked | |

Every sign-in (`auth complete`, `auth login`, the daemon's `auth.complete` / `auth.login`, and the
console `unidrive auth` browser and device-code flows) reads the id **before** it stores the
credentials and compares it with the stored one:

- same user (compared without case): stored as before;
- no stored id (a new profile, or one signed in before ids were kept): stored, and the id is recorded;
- another user: refused with `account_changed` (exit code 1, `{"ok":false,"error":"account_changed",...}`);
- a stored id, but the provider did not say who signed in (OneDrive `/me/drive` failed or names no
  owner user, a login answer without a uuid): refused with `account_unverified`.

A refusal stores nothing: the old credentials, `account_name` and `account_id` stay byte for byte, a
running daemon reloads nothing. The reply names no user and no id.

`account_id` belongs to the state, not to the credentials: `logout` (with or without `--keep-state`)
keeps it, so the next sign-in is still compared; `logout --delete-state` and `profile remove` (without
`--keep-data`) delete it together with `state.db`. That is also the way to sign a profile in to another
user on purpose: `logout --delete-state` (refused while the daemon runs), then sign in. A front-end
offers "add it as a new account" instead. A recorded id is never replaced by a sign-in.

### A profile signed in before ids were kept

A profile that has stored credentials but no `account_id` (signed in before ids were kept) would accept
the next sign-in as whoever signs in. So the id is also recorded **from the credentials it already runs
with**, without a sign-in:

- when a daemon starts (after it authenticated, off the start's path) and once per `unidrive sync` run
  (not in a dry run), if the profile has no `account_id`, ONE identity call is made with the current
  credentials: OneDrive `/me/drive` `owner.user.id`; Dropbox `users/get_current_account` `account_id` (the same sources as
  their sign-ins, so the id is **proven** as recorded). Internxt makes no call: it reads the uuid claim of the
  stored token (`payload.uuid`, else `uuid`); nothing is refreshed, rotated or written to the credentials. A
  token without the claim records nothing (one INFO line). S3, SFTP, WebDAV and local folders skip.
- the call is bounded (10 s). A failed, slow or empty call logs one INFO line (the exception class only) and
  records nothing; the daemon and the run go on and the next start tries again. It is never made when the
  credentials were rejected (the provider is not signed in) or when an id is already stored.
- a stored id is never overwritten by this recording; it does not sign in and writes no tokens.
- success logs `account id recorded (ref <8 hex of a hash>)`, never the id.

**Unproven ids (Internxt).** The sign-in compares the login answer's `user.uuid`; start-up has only the token's
claim, which may not be that id. An id recorded from the claim is therefore **unproven**: the file
`account_id_unproven` sits next to `account_id`. At a sign-in:

1. proven id: `user.uuid` is compared as for every provider;
2. unproven id: the NEW token's claim is compared with the stored id (the same source on both sides): equal is
   the same user and `user.uuid` replaces the id as the proven one (the mark goes); different is
   `account_changed`; a token without the claim is `account_unverified`; nothing is stored on a refusal;
3. a claim that disagrees with `user.uuid` never refuses a sign-in: one WARN, `user.uuid` is stored.

`logout --delete-state` and `profile remove` delete the mark with the id.

## Privacy

The name is personal data. It is never written to the log (at any level) or to the audit log, and
is not part of `doctor` output. The same holds for `account_id`. Tests capture log output at DEBUG during a sign-in and assert the name
is absent.

## Contract fixtures

`core/app/cli/src/test/resources/ipc-contract/cli/auth.complete.ndjson` (with the field) and
`auth.complete-no-account.ndjson` (without): an argv line and the reply line.
