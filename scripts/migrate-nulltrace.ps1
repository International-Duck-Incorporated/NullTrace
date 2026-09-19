$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Read-Utf8Raw([string]$Path) {
    return [System.IO.File]::ReadAllText((Join-Path $Root $Path), [System.Text.UTF8Encoding]::new($false))
}

function Write-Utf8Raw([string]$Path, [string]$Content) {
    [System.IO.File]::WriteAllText((Join-Path $Root $Path), $Content, [System.Text.UTF8Encoding]::new($false))
}

function Replace-TextInFile([string]$Path, [hashtable]$Replacements) {
    $full = Join-Path $Root $Path
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { return }
    $text = [System.IO.File]::ReadAllText($full)
    $original = $text
    foreach ($key in $Replacements.Keys) {
        $text = $text.Replace($key, [string]$Replacements[$key])
    }
    if ($text -ne $original) {
        [System.IO.File]::WriteAllText($full, $text, [System.Text.UTF8Encoding]::new($false))
    }
}

Write-Host '== NullTrace migration ==' -ForegroundColor Cyan

# Replace project identifiers throughout text sources. Keep upstream attribution URLs intact later.
$extensions = @('.java', '.json', '.gradle', '.gradle.kts', '.properties', '.md', '.txt', '.yaml', '.yml', '.xml', '.toml', '.gitignore')
Get-ChildItem -LiteralPath $Root -Recurse -File |
    Where-Object {
        $_.FullName -notmatch '\\(\.git|build|\.gradle)\\' -and
        $extensions -contains $_.Extension.ToLowerInvariant()
    } |
    ForEach-Object {
        $rel = $_.FullName.Substring($Root.Length + 1)
        foreach ($replacement in @(
            @('aurick.opsec', 'net.nulltrace'),
            @('aurick/opsec', 'net/nulltrace'),
            @('aurick\\opsec', 'net\\nulltrace'),
            @('OpSec', 'NullTrace'),
            @('Opsec', 'NullTrace'),
            @('opsec', 'nulltrace')
        )) {
            $content = Read-Utf8Raw $rel
            $content = $content.Replace($replacement[0], $replacement[1])
            Write-Utf8Raw $rel $content
        }
    }

# Restore upstream attribution URLs which were intentionally renamed by the generic text pass.
Get-ChildItem -LiteralPath $Root -Recurse -File |
    Where-Object {
        $_.FullName -notmatch '\\(\.git|build|\.gradle)\\' -and
        $extensions -contains $_.Extension.ToLowerInvariant()
    } |
    ForEach-Object {
        $rel = $_.FullName.Substring($Root.Length + 1)
        Replace-TextInFile $rel ([ordered]@{
            'https://github.com/aurickk/NullTrace' = 'https://github.com/aurickk/OpSec'
        })
    }

# Move Java packages and resource namespaces.
$directoryMoves = @(
    @{ From = 'src/client/java/aurick/opsec'; To = 'src/client/java/net/nulltrace' },
    @{ From = 'src/main/java/aurick/opsec';   To = 'src/main/java/net/nulltrace' }
)
foreach ($move in $directoryMoves) {
    $from = Join-Path $Root $move.From
    $to = Join-Path $Root $move.To
    if (Test-Path -LiteralPath $from) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $to) | Out-Null
        if (Test-Path -LiteralPath $to) { throw "Destination already exists: $($move.To)" }
        Move-Item -LiteralPath $from -Destination $to
    }
}

$resourceDir = Join-Path $Root 'src/main/resources/assets/opsec'
$resourceDst = Join-Path $Root 'src/main/resources/assets/nulltrace'
if (Test-Path -LiteralPath $resourceDir) {
    Move-Item -LiteralPath $resourceDir -Destination $resourceDst
}

$mixins = Join-Path $Root 'src/client/resources/opsec.client.mixins.json'
$mixinsNew = Join-Path $Root 'src/client/resources/nulltrace.client.mixins.json'
if (Test-Path -LiteralPath $mixins) {
    Move-Item -LiteralPath $mixins -Destination $mixinsNew
}

