# `auth check`: prove a profile's stored sign-in against the provider

`unidrive [-p <profile>] auth check [--profile <p>] [--json] [--timeout <seconds>]`

A front-end that has just added an account wants one answer to "does the provider accept this profile's sign-in?",
without a daemon, a scan or a sync, and without any chance of an interactive sign-in. `profile list --json` has
`auth: ok | expired | none`, which is the offline truth (a token is stored and not expired by its own clock); this verb
is the provider's verdict.

## What it does

One authenticated call to the provider, the cheapest it has, with the credentials the profile has stored:

| Provider | The call | Account |
|---|---|---|
| OneDrive | `GET /me/drive` (Graph) | the name kept at sign-in (`account_name`), else the drive owner's display name |
| Internxt | `GET /users/usage` | the name kept at sign-in, else the account e-mail |
| Dropbox | `users/get_space_usage` | the name kept at sign-in |
| S3 | one `ListObjectsV2` of a single key | none |
| localfs | none: the profile has no account | `unsupported` |

It never signs in: no browser, no console, no device code, whatever the profile holds (an unsigned OneDrive profile
is `not_signed_in`, where `unidrive quota` would open a browser). It writes nothing to `state.db`, scans nothing and
starts no daemon. An expired access token is refreshed by the provider's own code as for any request; the refreshed
token is written by the same code that writes it everywhere (under the provider's refresh latch).

## Who makes the call

- **A stopped profile**: the CLI, holding the profile lock for the call (so no daemon or sync can start meanwhile and
  the token file has one writer).
- **A profile held by a running daemon**: the CLI hands the check to the daemon over its authenticated IPC socket
  (`auth.check`, daemon design §4.12), the way `auth login` is handed over (archive#813). The daemon makes the call through its
  own provider instance, which is the one that holds and refreshes the token: there is no second refresher, so no refresh
  write races the daemon's. The CLI itself reads no token and calls no provider.
- A profile held by a sync is `profile_in_use`.

## Reply

Success, exit 0:

```json
{"ok":true,"profile":"work","status":"authenticated","account":"Ada Example"}
```

`account` is absent (never `null`) when the provider does not say it. Refusal, exit 1 (the CLI convention for a
failed command, archive#1126), on stdout with `--json`:

```json
{"ok":false,"error":"credentials_rejected","message":"the provider did not accept the stored sign-in (revoked, expired or another account)"}
```

| `error` | Meaning | A verdict? |
|---|---|---|
| `not_signed_in` | no credentials are stored for the profile | yes |
| `credentials_rejected` | the provider refused them: revoked, expired beyond a refresh, another account | yes |
| `network_error` | the provider could not be asked (no connection, a timeout, a gateway error), or did not answer within `--timeout` | no: unknown |
| `provider_error` | the provider answered, but not usably (an account without a drive, a refusal that is not about the sign-in) | no |
| `unsupported` | the provider has no account to check (localfs) | no |
| `invalid_timeout` | `--timeout` is outside 1 to 300 | no |

The other refusals of the `auth` commands apply unchanged (`profile_not_found`, `config_not_found`, `profile_in_use`,
`daemon_too_old`, ...). Messages name the exception type and, when the provider answered, the HTTP status; they never
quote a response, a header or a token. The output never contains a token or any credential.

## `--timeout`

Seconds to wait for the provider (default 20, 1 to 300). A front-end with its own limit passes a shorter one and gets a
`network_error` token instead of a killed process. Against a running daemon the CLI waits the limit plus five seconds for
the daemon's answer.

## Windows client

The Add-account wizard's credential check may use `auth check --json` instead of reading `profile list --json`: `ok` ends
the check; `not_signed_in` and `credentials_rejected` are the verdicts it warns on; `network_error` and `provider_error`
are "could not confirm" and carry the reason.
