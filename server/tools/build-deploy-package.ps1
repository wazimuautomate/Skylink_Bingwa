<#
.SYNOPSIS
    Builds the cPanel upload package for the Skylink Bingwa server.

.DESCRIPTION
    Production is deployed by hand: files are dragged into cPanel File Manager over the running
    installation. Uploading the whole project every time is slow and risks clobbering files the
    owner edited on the server, so this script produces a ZIP containing ONLY the files that
    changed against a baseline git ref, with the folder structure preserved exactly as it must
    land inside public_html.

    The ZIP always excludes secrets, local config, uploads and build output. It also carries a
    generated DEPLOY-README.md listing which SQL migrations to run and what to check afterwards.

.PARAMETER Since
    Baseline git ref to diff against. Default 'main' — i.e. "everything this branch changed".
    Use a tag or commit SHA to build the delta since a specific deployment.

.PARAMETER OutDir
    Where the ZIP is written. Default server/dist (git-ignored).

.EXAMPLE
    pwsh server/tools/build-deploy-package.ps1
    pwsh server/tools/build-deploy-package.ps1 -Since v1.0.2
#>
[CmdletBinding()]
param(
    [string] $Since  = 'main',
    [string] $OutDir = ''
)

$ErrorActionPreference = 'Stop'

$repoRoot = (git rev-parse --show-toplevel).Trim()
if (-not $repoRoot) { throw 'Not inside a git repository.' }
Set-Location $repoRoot

if (-not $OutDir) { $OutDir = Join-Path $repoRoot 'server/dist' }

# Files that must never leave the developer machine, whatever git says.
$excludePatterns = @(
    '*/config/config.php',
    '*/uploads/*',
    '*/storage/*',
    '*/dist/*',
    'server/tools/*',          # developer tooling, not server code
    '*/tests/*',               # the logic suite runs in CI, never on the web server
    '*/bin/*',                 # one-off CLI utilities
    '*/cutover/*',             # legacy migration helpers
    '*/.gitignore',
    '*/composer.json',
    '*.zip', '*.pem', '*.key', '*.p12', '*.jks', '*.keystore', '*.env', '.env*'
)

function Test-Excluded([string] $path) {
    foreach ($pattern in $excludePatterns) {
        if ($path -like $pattern) { return $true }
    }
    return $false
}

Write-Host "Baseline ref : $Since"
$sha = (git rev-parse --short HEAD).Trim()
Write-Host "Current HEAD : $sha"

# --- collect changed files -------------------------------------------------------------
# Added, Copied, Modified, Renamed. Deletions are reported separately: a file removed from the
# repo must be removed from the server by hand, never silently.
$changed = @(git diff --name-only --diff-filter=ACMR "$Since...HEAD" -- server/ |
    Where-Object { $_ -and (Test-Path $_) -and -not (Test-Excluded $_) })
$deleted = @(git diff --name-only --diff-filter=D "$Since...HEAD" -- server/)

if ($changed.Count -eq 0) {
    Write-Warning "No changed files under server/ since '$Since'. Nothing to package."
    return
}

Write-Host "Changed files: $($changed.Count)"
if ($deleted.Count -gt 0) { Write-Host "Deleted files: $($deleted.Count) (listed in DEPLOY-README.md)" }

# --- stage -----------------------------------------------------------------------------
$stamp   = Get-Date -Format 'yyyyMMdd-HHmm'
$staging = Join-Path ([System.IO.Path]::GetTempPath()) "skylinkbingwa-deploy-$stamp"
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Path $staging -Force | Out-Null

foreach ($file in $changed) {
    # Strip the leading "server/" so the archive root matches the cPanel upload target.
    $relative = $file -replace '^server/', ''
    $target   = Join-Path $staging $relative
    $parent   = Split-Path $target -Parent
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    Copy-Item -LiteralPath $file -Destination $target -Force
}

# --- migrations that ship in this package ----------------------------------------------
$migrations = @($changed |
    Where-Object { $_ -like 'server/admin-v2/database/migrations/*.sql' } |
    ForEach-Object { Split-Path $_ -Leaf } |
    Sort-Object)

