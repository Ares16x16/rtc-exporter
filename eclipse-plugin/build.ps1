param(
    [Parameter(Mandatory = $true)]
    [string]$EclipseHome
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $projectRoot "..")).Path
$pluginProject = Join-Path $projectRoot "com.example.rtc.exporter"
$featureProject = Join-Path $projectRoot "io.github.ares16x16.rtc.exporter.feature"
$buildRoot = Join-Path $projectRoot "build"
$distRoot = Join-Path $projectRoot "dist"
$packageRoot = Join-Path $buildRoot "package"
$siteInput = Join-Path $buildRoot "p2-input"
$p2Repository = Join-Path $buildRoot "p2-repository"
$publisherConfiguration = Join-Path $buildRoot "p2-publisher-configuration"
$publisherWorkspace = Join-Path $buildRoot "p2-publisher-workspace"
$pluginVersion = "2.0.1.20260723"
$pluginId = "com.example.rtc.exporter"
$featureId = "io.github.ares16x16.rtc.exporter.feature"

foreach ($generatedPath in @($buildRoot, $distRoot)) {
    if (Test-Path -LiteralPath $generatedPath) {
        $resolvedGenerated = (Resolve-Path -LiteralPath $generatedPath).Path
        if (-not $resolvedGenerated.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar)) {
            throw "Refusing to clean path outside plug-in project: $resolvedGenerated"
        }
        Remove-Item -LiteralPath $resolvedGenerated -Recurse -Force
    }
}

$bundleInfo = Join-Path $EclipseHome "configuration\org.eclipse.equinox.simpleconfigurator\bundles.info"
if (-not (Test-Path -LiteralPath $bundleInfo)) {
    throw "Eclipse bundles.info was not found: $bundleInfo"
}

$eclipseConsole = Join-Path $EclipseHome "eclipsec.exe"
if (-not (Test-Path -LiteralPath $eclipseConsole -PathType Leaf)) {
    throw "Eclipse console launcher was not found: $eclipseConsole"
}

$classPathEntries = New-Object System.Collections.Generic.List[string]
foreach ($line in Get-Content -LiteralPath $bundleInfo) {
    if ($line.StartsWith("#")) { continue }
    $match = [regex]::Match($line, "file:/([^,]+)")
    if (-not $match.Success) { continue }
    $bundlePath = [Uri]::UnescapeDataString($match.Groups[1].Value) -replace "/", "\"
    if (Test-Path -LiteralPath $bundlePath -PathType Leaf) {
        $classPathEntries.Add($bundlePath)
    }
}
if ($classPathEntries.Count -eq 0) {
    throw "No Eclipse bundle JARs were discovered from $bundleInfo"
}

$classes = Join-Path $buildRoot "classes"
$pluginStage = Join-Path $buildRoot "plugin-stage"
$featureStage = Join-Path $buildRoot "feature-stage"
$sitePlugins = Join-Path $siteInput "plugins"
$siteFeatures = Join-Path $siteInput "features"
$dropinsPlugins = Join-Path $packageRoot "dropins\rtc-exporter\plugins"
New-Item -ItemType Directory -Path $classes, $pluginStage, $featureStage, $sitePlugins, $siteFeatures,
    $dropinsPlugins, $distRoot, $p2Repository | Out-Null

$sources = Get-ChildItem -LiteralPath (Join-Path $pluginProject "src") -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
$argumentFile = Join-Path $buildRoot "javac.args"
$javacClassPath = (($classPathEntries | ForEach-Object { $_.Replace([char]92, [char]47) }) -join ";")
$javacClasses = $classes.Replace([char]92, [char]47)
$arguments = @(
    "-encoding", "UTF-8",
    "-source", "17",
    "-target", "17",
    "-classpath", ([char]34 + $javacClassPath + [char]34),
    "-d", ([char]34 + $javacClasses + [char]34)
)
$arguments += $sources | ForEach-Object { [char]34 + $_.Replace([char]92, [char]47) + [char]34 }
[IO.File]::WriteAllLines($argumentFile, $arguments, [Text.UTF8Encoding]::new($false))
& javac "@$argumentFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Copy-Item -LiteralPath (Join-Path $pluginProject "plugin.xml") -Destination $pluginStage
Copy-Item -LiteralPath (Join-Path $pluginProject "about.html") -Destination $pluginStage
Copy-Item -LiteralPath (Join-Path $pluginProject "icons") -Destination $pluginStage -Recurse
Copy-Item -LiteralPath (Join-Path $repositoryRoot "LICENSE") -Destination $pluginStage
Copy-Item -LiteralPath (Join-Path $classes "com") -Destination $pluginStage -Recurse