# Rename source/resource files after content migration.
Get-ChildItem -LiteralPath (Join-Path $Root 'src') -Recurse -File |
    Sort-Object FullName -Descending |
    ForEach-Object {
        $newName = $_.Name.Replace('OpSec', 'NullTrace').Replace('Opsec', 'NullTrace').Replace('opsec', 'nulltrace')
        if ($newName -ne $_.Name) {
            Move-Item -LiteralPath $_.FullName -Destination (Join-Path $_.DirectoryName $newName)
        }
    }

Get-ChildItem -LiteralPath (Join-Path $Root 'src') -Recurse -Directory |
    Sort-Object FullName -Descending |
    ForEach-Object {
        if ($_.Name -match '^(OpSec|Opsec|opsec|opseclang)$') {
            $newName = $_.Name.Replace('OpSec', 'NullTrace').Replace('Opsec', 'NullTrace').Replace('opsec', 'nulltrace')
            Move-Item -LiteralPath $_.FullName -Destination (Join-Path $_.Parent.FullName $newName)
        }
    }

# Remove now-empty legacy package containers.
foreach ($dir in @('src/client/java/aurick', 'src/main/java/aurick')) {
    $full = Join-Path $Root $dir
    if (Test-Path -LiteralPath $full) {
        try { Remove-Item -LiteralPath $full -Force -Recurse -ErrorAction Stop } catch {}
    }
}

# Canonical root build configuration.
$gradleProperties = Read-Utf8Raw 'gradle.properties'
$gradleProperties = [regex]::Replace($gradleProperties, '(?m)^maven_group=.*$', 'maven_group=net.nulltrace')
$gradleProperties = [regex]::Replace($gradleProperties, '(?m)^loader_version=.*$', 'loader_version=0.19.5')
Write-Utf8Raw 'gradle.properties' $gradleProperties

