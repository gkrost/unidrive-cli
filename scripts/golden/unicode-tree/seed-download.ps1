<#
.SYNOPSIS
    Seeds the download-direction suite (download.json) on the PROVIDER side of a hermetic localfs profile.

.DESCRIPTION
    The provider directory of a localfs profile is a plain folder, so the seeder can hold the names the cloud
    side creates and Windows cannot: a trailing dot or space, and the reserved device names (CON, PRN.txt, ...).
    The \\?\ prefix turns every path into a literal for the raw Win32 call, which is what makes those names
    legal here; the normal API would refuse them. What a mount or a mirror then shows the user is the test.

    The seeder writes manifest.tsv and its SHA-256 beside -Manifest (feed them to verify.ps1 -Manifest), one
    row per name it created, in the same format as the main suite's manifest. A name the volume refuses (an
    NTFS provider directory folds case, so it keeps only the first of the case-only twin pair) is reported
    loudly and left out of the manifest: on a real cloud both exist, and seeding them there is the provider's
    own job.

    Needs PowerShell 7; the source is ASCII only. Only -ProviderRoot (a fresh suite folder inside it) and the
    two manifest files are written; nothing is ever overwritten.

.EXAMPLE
    pwsh scripts/golden/unicode-tree/seed-download.ps1 -ProviderRoot C:\profiles\hermetic\provider
    pwsh scripts/golden/unicode-tree/verify.ps1 -Root C:\profiles\hermetic\provider\golden-unicode-download-v1 `
        -Manifest scripts/golden/unicode-tree/download-manifest.tsv
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ProviderRoot,
    [string]$Layout = (Join-Path $PSScriptRoot 'download.json'),
    [string]$Manifest = (Join-Path $PSScriptRoot 'download-manifest.tsv')
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required (pwsh).' }

$utf8 = [System.Text.UTF8Encoding]::new($false)
$spec = Get-Content -LiteralPath $Layout -Raw -Encoding UTF8 | ConvertFrom-Json
$id = $spec.id
$defaultMtime = [DateTime]::Parse('2026-01-01T12:00:00Z', [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AdjustToUniversal)

function Expand-Tokens([string]$s) {
    [regex]::Replace($s, '\{U\+([0-9A-Fa-f]{4,6})\}', { param($m) [char]::ConvertFromUtf32([Convert]::ToInt32($m.Groups[1].Value, 16)) })
}
function To-Ascii([string]$s) {
    $sb = [System.Text.StringBuilder]::new()
    $i = 0
    while ($i -lt $s.Length) {
        $cp = [char]::ConvertToUtf32($s, $i)
        if ($cp -ge 0x20 -and $cp -lt 0x7F) { [void]$sb.Append([char]$cp) } else { [void]$sb.Append(('{U+' + $cp.ToString('X4') + '}')) }
        $i += if ($cp -gt 0xFFFF) { 2 } else { 1 }
    }
    $sb.ToString()
}
function Get-Form([string]$s) {
    if ($s.IsNormalized([Text.NormalizationForm]::FormC) -and $s.IsNormalized([Text.NormalizationForm]::FormD)) { return 'both' }
    if ($s.IsNormalized([Text.NormalizationForm]::FormC)) { return 'NFC' }
    if ($s.IsNormalized([Text.NormalizationForm]::FormD)) { return 'NFD' }
    'other'
}

$root = [IO.Path]::GetFullPath((Join-Path $ProviderRoot $id))
if ([IO.Directory]::Exists($root)) { throw "$root already exists; nothing is overwritten. Remove it or pick another -ProviderRoot." }
$literalRoot = '\\?\' + $root

# A path under the suite as a \\?\ literal, with forward slashes folded to the Windows separator.
function Literal([string]$rel) { $literalRoot + '\' + ($rel.Replace('/', '\')) }

$rows = [System.Collections.Generic.List[object]]::new()
$refused = [System.Collections.Generic.List[string]]::new()
# A volume that folds case keeps only the first of a case-only twin pair; WriteAllBytes would silently
# overwrite, so the fold is caught here, before the write.
$folded = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)

foreach ($node in $spec.nodes) {
    $dirRel = (@($node.dir) -join '/')
    [void][IO.Directory]::CreateDirectory((Literal $dirRel))
    foreach ($f in @($node.files)) {
        if ($null -eq $f) { continue }
        $name = Expand-Tokens $f.name
        $rel = "$dirRel/$name"
        if (-not $folded.Add($name)) {
            $refused.Add((To-Ascii $rel))
            Write-Warning ("refused   " + (To-Ascii $rel) + " -- the volume folds case and keeps only the first of a case-only twin pair")
            continue
        }
        $body = [string]$f.text + "`n" + "$id | $rel`n"
        $data = $utf8.GetBytes($body)
        try {
            [IO.File]::WriteAllBytes((Literal $rel), $data)
            [IO.File]::SetLastWriteTimeUtc((Literal $rel), $defaultMtime)
            $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($data)).ToLowerInvariant()
            $rows.Add([pscustomobject]@{ Size = $data.Length; Hash = $hash; Path = $rel })
            Write-Host ("created  " + (To-Ascii $rel))
        } catch {
            $reason = $_.Exception.Message
            if ($_.Exception -is [IO.IOException] -or $_.Exception.InnerException -is [IO.IOException]) {
                $reason = 'the volume refuses this name (expected for the case-only twin pair on a folding volume)'
            }
            $refused.Add((To-Ascii $rel))
            Write-Warning ("refused   " + (To-Ascii $rel) + " -- $reason")
        }
    }
}

$sb = [System.Text.StringBuilder]::new()
[void]$sb.Append("#$id manifest: kind, size, sha256, mtime_utc, form, path_ascii, path (UTF-8, LF)`n")
$sorted = [System.Collections.Generic.List[object]]::new($rows)
$sorted.Sort([Comparison[object]]{ param($a, $b) [string]::CompareOrdinal($a.Path, $b.Path) })
foreach ($r in $sorted) {
    [void]$sb.Append(("{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}`n" -f 'F', $r.Size, $r.Hash, $defaultMtime.ToString('yyyy-MM-ddTHH:mm:ssZ'), (Get-Form $r.Path), (To-Ascii $r.Path), $r.Path))
}
foreach ($r in $refused) {
    [void]$sb.Append("# refused on this volume, present on a real cloud: $r`n")
}
$manifestBytes = $utf8.GetBytes($sb.ToString())
$manifestHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($manifestBytes)).ToLowerInvariant()
[IO.File]::WriteAllBytes($Manifest, $manifestBytes)
[IO.File]::WriteAllText((Join-Path (Split-Path -Parent $Manifest) ([IO.Path]::GetFileNameWithoutExtension($Manifest) + '.sha256')), "$manifestHash  " + [IO.Path]::GetFileName($Manifest) + "`n", $utf8)

Write-Host "created $($rows.Count) file(s) under $root; refused $($refused.Count)"
Write-Host "wrote $Manifest (sha256 $manifestHash)"
if ($refused.Count) { Write-Host "The refused names are expected on NTFS and are listed in the manifest header; a cloud-side seed has them." }
exit 0
