# Start stack and wait for health. Does not run GeoJSON export.
#   .\deploy.ps1
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArgs)
    if (Get-Command docker-compose -ErrorAction SilentlyContinue) {
        & docker-compose @ComposeArgs
    } elseif (Get-Command docker -ErrorAction SilentlyContinue) {
        & docker compose @ComposeArgs
    } else {
        throw "docker-compose 1.29.2 is required."
    }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Invoke-Compose up -d --build

Write-Host "Waiting for health..."
for ($i = 1; $i -le 36; $i++) {
    $health = & curl.exe -fsS http://localhost:8080/actuator/health 2>$null
    if ($LASTEXITCODE -eq 0 -and $health -match '"status":"UP"') {
        curl.exe -fsS http://localhost:8080/api/info
        Write-Host ""
        Write-Host "OK: docker stack is up"
        exit 0
    }
    Start-Sleep -Seconds 5
}

Write-Host "Backend health timeout. Logs:"
Invoke-Compose logs backend --tail 80
exit 1