$settings = @'
pluginManagement {
    repositories {
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.fabricmc.net/")
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.6"
}

stonecutter {
    kotlinController = false
    centralScript = "build.gradle"

    create(rootProject) {
        versions("26.2", "26.3")
        vcsVersion = "26.3"
    }
}
'@
Write-Utf8Raw 'settings.gradle.kts' $settings

$stonecutter = @'
plugins {
    id 'dev.kikugie.stonecutter'
}

stonecutter.active '26.3' /* [SC] DO NOT EDIT */

tasks.register('buildAll') {
    group = 'build'
    description = 'Builds all supported Minecraft versions'
    dependsOn ':26.2:build', ':26.3:build'
}

tasks.register('remapAll') {
    group = 'build'
    description = 'Builds final artifacts for all supported Minecraft versions'
    dependsOn ':26.2:jar', ':26.3:jar'
}
'@
Write-Utf8Raw 'stonecutter.gradle' $stonecutter

$wrapper = Read-Utf8Raw 'gradle/wrapper/gradle-wrapper.properties'
$wrapper = [regex]::Replace($wrapper, 'gradle-[0-9.]+-bin\.zip', 'gradle-9.6.1-bin.zip')
Write-Utf8Raw 'gradle/wrapper/gradle-wrapper.properties' $wrapper

$build = Read-Utf8Raw 'build.gradle'
$build = $build.Replace('opsec-$version_suffix', 'nulltrace-$version_suffix')
$build = $build.Replace('create("opsec")', 'create("nulltrace")')
$build = $build.Replace('opsec.refmap.json', 'nulltrace.refmap.json')
$build = $build.Replace('opsec.client.mixins.json', 'nulltrace.client.mixins.json')
$build = $build.Replace('artifactId = "opsec-$minecraft_version"', 'artifactId = "nulltrace-$minecraft_version"')
Write-Utf8Raw 'build.gradle' $build

# Ensure the exact current 26.2 dependency set and add the 26.3 target.
$v262 = @'
# Minecraft 26.2
# Unobfuscated version line - uses net.fabricmc.fabric-loom (no remapping)
# Requires Java 25

minecraft_version=26.2
loader_version=0.19.5
fabric_version=0.160.0+26.2
modmenu_version=20.0.0

minecraft_version_range=>=26.2 <26.3
version_suffix=26.2
'@
Write-Utf8Raw 'versions/26.2/gradle.properties' $v262

New-Item -ItemType Directory -Force -Path (Join-Path $Root 'versions/26.3') | Out-Null
$v263 = @'
# Minecraft 26.3
# Unobfuscated version line - uses net.fabricmc.fabric-loom (no remapping)
# Requires Java 25

minecraft_version=26.3
loader_version=0.19.5
fabric_version=0.160.5+26.3
modmenu_version=21.0.0-beta.1

minecraft_version_range=>=26.3 <26.4
version_suffix=26.3
'@
Write-Utf8Raw 'versions/26.3/gradle.properties' $v263

# Minecraft 26.3 switched GLFW -> SDL3. The old >=26.1 branches in this custom
# config screen poll GLFW directly. Give 26.3 its native event path, while keeping
# the existing 26.2 polling implementation untouched.
$guiPath = 'src/client/java/net/nulltrace/mod/config/NullTraceConfigScreen.java'
if (Test-Path -LiteralPath (Join-Path $Root $guiPath)) {
    $gui = Read-Utf8Raw $guiPath

    $injections = @{
        'AccountRowWidget' = @'
        //? if >=26.3 {
        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            updateButtonPositions();
            accountButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
            removeButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubled) {
            if (click.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
                if (accountButton.isMouseOver(click.x(), click.y())) return accountButton.mouseClicked(click, doubled);
                if (removeButton.isMouseOver(click.x(), click.y())) return removeButton.mouseClicked(click, doubled);
            }
            return false;
        }
        //?} elif >=26.1 {
'@
        'ImportExportRowWidget' = @'
        //? if >=26.3 {
        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            updateButtonPositions();
            importButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
            exportButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubled) {
            if (click.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
                if (importButton.isMouseOver(click.x(), click.y())) return importButton.mouseClicked(click, doubled);
                if (exportButton.isMouseOver(click.x(), click.y())) return exportButton.mouseClicked(click, doubled);
            }
            return false;
        }
        //?} elif >=26.1 {
'@
        'ToggleAllRowWidget' = @'
        //? if >=26.3 {
        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            updateButtonPositions();
            enableAllButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
            disableAllButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubled) {
            if (click.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
                if (enableAllButton.isMouseOver(click.x(), click.y())) return enableAllButton.mouseClicked(click, doubled);
                if (disableAllButton.isMouseOver(click.x(), click.y())) return disableAllButton.mouseClicked(click, doubled);
            }
            return false;
        }
        //?} elif >=26.1 {
'@
    }

    foreach ($entry in $injections.GetEnumerator()) {
        $className = $entry.Key
        $pattern = '(?s)(class ' + [regex]::Escape($className) + '\b.*?)(?m)^([ \t]*//\? if >=26\.1 \{)'
        $newText = [regex]::Replace($gui, $pattern, {
            param($match)
            return $match.Groups[1].Value + $entry.Value
        }, 1)
        if ($newText -eq $gui) {
            throw "Could not add 26.3 input branch for $className"
        }
        $gui = $newText
    }

    Write-Utf8Raw $guiPath $gui
}

# Validate no local project identifiers remain in tracked source/config text.
$remaining = @()
Get-ChildItem -LiteralPath $Root -Recurse -File |
    Where-Object {
        $_.FullName -notmatch '\\(\.git|build|\.gradle)\\' -and
        $extensions -contains $_.Extension.ToLowerInvariant()
    } |
    ForEach-Object {
        $content = [System.IO.File]::ReadAllText($_.FullName)
        if ($content -match '(?i)(aurick\.opsec|assets/opsec|\bopsec\b|OpSec|Opsec)') {
            $remaining += $_.FullName.Substring($Root.Length + 1)
        }
    }
if ($remaining.Count -gt 0) {
    Write-Host 'Legacy identifiers remain in:' -ForegroundColor Yellow
    $remaining | ForEach-Object { Write-Host "  $_" }
}

Write-Host 'Migration complete.' -ForegroundColor Green
Write-Host 'Next: .\gradlew.bat :26.2:build :26.3:build' -ForegroundColor Cyan
