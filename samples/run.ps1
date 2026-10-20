<#
.SYNOPSIS
Runs the two sample services on Windows (or anywhere PowerShell 7 runs): user-service (HTTP 8081, gRPC 9091), then
order-service (HTTP 8082).

.DESCRIPTION
  samples\run.ps1           build, start both, show /orders, keep them running until Ctrl+C
  samples\run.ps1 -Check    build, start both, check /orders shows the users, stop both (exit code 1 on failure)

Needs Java 21 (java on PATH, or JAVA_HOME).
#>
param([switch]$Check)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $root 'samples/build/logs'
$onWindows = [System.Environment]::OSVersion.Platform -eq 'Win32NT'

$java = 'java'
if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin/java' }
if (-not (Get-Command $java -ErrorAction SilentlyContinue)) {
    Write-Error 'Java 21 is needed: put java on PATH or set JAVA_HOME.'
}

Write-Host 'Building the services...'
$gradlew = if ($onWindows) { Join-Path $root 'gradlew.bat' } else { Join-Path $root 'gradlew' }
Push-Location $root
try {
    & $gradlew -q ':samples:user-service:bootJar' ':samples:order-service:bootJar'
    if ($LASTEXITCODE -ne 0) { throw "The build failed (exit code $LASTEXITCODE)." }
} finally {
    Pop-Location
}

function Get-ServiceJar([string]$name) {
    $jar = Get-ChildItem (Join-Path $root "samples/$name/build/libs") -Filter '*.jar' |
        Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
    if (-not $jar) { throw "No jar was built for $name." }
    return $jar.FullName
}

$processes = @()

function Start-Service([string]$name) {
    New-Item -ItemType Directory -Force -Path $logs | Out-Null
    $out = Join-Path $logs "$name.log"
    $err = Join-Path $logs "$name.err.log"
    $process = Start-Process -FilePath $java -ArgumentList @('-jar', "`"$(Get-ServiceJar $name)`"") `
        -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -NoNewWindow
    $script:processes += $process
    Write-Host "Started $name (pid $($process.Id)), log: $out"
    return $process
}

function Wait-Service([string]$name, [string]$url, $process) {
    for ($i = 0; $i -lt 120; $i++) {
        try {
            Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2 | Out-Null
            Write-Host "$name is up: $url"
            return
        } catch {
            if ($process.HasExited) { break }
            Start-Sleep -Seconds 1
        }
    }
    Write-Host "$name did not start; the end of its log:"
    Get-Content (Join-Path $logs "$name.log") -Tail 40 -ErrorAction SilentlyContinue
    throw "$name did not answer $url."
}

$exitCode = 0
try {
    $users = Start-Service 'user-service'
    Wait-Service 'user-service' 'http://localhost:8081/users' $users
    $orders = Start-Service 'order-service'
    Wait-Service 'order-service' 'http://localhost:8082/orders' $orders

    $body = (Invoke-WebRequest -Uri 'http://localhost:8082/orders' -UseBasicParsing).Content
    Write-Host ''
    Write-Host 'GET http://localhost:8082/orders'
    Write-Host $body
    Write-Host ''

    if ($Check) {
        foreach ($expected in @('"userName":"Alice Nguyen"', '"userCountry":"France"', '"userAddressCount":2')) {
            if (-not $body.Contains($expected)) {
                Write-Host "Check failed: /orders does not contain $expected"
                $exitCode = 1
            }
        }
        if ($exitCode -eq 0) {
            Write-Host "Check passed: order-service filled the orders' user fields from user-service over gRPC."
        }
    } else {
        Write-Host 'Both services are running. Try:'
        Write-Host '  curl http://localhost:8082/orders/2'
        Write-Host '  curl http://localhost:8081/users'
        Write-Host 'Press Ctrl+C to stop them.'
        while (-not ($processes | Where-Object { $_.HasExited })) { Start-Sleep -Seconds 1 }
    }
} catch {
    Write-Host "Error: $($_.Exception.Message)"
    $exitCode = 1
} finally {
    foreach ($process in $processes) {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    }
}
exit $exitCode
