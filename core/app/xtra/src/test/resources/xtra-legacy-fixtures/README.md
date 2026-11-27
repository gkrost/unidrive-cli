# X-tra legacy fixtures

Written once by the unchanged `main` code (before `aes-gcm-sym`), so `AesGcmSymmetricStrategyTest` can prove that files
and key files of the earlier algorithms still decrypt. Test material only: the passphrase of every key file here is
`fixture-pass-1234`, and nothing here protects real data.

| File | What it is |
|---|---|
| `plaintext.txt` | the 70,000-byte plaintext (crosses one 64 KiB segment boundary) |
| `xtra.key`, `aes-gcm.xtra` | RSA key file and a stored file of algorithm 1 (`aes-gcm`) |
| `xtra-age.key`, `age-v1.xtra` | age identity envelope and a stored file of algorithm 2 (`age-v1`) |
| `xtra-tink.key`, `tink-aes-gcm.xtra` | Tink keyset envelope and a stored file of algorithm 3 (`tink-aes-gcm`) |
