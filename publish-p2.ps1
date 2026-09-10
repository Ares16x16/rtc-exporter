param(
    [Parameter(Mandatory = $true)]
    [string]$EclipseHome,
    [string]$BundleInfoPath
)

$ErrorActionPreference = "Stop"
$Version = "3.0.2.20260910"
$PluginId = "com.example.rtc.exporter"
$FeatureId = "io.github.ares16x16.rtc.exporter.feature"
$Repo = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$BuildScript = Join-Path $Repo "eclipse-plugin\build.ps1"
$EclipseHome = (Resolve-Path -LiteralPath $EclipseHome).Path
$BundleInfo = if ([string]::IsNullOrWhiteSpace($BundleInfoPath)) {
    Join-Path $EclipseHome "configuration\org.eclipse.equinox.simpleconfigurator\bundles.info"
} else {
    (Resolve-Path -LiteralPath $BundleInfoPath).Path
}

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath failed with exit code $LASTEXITCODE"
    }
}

if (-not (Test-Path -LiteralPath (Join-Path $EclipseHome "eclipsec.exe") -PathType Leaf)) {
    throw "EclipseHome must contain eclipsec.exe: $EclipseHome"
}
if (-not (Test-Path -LiteralPath $BundleInfo -PathType Leaf)) {
    throw "Eclipse bundles.info was not found: $BundleInfo"
}

$requiredBundleIds = @(
    "com.ibm.team.filesystem.client",
    "com.ibm.team.repository.client",
    "com.ibm.team.scm.client"
)
$bundleText = Get-Content -LiteralPath $BundleInfo
foreach ($id in $requiredBundleIds) {
    if (-not ($bundleText | Select-String -SimpleMatch "$id,")) {
        throw "$EclipseHome is not an EWM Eclipse installation: missing $id"
    }
}

$buildArguments = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $BuildScript,
    "-EclipseHome", $EclipseHome
)
if (-not [string]::IsNullOrWhiteSpace($BundleInfoPath)) {
    $buildArguments += @("-BundleInfoPath", $BundleInfo)
}
Invoke-NativeCommand "powershell.exe" $buildArguments

$p2Source = Join-Path $Repo "eclipse-plugin\build\p2-repository"
$requiredP2Files = @(
    (Join-Path $p2Source "p2.index"),
    (Join-Path $p2Source "content.jar"),
    (Join-Path $p2Source "artifacts.jar"),
    (Join-Path $p2Source "features\${FeatureId}_${Version}.jar"),
    (Join-Path $p2Source "plugins\${PluginId}_${Version}.jar")
)
foreach ($file in $requiredP2Files) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Missing p2 artifact: $file"
    }
    if ((Get-Item -LiteralPath $file).Length -le 0) {
        throw "Empty p2 artifact: $file"
    }
}

Invoke-NativeCommand "git.exe" @("fetch", "origin", "gh-pages")
$pages = Join-Path ([IO.Path]::GetTempPath()) `
    ("rtc-exporter-pages-" + [Guid]::NewGuid().ToString("N"))
Invoke-NativeCommand "git.exe" @("worktree", "add", "--detach", $pages, "origin/gh-pages")

$pagesP2 = Join-Path $pages "p2"
New-Item -ItemType Directory -Path $pagesP2 -Force | Out-Null
Get-ChildItem -LiteralPath $pagesP2 -Force |
    Where-Object { $_.Name -ne "index.html" } |
    Remove-Item -Recurse -Force
Get-ChildItem -LiteralPath $p2Source -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $pagesP2 -Recurse -Force
}

$publishedP2Files = @(
    (Join-Path $pagesP2 "p2.index"),
    (Join-Path $pagesP2 "content.jar"),
    (Join-Path $pagesP2 "artifacts.jar"),
    (Join-Path $pagesP2 "features\${FeatureId}_${Version}.jar"),
    (Join-Path $pagesP2 "plugins\${PluginId}_${Version}.jar")
)
foreach ($file in $publishedP2Files) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Published p2 file is missing: $file"
    }
    if ((Get-Item -LiteralPath $file).Length -le 0) {
        throw "Published p2 file is empty: $file"
    }
}

$rootIndex = Join-Path $pages "index.html"
$rootHtml = [IO.File]::ReadAllText($rootIndex)
$rootHtml = $rootHtml.Replace(
    "Version 3.0.1 · Eclipse update site",
    "Version 3.0.2 · Eclipse update site"
).Replace(
    "Version 3.0.1 provides a smaller Pending Changes selection window and clearer guidance for loading EWM history before export.",
    "Version 3.0.2 exports all Pending Changes currently loaded in the view; History export remains selectable."
)
[IO.File]::WriteAllText($rootIndex, $rootHtml, [Text.UTF8Encoding]::new($false))

$p2Index = Join-Path $pagesP2 "index.html"
$p2Html = [IO.File]::ReadAllText($p2Index)
$p2Html = $p2Html.Replace(
    "Current version: <strong>3.0.1</strong>",
    "Current version: <strong>3.0.2</strong>"
)
[IO.File]::WriteAllText($p2Index, $p2Html, [Text.UTF8Encoding]::new($false))

Invoke-NativeCommand "git.exe" @("-C", $pages, "add", "--", "p2", "index.html")
& git.exe -C $pages diff --cached --quiet
$diffExitCode = $LASTEXITCODE
if ($diffExitCode -eq 0) {
    throw "No Pages changes were staged"
}
if ($diffExitCode -ne 1) {
    throw "Could not inspect staged Pages changes"
}

Invoke-NativeCommand "git.exe" @(
    "-C", $pages,
    "commit", "-m", "Publish RTC Exporter $Version p2 repository"
)
Invoke-NativeCommand "git.exe" @("-C", $pages, "push", "origin", "HEAD:gh-pages")

Write-Host "Published: https://ares16x16.github.io/rtc-exporter/p2/"
Write-Host "Pages worktree retained for inspection: $pages"
