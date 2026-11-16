param(
    [ValidateSet('all','core','vk','cloud','data','learning')][string]$Group = 'all',
    [string]$Name = '',
    [switch]$List,
    [switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
# build.ps1 is the one registry of executable checks. Argument-dependent diagnostics are excluded.
$source = Get-Content -LiteralPath (Join-Path $root 'build.ps1') -Raw
$pattern = '(?m)^\s*& \$java -cp \$classpath (financeos\.[A-Za-z0-9_.]+Test)\s*$'
$classes = @([regex]::Matches($source, $pattern) | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
$selected = @($classes | Where-Object {
    ($Group -eq 'all' -or $_ -like ('financeos.' + $Group + '.*')) -and
    ([string]::IsNullOrWhiteSpace($Name) -or $_ -like ('*' + $Name + '*'))
})
if ($selected.Count -eq 0) { throw 'No registered tests match. Use .\test.ps1 -List to see available names.' }
if ($List) { $selected; Write-Host ('Selected test classes: ' + $selected.Count); return }
if (-not $NoBuild) { & (Join-Path $root 'build.ps1') -SkipTests }
$java = Join-Path $root '..\toolchain\jdk21\jdk21.0.12_12\bin\java.exe'
$jars = @(Get-ChildItem -LiteralPath (Join-Path $root '.m2\com\fasterxml\jackson') -Filter '*.jar' -Recurse | ForEach-Object FullName)
$classpath = (@((Join-Path $root 'target\test-classes'), (Join-Path $root 'target\classes')) + $jars) -join ';'
Push-Location $root
try {
    foreach ($class in $selected) {
        Write-Host ('RUN ' + $class)
        & $java -cp $classpath $class
        if ($LASTEXITCODE -ne 0) { throw ('FAILED: ' + $class) }
    }
} finally { Pop-Location }
Write-Host ('Passed test classes: ' + $selected.Count)
