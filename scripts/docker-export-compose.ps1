# Shared docker-compose helpers for export-*.ps1 (PowerShell).

function Invoke-Compose {
    param(
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArgs,
        [switch]$NoExit
    )
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    if (Get-Command docker-compose -ErrorAction SilentlyContinue) {
        & docker-compose @ComposeArgs
    } elseif (Get-Command docker -ErrorAction SilentlyContinue) {
        & docker compose @ComposeArgs
    } else {
        $ErrorActionPreference = $prev
        throw "docker-compose 1.29.2 is required."
    }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    if (-not $NoExit -and $code -ne 0) { exit $code }
    return $code
}

function Test-BackendHealthy {
    $health = & curl.exe -fsS http://localhost:8080/actuator/health 2>$null
    return ($LASTEXITCODE -eq 0 -and $health -match '"status":"UP"')
}

function Ensure-DockerStack {
    param([switch]$Build)
    if (Test-BackendHealthy) {
        Write-Host "Backend already healthy on :8080; skipping docker-compose up"
        return
    }
    $upArgs = if ($Build) { @("up", "--build", "-d") } else { @("up", "-d") }
    Write-Host ("Starting stack: docker-compose " + ($upArgs -join " "))
    $code = Invoke-Compose @upArgs -NoExit
    if ($code -ne 0) {
        Write-Host "docker-compose up failed (exit $code); recreating stack (down, then up)..."
        Invoke-Compose down -NoExit | Out-Null
        Invoke-Compose @upArgs
    }
}

function Invoke-CurlJsonPost {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$JsonBody
    )
    $tmp = Join-Path $env:TEMP ("heatnet-post-" + [guid]::NewGuid().ToString("n") + ".json")
    [System.IO.File]::WriteAllText($tmp, $JsonBody, [System.Text.UTF8Encoding]::new($false))
    try {
        $raw = curl.exe -fsS -X POST $Url -H "Content-Type: application/json" -d "@$tmp"
        if ($LASTEXITCODE -ne 0) { throw "POST $Url failed (curl exit $LASTEXITCODE)" }
        return $raw | ConvertFrom-Json
    } finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

function Wait-Health {
    for ($i = 1; $i -le 96; $i++) {
        if (Test-BackendHealthy) { return }
        Start-Sleep -Seconds 5
    }
    Invoke-Compose logs --tail 80 backend -NoExit | Out-Null
    throw "Backend did not become healthy."
}