$pluginJar = Join-Path $sitePlugins "${pluginId}_${pluginVersion}.jar"
& jar --create --file $pluginJar --manifest (Join-Path $pluginProject "META-INF\MANIFEST.MF") -C $pluginStage .
if ($LASTEXITCODE -ne 0) { throw "Plug-in JAR packaging failed" }

Copy-Item -LiteralPath (Join-Path $featureProject "feature.xml") -Destination $featureStage
Copy-Item -LiteralPath (Join-Path $featureProject "license.html") -Destination $featureStage
Copy-Item -LiteralPath (Join-Path $repositoryRoot "LICENSE") -Destination $featureStage
$featureJar = Join-Path $siteFeatures "${featureId}_${pluginVersion}.jar"
& jar --create --file $featureJar --no-manifest -C $featureStage .
if ($LASTEXITCODE -ne 0) { throw "Feature JAR packaging failed" }

$standaloneJar = Join-Path $distRoot "${pluginId}_${pluginVersion}.jar"
Copy-Item -LiteralPath $pluginJar -Destination $standaloneJar
Copy-Item -LiteralPath $pluginJar -Destination $dropinsPlugins

$archive = Join-Path $distRoot "rtc-exporter-dropins-${pluginVersion}.zip"
& jar --create --file $archive --no-manifest -C $packageRoot .
if ($LASTEXITCODE -ne 0) { throw "Dropins ZIP packaging failed" }

$p2RepositoryUri = ([Uri]($p2Repository.TrimEnd("\") + "\")).AbsoluteUri
$categoryDefinitionUri = ([Uri](Join-Path $projectRoot "category.xml")).AbsoluteUri
& $eclipseConsole -nosplash -consolelog -clean `
    -configuration $publisherConfiguration `
    -data $publisherWorkspace `
    -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher `
    -metadataRepository $p2RepositoryUri `
    -artifactRepository $p2RepositoryUri `
    -metadataRepositoryName "RTC Exporter" `
    -artifactRepositoryName "RTC Exporter" `
    -source $siteInput `
    -compress `
    -publishArtifacts
if ($LASTEXITCODE -ne 0) { throw "p2 features and bundles publishing failed" }

& $eclipseConsole -nosplash -consolelog `
    -configuration $publisherConfiguration `
    -data $publisherWorkspace `
    -application org.eclipse.equinox.p2.publisher.CategoryPublisher `
    -metadataRepository $p2RepositoryUri `
    -categoryDefinition $categoryDefinitionUri `
    -categoryQualifier $featureId `
    -compress
if ($LASTEXITCODE -ne 0) { throw "p2 category publishing failed" }

[IO.File]::WriteAllLines(
    (Join-Path $p2Repository "p2.index"),
    @(
        "version = 1",
        "metadata.repository.factory.order = content.jar,content.xml,!",
        "artifact.repository.factory.order = artifacts.jar,artifacts.xml,!"
    ),
    [Text.UTF8Encoding]::new($false))

$unexpectedPlugins = @(Get-ChildItem -LiteralPath (Join-Path $p2Repository "plugins") -Filter "*.jar" |
    Where-Object { $_.Name -ne "${pluginId}_${pluginVersion}.jar" })
$unexpectedFeatures = @(Get-ChildItem -LiteralPath (Join-Path $p2Repository "features") -Filter "*.jar" |
    Where-Object { $_.Name -ne "${featureId}_${pluginVersion}.jar" })
if ($unexpectedPlugins.Count -ne 0 -or $unexpectedFeatures.Count -ne 0) {
    throw "The p2 repository contains unexpected plug-in or feature artifacts"
}

$p2Archive = Join-Path $distRoot "rtc-exporter-p2-${pluginVersion}.zip"
& jar --create --file $p2Archive --no-manifest -C $p2Repository .
if ($LASTEXITCODE -ne 0) { throw "p2 repository packaging failed" }

$hashes = @($standaloneJar, $archive, $p2Archive) | ForEach-Object {
    $hash = Get-FileHash -LiteralPath $_ -Algorithm SHA256
    "$($hash.Hash)  $($hash.Path | Split-Path -Leaf)"
}
Set-Content -LiteralPath (Join-Path $distRoot "SHA256.txt") -Value $hashes -Encoding ASCII

Write-Output "Standalone JAR: $standaloneJar"
Write-Output "Dropins ZIP: $archive"
Write-Output "p2 repository ZIP: $p2Archive"
Write-Output "Unpacked p2 repository: $p2Repository"
Write-Output "SHA-256 checksums: $(Join-Path $distRoot "SHA256.txt")"
