<#
.SYNOPSIS
Runs the .NET + Java sample on Windows (or anywhere PowerShell 7 runs): NATS and PostgreSQL in Docker, then
catalog-service (.NET, HTTP 5080) and sales-service (Java, HTTP 8090), which map each other's data over NATS.

.DESCRIPTION
  samples\dotnet-java-nats\run.ps1          start everything, show /orders and /reviews, keep running until Ctrl+C
  samples\dotnet-java-nats\run.ps1 -Check   start everything, check both mappings, stop everything (exit code 1 on failure)

Needs Docker Desktop (Linux containers), the .NET 10 SDK and Java 21 (java on PATH, or JAVA_HOME).
#>
param([switch]$Check)

$ErrorActionPreference = 'Stop'
$sample = $PSScriptRoot
$root = Split-Path -Parent (Split-Path -Parent $sample)
$build = Join-Path $sample 'build'
$logs = Join-Path $build 'logs'
$compose = Join-Path $sample 'docker-compose.yml'
$onWindows = [System.Environment]::OSVersion.Platform -eq 'Win32NT'

$java = 'java'
if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin/java' }
foreach ($tool in @(@('docker', 'Docker Desktop is needed: https://docs.docker.com/get-docker/'),
        @('dotnet', 'The .NET 10 SDK is needed: https://dotnet.microsoft.com/download'),
        @($java, 'Java 21 is needed: put java on PATH or set JAVA_HOME.'))) {
    if (-not (Get-Command $tool[0] -ErrorAction SilentlyContinue)) { Write-Error $tool[1] }
}

function Invoke-Checked([string]$what, [scriptblock]$command) {
    & $command
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit code $LASTEXITCODE)." }
}

$processes = @()

function Start-Service([string]$name, [string]$file, [string[]]$arguments, [string]$directory) {
    New-Item -ItemType Directory -Force -Path $logs | Out-Null
    $out = Join-Path $logs "$name.log"
    $err = Join-Path $logs "$name.err.log"
    $process = Start-Process -FilePath $file -ArgumentList $arguments -WorkingDirectory $directory `
        -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -NoNewWindow
    $script:processes += $process
    Write-Host "Started $name (pid $($process.Id)), log: $out"
    return $process
}

function Wait-Service([string]$name, [string]$url, $process) {
    for ($i = 0; $i -lt 120; $i++) {
        try {
            # Up once it answers with its seeded data: the migrations run before the web server starts
            $content = (Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2).Content
            if ($content.StartsWith('[{')) {
                Write-Host "$name is up: $url"
                return
            }
        } catch {
        }
        if ($process.HasExited) { break }
        Start-Sleep -Seconds 1
    }
    Write-Host "$name did not start; the end of its log:"
    Get-Content (Join-Path $logs "$name.log") -Tail 40 -ErrorAction SilentlyContinue
    throw "$name did not answer $url."
}

$exitCode = 0
try {
    Write-Host 'Starting NATS and PostgreSQL...'
    Invoke-Checked 'docker compose up' { docker compose -f $compose up -d --wait }

    Write-Host 'Building the services...'
    $gradlew = if ($onWindows) { Join-Path $root 'gradlew.bat' } else { Join-Path $root 'gradlew' }
    Push-Location $root
    try {
        Invoke-Checked 'The Java build' { & $gradlew -q ':samples:dotnet-java-nats:sales-service:bootJar' }
    } finally {
        Pop-Location
    }
    $catalogOut = Join-Path $build 'catalog-service'
    Invoke-Checked 'The .NET build' {
        dotnet publish (Join-Path $sample 'catalog-service') -c Release -o $catalogOut --nologo -v quiet | Out-Null
    }
    $jar = Get-ChildItem (Join-Path $sample 'sales-service/build/libs') -Filter '*.jar' |
        Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
    if (-not $jar) { throw 'No jar was built for sales-service.' }

    # catalog-service reads appsettings.json from its working directory
    $catalog = Start-Service 'catalog-service' 'dotnet' @('CatalogService.dll') $catalogOut
    $sales = Start-Service 'sales-service' $java @('-jar', "`"$($jar.FullName)`"") $sample
    Wait-Service 'catalog-service' 'http://localhost:5080/products' $catalog
    Wait-Service 'sales-service' 'http://localhost:8090/customers' $sales

    $orders = (Invoke-WebRequest -Uri 'http://localhost:8090/orders' -UseBasicParsing).Content
    $reviews = (Invoke-WebRequest -Uri 'http://localhost:5080/reviews' -UseBasicParsing).Content
    Write-Host ''
    Write-Host 'GET http://localhost:8090/orders   (Java, with the products of .NET)'
    Write-Host $orders
    Write-Host ''
    Write-Host 'GET http://localhost:5080/reviews  (.NET, with the customers of Java)'
    Write-Host $reviews
    Write-Host ''

    if ($Check) {
        foreach ($expected in @('"productName":"ThinkPad X1 Carbon"', '"categoryName":"Accessories"', '"total":259.8')) {
            if (-not $orders.Contains($expected)) {
                Write-Host "Check failed: /orders does not contain $expected"
                $exitCode = 1
            }
        }
        foreach ($expected in @('"customerName":"Alice Nguyen"', '"customerCity":"Lyon"', '"customerOrderCount":3')) {
            if (-not $reviews.Contains($expected)) {
                Write-Host "Check failed: /reviews does not contain $expected"
                $exitCode = 1
            }
        }
        if ($exitCode -eq 0) { Write-Host "Check passed: each service filled its data from the other over NATS." }
    } else {
        Write-Host 'Everything is running. Try:'
        Write-Host '  curl http://localhost:8090/orders/2'
        Write-Host '  curl http://localhost:5080/reviews/3'
        Write-Host '  curl http://localhost:5080/products'
        Write-Host '  curl http://localhost:8090/customers'
        Write-Host 'Press Ctrl+C to stop.'
        while (-not ($processes | Where-Object { $_.HasExited })) { Start-Sleep -Seconds 1 }
    }
} catch {
    Write-Host "Error: $($_.Exception.Message)"
    $exitCode = 1
} finally {
    foreach ($process in $processes) {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    }
    Write-Host 'Stopping NATS and PostgreSQL...'
    docker compose -f $compose down -v 2>&1 | Out-Null
}
exit $exitCode
