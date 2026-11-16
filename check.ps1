$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try { python scripts/ci.py; if($LASTEXITCODE -ne 0){throw 'Checks failed'} } finally { Pop-Location }
