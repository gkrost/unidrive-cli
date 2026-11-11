# S3 provider

A bucket on Amazon S3 or an S3-compatible service (Scaleway, Hetzner, Backblaze B2, Wasabi, OVH, MinIO).

The provider talks to the S3 REST API path-style (`<endpoint>/<bucket>/<key>`) over Ktor, through `S3Api`, and does without the AWS SDK. `SigV4Signer` signs every request. It was written from AWS's published Signature Version 4 algorithm, and its tests are AWS's published worked examples. Behaviour notes taken from Cyberduck are behaviour only. Cyberduck is GPL-3.0, and no code was taken from it.

## Profile

```toml
[providers.s3]
type = "s3"
sync_root = "~/S3"
bucket = "my-bucket"
region = "fr-par"                       # default us-east-1
endpoint = "https://s3.fr-par.scw.cloud" # default: AWS in that region
access_key_id = "..."
secret_access_key = "..."
# s3_create_from_listing = false         # see "Creates on a vendor without If-None-Match" below
```

`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `S3_BUCKET` fill the same keys from the environment.

`unidrive profile add --type s3 --mode mount --option bucket=... --json` creates the profile without a terminal: the key pair is taken from the environment (and not written to `config.toml`), or the secret is piped in with `--secret-stdin`. The endpoint is the regional one (`https://s3.fr-par.scw.cloud`), never one that begins with the bucket name.

## Vendors

What differs between vendors is read off the endpoint's host (`S3Config.Vendor`):

| Host | Parts per multipart upload | Conditional write |
|---|---|---|
| `*.amazonaws.com` | 10,000 | `If-Match` on PUT and CompleteMultipartUpload: a 412 is a conflict |
| `*.scw.cloud` | 1,000 (Scaleway's documented limit) | a HEAD comparison first |
| any other | 10,000 | a HEAD comparison first |

### Creates on a vendor without `If-None-Match`

A create (a new key, nothing to replace) carries `If-None-Match: *`, so a key that is taken by now answers 412 and is a conflict. Where the vendor refuses the header (a 501, or a 400 that names it), the provider checks that the key is free with a HEAD before the write and stops sending the header for the rest of the process. That is the default, and it holds for every caller, including a mount's direct upload, which has no listing behind it. The HEAD narrows the window in which another writer's object can be replaced; it does not close it.

`s3_create_from_listing = true` (default `false`, gkrost/unidrive-cli#573) leaves that HEAD out for such a vendor and trusts the sync's own listing that the key was free. That is one request fewer per object (PUT, HEAD instead of HEAD, PUT, HEAD). The trade-off is a wider window: an object that someone else put there after the sync's listing is replaced, and so is one a direct upload never looked up. Turn it on only for a bucket nothing else writes to. A vendor that honours `If-None-Match` keeps its atomic guard whichever way the key is set.

## API surface used

| Request | Used by | Notes |
|---|---|---|
| `GET ?list-type=2` (ListObjectsV2) | `delta`, `listChildren`, folder checks | `delta` lists the whole bucket (`deltaIsFullListing`), and the engine finds deletions by absence. A listing cut off half-way resumes with `start-after` the last key the scan context kept. `listChildren` uses the `/` delimiter. |
| `HEAD` | `getMetadata`, the write guard, after every upload, `move` checks every key it writes | A taken key is `RemoteConflictException`, never an overwrite; a key already holding the source's bytes (the ETag a copy leaves) is an interrupted attempt and the move may go over it again. The folder leg reads the destination with one ListObjectsV2 instead of a HEAD per key. |
| `GET` | `download` | One GET per object, no HEAD before it. The bytes go to a staging sibling, moved over the target only when complete, and are checked against the GET's own `Content-Length`. The request has no request-level cap; connect and read-idle timeouts watch the wait for the headers and any stall, and the body is capped from the declared size (at least 10 minutes, longer at 50 KiB/s). A 404 is `PermanentDownloadFailureException`. |
| `PUT` | `upload` up to 100 MiB, folder markers (`key/`) | `x-amz-meta-mtime` carries the file's mtime as rclone writes it (seconds since the epoch, with a fraction). An upload carries `Content-MD5`, taken in the same read of the file that makes its SHA-256 proof, so S3 refuses (400 `BadDigest`, nothing stored) bytes that are not the ones that were read: a file changed since, or a body damaged in transit. |
| `POST ?uploads`, `PUT ?partNumber`, `POST ?uploadId`, `DELETE ?uploadId`, `GET ?uploadId` | multipart above 100 MiB | Parts are at least 16 MiB, sized to the vendor's part limit, streamed from the file and checked with `Content-MD5`. The open upload is kept under `s3-uploads/` in the profile folder. A second attempt on the unchanged file reuses the parts S3 holds (ListParts, compared by MD5). `discardStagedUpload` aborts it. |
| `PUT` + `x-amz-copy-source` | `move` up to 5 GiB | CopyObject with `x-amz-metadata-directive: COPY`, then DELETE the source. |
| `PUT ?partNumber` + `x-amz-copy-source-range` | `move` above 5 GiB | UploadPartCopy on every vendor, in parts of at least 512 MiB. |
| `DELETE` | `delete` of a file | A missing key counts as deleted. |
| `POST ?delete` (DeleteObjects) | `delete` of a folder, folder moves | 1,000 keys per call with `Content-MD5`. A vendor that refuses it gets one DELETE per key. |
| presigned `GET` | `share` | Seven days at most, and no password. |

Every request takes a slot from the shared `HttpRetryBudget`. A 500, 503 (SlowDown) or 429 is retried with backoff. A 401 or 403 is `AuthenticationException`, and a 412 is `RemoteConflictException`.

## Hash and time

- **Hash:** `HashAlgorithm.Md5Hex` on the ETag. A multipart ETag (`<hex>-<n>`) is not an MD5, and verification skips it. Neither is an SSE-KMS or SSE-C ETag, which the listing cannot tell apart.
- **Time:** an item's `modified` is S3's `LastModified` wherever the item comes from. A listing carries no user metadata, and the engine compares the time it recorded, so the time read from a listing and the one read after an upload must be the same. The provider writes `x-amz-meta-mtime`, and a `HEAD` reads it back (`S3Api.head`); `move` carries it to the copy.

## Tests

- `./gradlew :providers:s3:test` (part of `check`): the provider against `FakeS3`, an in-memory S3 behind Ktor's mock engine, and `SigV4Signer` against AWS's published examples.
- `./gradlew :providers:s3:minioTest` (not part of `check`): `S3MinioContainerTest`, the provider against a real S3 server, MinIO in a Testcontainer: the listing paged and resumed, single and multipart uploads (resumed after a cut), the part-copy move above 5 GiB, a batch delete refused for one key, a presigned GET, and `If-Match` / `If-None-Match` answered by the server. It needs a Docker engine and skips every case without one. CI runs it in the Linux job `s3-minio`. The fake accepts what MinIO refuses (parts below 5 MiB, for one), so a change to what the provider sends is worth a run of both.

## Not yet

- **Opt-in runs on AWS and Scaleway:** the same cases against a real vendor, gated on keys in the environment.
- **`x-amz-meta-mtime` as the item's time:** a `HEAD` reads it back, but only `move` uses it (to carry it to the copy); `modified` stays `LastModified` everywhere, so the engine never compares the file's own mtime. Using it needs a HEAD per listed object or an engine-side decision on which time to compare.
- **Quota:** none (`hasQuota = false`); a bucket has no plan to report.
