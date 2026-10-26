# `relocate` is switched off

`unidrive relocate --from <profile> --to <profile>` copies a drive's files from one provider to another and, with
`--delete-source`, deletes the originals. It can overwrite or delete files that were never migrated, so it is switched
off (owner decision 2026-10-26, [#94](https://github.com/gkrost/unidrive-cli/pull/94)).

## What is off

- `RELOCATE_ENABLED` in `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt` is `false`. `run()` throws
  first thing, before it reads a profile, creates a provider, cleans temp directories or prints anything. The exit code is
  1 through the normal error handler, the message names the four issues below, and nothing is read or changed.
- The relocate code (`CloudRelocator`, in `:app:sync`) and its tests, including the `deleteSourceRecursive` tests, stay.
  `RelocateCommandTest` pins the refusal: exit code not 0, the message names the issues, stdout empty, the config
  folder unchanged.
- The README lists the command as switched off. Any other text that presents `relocate` as usable is stale.

## The four defects

| Issue | Defect |
|---|---|
| [#56](https://github.com/gkrost/unidrive-cli/issues/56) | A remote item's name is used as a path below the temp dir without containment (`tempDir.resolve(itemName)` in `CloudRelocator`): a name with a backslash or `..` escapes it on Windows (S3 allows any key), overwrites an existing file there, and the cleanup then deletes it. The staged files also sit in the temp dir with its inherited ACL on Windows, readable by other principals; POSIX stages them at 0700 |
| [#61](https://github.com/gkrost/unidrive-cli/issues/61) | `--delete-source` deletes by string prefix without a path boundary (`/Docs` also removes `/Docs-old` and `/Documents`) and ignores failed transfers |
| [#62](https://github.com/gkrost/unidrive-cli/issues/62) | `isEquivalent` treats two different content hashes of equal size as the same file, so the file is skipped (and, with `--delete-source`, the source copy deleted) |
| [#63](https://github.com/gkrost/unidrive-cli/issues/63) | The temp file is named by the leaf name only, so two files with one name in different folders share one temp path while they transfer concurrently |

## Plan to switch it on again

1. #56: name the temp file with a counter or a hash, never with the remote name, and check containment of the resolved path.
   Stage under an owner-only folder (`OwnerOnly`) on every platform.
2. #61: delete by id with a path boundary. Refuse the delete pass if `errorCount > 0` or if anything was skipped
   other than as "equivalent".
3. #62: differing comparable hashes are not equivalent.
4. #63: a unique temp name per transfer, and verify the object after the upload.
5. Set `RELOCATE_ENABLED` to `true` and replace the test `relocate refuses to run ...` in `RelocateCommandTest` with
   tests of the fixed behaviour.

Relocate is not part of the MVP (owner decision 2026-10-19, recorded on #56 and #61): the fix is for after it.
