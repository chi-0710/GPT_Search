# Env-gated reindex benchmark: isolated pgvector + local embedding stub, 1k/5k/10k chunks.
#
# Creates its own docker network, pgvector container (--tmpfs data dir, never the compose
# application database/volume) and lets ReindexPerformanceTest create/drop a per-run database.
# Each scale runs in a fresh Maven invocation with -Xmx<HeapMb>m; the surefire JVM working set
# (RSS) is sampled from PowerShell while the test runs. Results are merged into
# backend-java/target/reindex-benchmark.json (override with -Output).
#
# Self-contained ASCII-only source: Windows PowerShell 5.1 mis-decodes UTF-8 without BOM.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/reindex_benchmark.ps1
#        [-Scales 1000,5000,10000] [-Dimensions 1536] [-BatchSize 500] [-HeapMb 256]
param(
    [int[]] $Scales = @(1000, 5000, 10000),
    [int] $Dimensions = 1536,
    [int] $BatchSize = 500,
    [int] $HeapMb = 256,
    [string] $Output = 'backend-java/target/reindex-benchmark.json'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaBuild = Join-Path $root 'scripts/java_build.ps1'
$outputPath = if ([System.IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path $root $Output }
$runDir = Join-Path $root 'backend-java/target/reindex-benchmark-runs'
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$runId = "$PID-$(Get-Random -Maximum 99999)"
$container = "rr-reindex-bench-$runId"
$network = "$container-net"
$createdContainer = $false
$createdNetwork = $false
$runs = @()

function Invoke-CheckedDocker {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: docker $($args -join ' ') (exit $LASTEXITCODE)" }
}

try {
    Invoke-CheckedDocker network create $network
    $createdNetwork = $true
    Invoke-CheckedDocker run -d --name $container --network $network --tmpfs /var/lib/postgresql/data `
        -p 127.0.0.1::5432 -e POSTGRES_DB=researchrunner_java_write_test `
        -e POSTGRES_USER=java_test -e POSTGRES_PASSWORD=java_test pgvector/pgvector:pg16 | Out-Null
    $createdContainer = $true

    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & docker exec $container pg_isready -h 127.0.0.1 -U java_test -d researchrunner_java_write_test | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Isolated PostgreSQL did not become ready' }

    $mapping = (& docker port $container 5432/tcp).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $mapping) { throw 'Cannot resolve the isolated PostgreSQL mapped port' }
    $port = ($mapping -split ':')[-1]
    $databaseUrl = "jdbc:postgresql://127.0.0.1:$port/researchrunner_java_write_test"
    Write-Output "isolated PostgreSQL: $databaseUrl"

    $env:JAVA_WRITE_TEST_DATABASE_URL = $databaseUrl
    $env:REINDEX_BENCHMARK = '1'
    $env:REINDEX_BENCH_DIMENSIONS = "$Dimensions"
    $env:REINDEX_BATCH_SIZE = "$BatchSize"
    $env:REINDEX_BENCH_CHUNKS_PER_DOC = '1000'
    $env:DOCUMENT_CHUNK_SIZE = '1000'
    $env:DOCUMENT_CHUNK_OVERLAP = '100'

    foreach ($scale in $Scales) {
        $runFile = Join-Path $runDir "run-$scale.json"
        Remove-Item -LiteralPath $runFile -Force -ErrorAction SilentlyContinue
        $stopFile = Join-Path $runDir "stop-$scale"
        Remove-Item -LiteralPath $stopFile -Force -ErrorAction SilentlyContinue
        $env:REINDEX_BENCH_CHUNKS = "$scale"
        $env:REINDEX_BENCH_OUTPUT = $runFile

        $monitor = Start-Job -ScriptBlock {
            param($stop)
            $peak = 0
            while (-not (Test-Path -LiteralPath $stop)) {
                try {
                    $processes = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction Stop |
                        Where-Object { $_.CommandLine -and $_.CommandLine -match 'surefirebooter' }
                    foreach ($entry in $processes) {
                        $process = Get-Process -Id $entry.ProcessId -ErrorAction SilentlyContinue
                        if ($process -and $process.WorkingSet64 -gt $peak) { $peak = $process.WorkingSet64 }
                    }
                } catch { }
                Start-Sleep -Milliseconds 250
            }
            return $peak
        } -ArgumentList $stopFile

        $mvnArgs = @("-Dtest=ReindexPerformanceTest", "-DargLine=-Xmx${HeapMb}m", 'test')
        $exitCode = 0
        try {
            & powershell -NoProfile -ExecutionPolicy Bypass -File $javaBuild @mvnArgs
            $exitCode = $LASTEXITCODE
        } finally {
            New-Item -ItemType File -Path $stopFile -Force | Out-Null
            $peakBytes = Receive-Job -Job $monitor -Wait -AutoRemoveJob
            Remove-Item -LiteralPath $stopFile -Force -ErrorAction SilentlyContinue
        }
        if ($exitCode -ne 0) { throw "Maven benchmark failed for scale $scale (exit $exitCode)" }
        if (-not (Test-Path -LiteralPath $runFile)) { throw "Missing benchmark JSON for scale ${scale}: $runFile" }

        $run = Get-Content -LiteralPath $runFile -Raw | ConvertFrom-Json
        $rssMb = $null
        if ($peakBytes) { $rssMb = [math]::Round(([double]@($peakBytes)[0]) / 1MB, 1) }
        $run | Add-Member -NotePropertyName jvmRssPeakMb -NotePropertyValue $rssMb -Force
        $runs += $run
        Write-Output ("scale={0} chunks totalMs={1} stagingMs={2} commitMs={3} heapPeakDeltaMb={4} rssPeakMb={5} maxBatchChunks={6}" -f `
            $run.scaleChunks, $run.totalMs, $run.stagingMs, $run.commitMs, $run.heapPeakDeltaMb, $rssMb, $run.maxBatchChunks)
    }

    $document = [ordered]@{
        generatedAt           = (Get-Date).ToString('o')
        host                  = $env:COMPUTERNAME
        postgresImage         = 'pgvector/pgvector:pg16'
        isolation             = 'temporary docker network + temporary pgvector container (--tmpfs data dir) + per-run database; compose application DB/volume is never used'
        embedding             = "local deterministic EmbeddingGateway stub, no external provider calls; dimensions $Dimensions"
        batchSize             = $BatchSize
        dimensions            = $Dimensions
        jvmMaxHeapMb          = $HeapMb
        jvmRssNote            = 'jvmRssPeakMb is the surefire JVM peak working set sampled from PowerShell; heap* values are sampled inside the JVM'
        boundedMemoryEvidence = 'each embedding/staging batch is capped by batchSize, its vectors are written to the staging table and flushed immediately; heapPeakDeltaMb must not grow linearly with total chunks'
        scales                = @($runs)
    }
    $outputDir = Split-Path -Parent $outputPath
    if ($outputDir) { New-Item -ItemType Directory -Force -Path $outputDir | Out-Null }
    $jsonText = $document | ConvertTo-Json -Depth 10
    [System.IO.File]::WriteAllText($outputPath, $jsonText + "`n", (New-Object System.Text.UTF8Encoding($false)))
    Write-Output "benchmark JSON: $outputPath"
}
finally {
    if ($createdContainer) { & docker rm -f $container | Out-Null }
    if ($createdNetwork) { & docker network rm $network | Out-Null }
    foreach ($name in @('JAVA_WRITE_TEST_DATABASE_URL', 'REINDEX_BENCHMARK', 'REINDEX_BENCH_DIMENSIONS', 'REINDEX_BATCH_SIZE',
            'REINDEX_BENCH_CHUNKS', 'REINDEX_BENCH_CHUNKS_PER_DOC', 'REINDEX_BENCH_OUTPUT', 'DOCUMENT_CHUNK_SIZE', 'DOCUMENT_CHUNK_OVERLAP')) {
        Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    }
}
