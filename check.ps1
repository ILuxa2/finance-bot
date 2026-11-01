$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & mvn test-compile dependency:build-classpath '-Dmdep.outputFile=target/test-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    $classpath = @('target/test-classes', 'target/classes', (Get-Content target/test-classpath.txt -Raw).Trim()) -join [IO.Path]::PathSeparator
    foreach ($file in Get-ChildItem src/test/java -Recurse -Filter '*Test.java') {
        $relative = [IO.Path]::GetRelativePath((Join-Path $PSScriptRoot 'src/test/java'), $file.FullName)
        $class = ($relative -replace '\.java$', '') -replace '[/\\]', '.'
        & java '-Dfile.encoding=UTF-8' -cp $classpath $class
        if ($LASTEXITCODE -ne 0) { throw "Check failed: $class" }
    }
} finally { Pop-Location }