# --- generated readme ------------------------------------------------------------------
$readme = @()
$readme += '# Skylink Bingwa server — deployment package'
$readme += ''
$readme += "Built: $(Get-Date -Format 'yyyy-MM-dd HH:mm') (Africa/Nairobi machine time)"
$readme += "Baseline: $Since"
$readme += "Commit: $sha"
$readme += "Files: $($changed.Count)"
$readme += ''
$readme += '## 1. Back up first'
$readme += ''
$readme += '- In cPanel, export the MySQL database (phpMyAdmin > Export > Quick).'
$readme += '- Download a copy of the current `admin-v2` folder, or at minimum `admin-v2/config/config.php`.'
$readme += ''
$readme += '## 2. Upload'
$readme += ''
$readme += 'Extract this ZIP and upload its contents into the folder that already holds the server,'
$readme += 'keeping the folder structure. Files overwrite in place; nothing else is touched.'
$readme += ''
$readme += '**`config/config.php` is deliberately NOT in this package.** Your live credentials stay as they are.'
$readme += ''
$readme += '## 3. Database migrations'
$readme += ''
if ($migrations.Count -gt 0) {
    $readme += 'This package adds the following migrations:'
    $readme += ''
    foreach ($m in $migrations) { $readme += "- ``$m``" }
    $readme += ''
    $readme += 'You do NOT need to run them by hand. The admin applies pending migrations'
    $readme += 'automatically on the first request after upload (App\Core\Installer::autoProvision).'
    $readme += 'Simply open the admin panel once and sign in.'
    $readme += ''
    $readme += 'If you prefer to run them explicitly and your host offers SSH or a PHP CLI cron:'
    $readme += ''
    $readme += '```'
    $readme += 'php database/migrate.php'
    $readme += '```'
} else {
    $readme += 'No new migrations in this package.'
}
$readme += ''
$readme += '## 4. Files removed from the project'
$readme += ''
if ($deleted.Count -gt 0) {
    $readme += 'Delete these from the server by hand — they are no longer part of the application:'
    $readme += ''
    foreach ($d in $deleted) { $readme += "- ``$($d -replace '^server/', '')``" }
} else {
    $readme += 'None.'
}
$readme += ''
$readme += '## 5. After uploading'
$readme += ''
$readme += '1. Open the admin panel and sign in. The migration step runs on this first request.'
$readme += '2. Open **Preview & publish**. It should list only what you actually changed.'
$readme += '3. Publish once so devices receive the new configuration.'
$readme += '4. Check `GET /api/health` returns `"ok": true` and the expected `configVersion`.'
$readme += '5. Check `GET /api/sync/manifest` lists every resource with a version.'
$readme += '6. Confirm the app still loads offers on a phone.'
$readme += ''
$readme += '## 6. Cache'
$readme += ''
$readme += 'No server-side cache needs clearing. If your host runs OPcache and an old page persists,'
$readme += 'wait for the OPcache TTL or restart PHP from cPanel > Select PHP Version > switch and switch back.'
$readme += 'Browsers may hold the admin CSS/JS; hard-refresh with Ctrl+F5.'
$readme += ''

$readmePath = Join-Path $staging 'DEPLOY-README.md'
$readme -join "`n" | Set-Content -LiteralPath $readmePath -Encoding utf8

# --- manifest of exactly what is inside -------------------------------------------------
$manifest = @('# Files in this package', '') + ($changed | ForEach-Object { $_ -replace '^server/', '' } | Sort-Object)
$manifest -join "`n" | Set-Content -LiteralPath (Join-Path $staging 'DEPLOY-MANIFEST.txt') -Encoding utf8

# --- zip --------------------------------------------------------------------------------
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$zipPath = Join-Path $OutDir "skylink-bingwa-server-$stamp-$sha.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }

Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $zipPath -CompressionLevel Optimal
Remove-Item $staging -Recurse -Force

$hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLower()
"$hash  $(Split-Path $zipPath -Leaf)" | Set-Content -LiteralPath "$zipPath.sha256" -Encoding ascii

Write-Host ''
Write-Host "Package : $zipPath"
Write-Host "SHA-256 : $hash"
Write-Host ''
Write-Host 'Read DEPLOY-README.md inside the ZIP before uploading.'
