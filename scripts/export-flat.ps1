# Flat contest GeoJSON (no depth profile).
# Does not overwrite data/m11-sample.geojson.
#
# Local: Maven + JDK 11, java -jar:
#   .\scripts\export-flat.ps1
# Docker (contest stack): API like export-flat.sh --docker:
#   .\scripts\export-flat.ps1 -Docker
param(
    [switch]$Docker
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)
. (Join-Path $PSScriptRoot "docker-export-compose.ps1")

function Export-DockerResult {
    param(
        [bool]$EnableDepth,
        [string]$AllName,
        [string]$Label
    )
    Write-Host "Uploading dataset/dataset_updated.geojson"
    $upload = curl.exe -fsS -F "file=@dataset/dataset_updated.geojson" http://localhost:8080/api/files | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $upload.fileId) { throw "Response has no fileId." }
    $body = @{ fileId = $upload.fileId; enableDepth = $EnableDepth } | ConvertTo-Json -Compress
    $job = Invoke-CurlJsonPost -Url "http://localhost:8080/api/jobs" -JsonBody $body
    if (-not $job.jobId) { throw "Response has no jobId." }
    Write-Host "Job $($job.jobId) ($Label)"
    for ($i = 1; $i -le 180; $i++) {
        Start-Sleep -Seconds 5
        $st = curl.exe -fsS "http://localhost:8080/api/jobs/$($job.jobId)" | ConvertFrom-Json
        Write-Host "$($st.status) $($st.stage) $($st.progress)"
        if ($st.status -eq "FAILED") { throw $st.message }
        if ($st.status -eq "DONE") { break }
        if ($i -eq 180) { throw "Job did not finish within 15 minutes." }
    }
    New-Item -ItemType Directory -Force -Path data | Out-Null
    curl.exe -fsS -o "data/$AllName.geojson" "http://localhost:8080/api/jobs/$($job.jobId)/result"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    foreach ($variant in @("vA", "vB", "vC")) {
        $out = "data/${AllName}_${variant}.geojson"
        curl.exe -fsS -o $out "http://localhost:8080/api/jobs/$($job.jobId)/result?variant=$variant"
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Write-Host "Saved $out"
    }
    Write-Host "Done: data/$AllName.geojson and ${AllName}_vA.geojson, _vB, _vC"
}

if (-not $Docker) {
    Write-Host "Mode: local JAR. For Docker use: .\scripts\export-flat.ps1 -Docker"
    $jar = "backend\target\heatnet-tracing-service-0.1.0-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) {
        mvn -f backend/pom.xml package -DskipTests
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    $env:SPRING_PROFILES_ACTIVE = "local"
    $env:HEATNET_CONFIG_DIR = (Resolve-Path "config").Path
    $env:HEATNET_DATA_DIR = (Join-Path (Get-Location) "data")
    java -Xmx6g -jar $jar `
        --heatnet.cli.input=dataset/dataset_updated.geojson `
        --heatnet.cli.output=data/result.geojson `
        --heatnet.cli.depth=false `
        --heatnet.cli.split-variants=true
    exit $LASTEXITCODE
}

Write-Host "Mode: Docker, flat (no depth)"
Ensure-DockerStack
Wait-Health
Export-DockerResult -EnableDepth $false -AllName "result" -Label "no depth"
