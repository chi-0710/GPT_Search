<#
.SYNOPSIS
    One-shot, fully isolated full-flow E2E run for the ResearchRunner Java backend.

.DESCRIPTION
    Runs the real frontend + real Java backend + real PostgreSQL/pgvector, but every model,
    embedding, search and scrape call is served by the local deterministic stub
    `scripts/stub_provider_server.py`, so no paid provider is ever contacted.

    Pipeline:
      1. build the Java JAR through scripts/java_build.ps1 (serialized Maven lock)
      2. start the deterministic provider stub (OpenAI chat/embeddings, /search, firecrawl)
      3. start a throw-away pgvector container (own network, tmpfs data dir, host port 5434)
      4. run the Java Flyway/Alembic-compatible migration against that container
      5. start the Java API on 18003 with stub provider configuration
      6. copy frontend/nextjs to a temp dir (own .next cache, node_modules junction) and start
         `next dev` on 3901 pointing at the Java API
      7. run `npx playwright test full-flow` against that stack
      8. collect logs + stub stage counters and remove every process/container/port it created

    Every resource it creates is isolated from the docker compose stack: no compose project,
    service, volume, database or `.env` file is touched. Logs and Playwright artifacts are
    written under backend-java/target/e2e-stub/<timestamp>/.

.PARAMETER SkipBuild
    Reuse the existing backend-java/target/researchrunner-api-0.1.0-SNAPSHOT.jar.

.PARAMETER KeepEnvironment
    Debugging aid: do not stop processes/containers at the end. The script prints the
    endpoints and log paths so the environment can be inspected (and cleaned manually).

.PARAMETER NoDockerStart
    Fail instead of starting Docker Desktop when the docker engine is unavailable.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test_java_e2e_stub.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test_java_e2e_stub.ps1 -SkipBuild -KeepEnvironment
#>
[CmdletBinding()]
param(
    [switch] $SkipBuild,
    [switch] $KeepEnvironment,
    [switch] $NoDockerStart,
    [switch] $SkipPlaywright,
    [string] $PlaywrightGrep = '',
    [int] $StubPort = 18090,
    [int] $JavaPort = 18003,
    [int] $FrontendPort = 3901,
    [int] $DbPort = 5434,
    [string] $LogRoot = ''
)

$ErrorActionPreference = 'Stop'
$script:root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$script:frontend = Join-Path $script:root 'frontend\nextjs'
$script:javaDir = Join-Path $script:root 'backend-java'
$script:jar = Join-Path $script:javaDir 'target\researchrunner-api-0.1.0-SNAPSHOT.jar'
$script:javaExe = Join-Path $script:javaDir '.tools\jdk-21.0.12.1+1\bin\java.exe'
$script:buildScript = Join-Path $PSScriptRoot 'java_build.ps1'
$script:stubScript = Join-Path $PSScriptRoot 'stub_provider_server.py'

if (-not $LogRoot) {
    $stamp = (Get-Date -Format 'yyyyMMdd-HHmmss')
    $LogRoot = Join-Path $script:javaDir ("target\e2e-stub\$stamp")
}
$script:logRoot = (New-Item -ItemType Directory -Force -Path $LogRoot).FullName
$script:logDir = (New-Item -ItemType Directory -Force -Path (Join-Path $script:logRoot 'logs')).FullName
$script:playwrightLog = Join-Path $script:logDir 'playwright.log'
$script:playwrightResults = Join-Path $script:logRoot 'playwright-results'
$script:frontendCopy = Join-Path $script:logRoot 'frontend-copy'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$script:networkName = "researchrunner-e2e-$stamp-$PID-net"
$script:dbName = "researchrunner-e2e-$stamp-$PID-db"

$script:processes = @()
$script:savedEnv = @{}
$script:dbCreated = $false
$script:networkCreated = $false
$script:playwrightExit = 1
$script:failure = $null
$script:playwrightSummary = [ordered]@{ passed = 0; failed = 0; skipped = 0; total = 0 }
$script:cleanupReport = [ordered]@{}
$script:stubStats = $null
$script:buildSeconds = 0
$script:startedAt = Get-Date

# --------------------------------------------------------------------------- helpers
function Set-E2EEnv([string] $name, [string] $value) {
    if (-not $script:savedEnv.ContainsKey($name)) {
        $script:savedEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

function Restore-E2EEnv {
    foreach ($key in @($script:savedEnv.Keys)) {
        [Environment]::SetEnvironmentVariable($key, $script:savedEnv[$key], 'Process')
    }
    $script:savedEnv = @{}
}

function Start-LoggedProcess([string] $name, [string] $file, [string[]] $arguments, [string] $workingDirectory) {
    $out = Join-Path $script:logDir "$name.out.log"
    $err = Join-Path $script:logDir "$name.err.log"
    $proc = Start-Process -FilePath $file -ArgumentList $arguments -WorkingDirectory $workingDirectory `
        -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Hidden
    $script:processes += [pscustomobject]@{ Name = $name; Proc = $proc; Out = $out; Err = $err }
    Write-Host ("[e2e] 启动 {0}（PID {1}）" -f $name, $proc.Id)
    return $proc
}

function Remove-E2EEnv([string] $name) {
    if (-not $script:savedEnv.ContainsKey($name)) {
        $script:savedEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
}

# Runs a native command synchronously with file redirection and returns its exit code.
# PowerShell 5.1 turns native stderr into a terminating NativeCommandError when
# $ErrorActionPreference is Stop, so the preference is relaxed for the call only; the exit
# code (rather than stderr traffic) is what decides success.
function Invoke-NativeLogged([string] $file, [string[]] $arguments, [string] $workingDirectory,
        [string] $logOut, [string] $logErr) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    Push-Location $workingDirectory
    try {
        & $file @arguments 1> $logOut 2> $logErr
        $code = $LASTEXITCODE
    }
    finally {
        Pop-Location
        $ErrorActionPreference = $previousPreference
    }
    return $code
}

function Wait-ForHttp([string] $url, [int] $timeoutSeconds, [scriptblock] $predicate = $null) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    $last = '未发起请求'
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                if ($null -eq $predicate) { return $response }
                $json = $null
                try { $json = $response.Content | ConvertFrom-Json } catch { }
                if (($null -ne $json) -and (& $predicate $json)) { return $json }
                $last = 'HTTP 200 但条件未满足'
            }
            else {
                $last = "HTTP $($response.StatusCode)"
            }
        }
        catch {
            $last = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 750
    }
    throw "等待 $url 超时（最后状态：$last）"
}

function Assert-PortFree([int] $port) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    if ($listener) {
        $owner = @($listener)[0].OwningProcess
        $name = 'unknown'
        try { $name = (Get-Process -Id $owner -ErrorAction Stop).ProcessName } catch { }
        throw "端口 $port 已被占用（PID $owner / $name）。请先停止该进程再运行本脚本。"
    }
}

function Test-DockerEngine {
    & docker version --format '{{.Server.Version}}' 2>$null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

function Invoke-Docker([string[]] $dockerArgs) {
    & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) {
        throw ("docker {0} 失败（退出码 {1}）" -f ($dockerArgs -join ' '), $LASTEXITCODE)
    }
}

function Get-LogTail([string] $path, [int] $lines = 40) {
    if (Test-Path -LiteralPath $path) {
        return (Get-Content -LiteralPath $path -Tail $lines -ErrorAction SilentlyContinue) -join "`n"
    }
    return '(日志不可用)'
}

function Stop-PortOwner([int] $port, [string] $label) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    foreach ($entry in @($listener)) {
        if ($null -eq $entry) { continue }
        $owner = $entry.OwningProcess
        $processName = ''
        try { $processName = (Get-Process -Id $owner -ErrorAction Stop).ProcessName } catch { continue }
        if ($processName -match '^(java|node|python|cmd)$') {
            Write-Host ("[e2e] 清理 {0} 端口 {1} 的残留进程 PID {2}（{3}）" -f $label, $port, $owner, $processName)
            & taskkill /PID $owner /T /F 2>$null | Out-Null
        }
        else {
            Write-Warning ("[e2e] 端口 {0} 仍被 PID {1}（{2}）占用，未自动结束" -f $port, $owner, $processName)
        }
    }
}

# --------------------------------------------------------------------------- preflight
Write-Host "=============================================================="
Write-Host " ResearchRunner Java 全流程 E2E（本地 stub，无真实供应商调用）"
Write-Host " 日志目录: $($script:logRoot)"
Write-Host " 端口: stub=$StubPort java=$JavaPort frontend=$FrontendPort postgres=$DbPort"
Write-Host "=============================================================="

foreach ($required in @($script:frontend, $script:javaDir, $script:buildScript, $script:stubScript, $script:javaExe)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "缺少运行依赖：$required" }
}
$python = (Get-Command python -ErrorAction Stop).Source
$nodeModules = Join-Path $script:frontend 'node_modules'
if (-not (Test-Path -LiteralPath (Join-Path $nodeModules 'playwright'))) {
    throw "缺少 frontend/nextjs/node_modules（请先在 frontend/nextjs 执行 npm install --legacy-peer-deps）"
}
if (-not (Test-Path -LiteralPath (Join-Path $nodeModules '.bin\next.cmd'))) {
    throw "缺少 frontend/nextjs/node_modules/.bin/next.cmd"
}
$chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
if (-not (Test-Path -LiteralPath $chrome)) {
    throw "未找到系统 Chrome：$chrome（Playwright 使用 channel: chrome，不下载浏览器）"
}

try {
    foreach ($port in @($StubPort, $JavaPort, $FrontendPort, $DbPort)) {
        Assert-PortFree $port
    }

    $dockerDesktop = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    if (-not (Test-DockerEngine)) {
        if ($NoDockerStart) { throw 'Docker 引擎不可用（已指定 -NoDockerStart）' }
        if (-not (Test-Path -LiteralPath $dockerDesktop)) { throw 'Docker 引擎不可用，且未找到 Docker Desktop' }
        Write-Host '[e2e] Docker 引擎不可用，正在启动 Docker Desktop…'
        Start-Process -FilePath $dockerDesktop | Out-Null
        $deadline = (Get-Date).AddSeconds(300)
        while (-not (Test-DockerEngine)) {
            if ((Get-Date) -gt $deadline) { throw 'Docker Desktop 在 300 秒内未就绪' }
            Start-Sleep -Seconds 5
        }
    }
    Write-Host ('[e2e] Docker 引擎就绪：{0}' -f (& docker version --format '{{.Server.Version}}'))

    $imageExists = $false
    & docker image inspect pgvector/pgvector:pg16 *> $null
    if ($LASTEXITCODE -eq 0) { $imageExists = $true }
    if (-not $imageExists) {
        Write-Host '[e2e] 拉取 pgvector/pgvector:pg16（仅镜像，不涉及 compose 数据卷）'
        Invoke-Docker @('pull', 'pgvector/pgvector:pg16')
    }

    # ------------------------------------------------------------- 1. build
    if (-not $SkipBuild) {
        Write-Host '[e2e] 构建 Java JAR（scripts/java_build.ps1 -DskipTests package，全局串行锁）'
        $buildLog = Join-Path $script:logDir 'java-build.log'
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:buildScript '-DskipTests' 'package' 2>&1 |
            Tee-Object -FilePath $buildLog
        if ($LASTEXITCODE -ne 0) {
            throw "Java 构建失败（退出码 $LASTEXITCODE，日志：$buildLog）。若其他 teammate 正在编辑 Java 源码，请待其完成后再运行。"
        }
        $buildTimeMatch = [regex]::Match(((Get-Content -LiteralPath $buildLog -ErrorAction SilentlyContinue) -join "`n"), 'Total time:\s+([0-9.]+)\s*s')
        if ($buildTimeMatch.Success) { $script:buildSeconds = [double] $buildTimeMatch.Groups[1].Value }
    }
    if (-not (Test-Path -LiteralPath $script:jar)) { throw "缺少 JAR：$($script:jar)" }
    Write-Host ('[e2e] 使用 JAR：{0}（{1}）' -f $script:jar, (Get-Item -LiteralPath $script:jar).LastWriteTime)
    if ($script:buildSeconds) { Write-Host ('[e2e] Maven 构建耗时：{0:N1}s' -f $script:buildSeconds) }

    # ------------------------------------------------------------- 2. stub
    Set-E2EEnv 'STUB_REJECT_ONCE' '1'
    Set-E2EEnv 'STUB_REJECT_FACT' '1'
    Set-E2EEnv 'STUB_SLOW_MS' '1500'
    Set-E2EEnv 'STUB_DEBUG_DIR' (Join-Path $script:logDir 'stub-requests')
    $stubProc = Start-LoggedProcess 'stub' $python @('-u', $script:stubScript, '--host', '127.0.0.1', '--port', "$StubPort", '--log', (Join-Path $script:logDir 'stub.jsonl')) $script:root
    $null = Wait-ForHttp ("http://127.0.0.1:{0}/health" -f $StubPort) 60 { param($json) return ($json.status -eq 'ok') }
    Write-Host '[e2e] stub provider 就绪'

    # ------------------------------------------------------------- 3. postgres
    Write-Host "[e2e] 创建临时 pgvector 容器 $($script:dbName)（自建网络、tmpfs、隔离库）"
    Invoke-Docker @('network', 'create', $script:networkName)
    $script:networkCreated = $true
    $dbPublish = "127.0.0.1:$DbPort`:5432"
    Invoke-Docker @(
        'run', '-d', '--name', $script:dbName, '--network', $script:networkName,
        '--tmpfs', '/var/lib/postgresql/data',
        '-p', $dbPublish,
        '-e', 'POSTGRES_DB=researchrunner_e2e',
        '-e', 'POSTGRES_USER=e2e',
        '-e', 'POSTGRES_PASSWORD=e2e',
        'pgvector/pgvector:pg16'
    ) | Out-Null
    $script:dbCreated = $true
    $dbReady = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        & docker exec $script:dbName pg_isready -U e2e -d researchrunner_e2e *> $null
        if ($LASTEXITCODE -eq 0) { $dbReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $dbReady) { throw '临时 PostgreSQL 在 90 秒内未就绪' }
    Write-Host '[e2e] 临时 PostgreSQL 就绪'

    # ------------------------------------------------------------- 4. migrate
    Set-E2EEnv 'JAVA_DATABASE_URL' ("jdbc:postgresql://127.0.0.1:{0}/researchrunner_e2e" -f $DbPort)
    Set-E2EEnv 'POSTGRES_USER' 'e2e'
    Set-E2EEnv 'POSTGRES_PASSWORD' 'e2e'
    $migrateOut = Join-Path $script:logDir 'migrate.out.log'
    $migrateErr = Join-Path $script:logDir 'migrate.err.log'
    $migrateExit = Invoke-NativeLogged $script:javaExe @('-jar', 'target\researchrunner-api-0.1.0-SNAPSHOT.jar', 'migrate') `
        $script:javaDir $migrateOut $migrateErr
    if ($migrateExit -ne 0) {
        throw ("Java 迁移失败（退出码 {0}）`n{1}`n{2}" -f $migrateExit, (Get-LogTail $migrateOut), (Get-LogTail $migrateErr))
    }
    Write-Host '[e2e] Java 迁移完成'

    # ------------------------------------------------------------- 5. java api
    Set-E2EEnv 'SERVER_PORT' "$JavaPort"
    Set-E2EEnv 'JAVA_WRITES_ENABLED' 'true'
    Set-E2EEnv 'JAVA_DB_READ_ONLY' 'false'
    Set-E2EEnv 'JAVA_REQUIRE_FLYWAY' 'true'
    Set-E2EEnv 'CORS_ALLOW_ORIGINS' ("http://127.0.0.1:{0},http://localhost:{0}" -f $FrontendPort)
    Set-E2EEnv 'PUBLIC_API_URL' ("http://127.0.0.1:{0}" -f $JavaPort)
    Set-E2EEnv 'LOGGING_LEVEL_ROOT' 'INFO'
    Set-E2EEnv 'IMAGE_GENERATION_ENABLED' 'false'
    Set-E2EEnv 'JAVA_VISUALIZATION_ENABLED' 'true'
    # Chat provider (OpenAI-compatible through vllm_openai)
    Set-E2EEnv 'FAST_LLM' 'vllm_openai:stub-model'
    Set-E2EEnv 'SMART_LLM' 'vllm_openai:stub-model'
    Set-E2EEnv 'STRATEGIC_LLM' 'vllm_openai:stub-model'
    Set-E2EEnv 'VLLM_OPENAI_API_BASE' ("http://127.0.0.1:{0}/v1" -f $StubPort)
    Set-E2EEnv 'VLLM_OPENAI_API_KEY' 'stub'
    # Embeddings provider (OpenAI-compatible)
    Set-E2EEnv 'EMBEDDING' 'openai:stub-embedding'
    Set-E2EEnv 'EMBEDDING_DIMENSION' '1024'
    Set-E2EEnv 'OPENAI_BASE_URL' ("http://127.0.0.1:{0}/v1" -f $StubPort)
    Set-E2EEnv 'OPENAI_API_KEY' 'stub'
    # Custom retriever + Firecrawl scraper served by the stub
    Set-E2EEnv 'RETRIEVER' 'custom'
    Set-E2EEnv 'RETRIEVER_ENDPOINT' ("http://127.0.0.1:{0}/search" -f $StubPort)
    Set-E2EEnv 'SCRAPER' 'firecrawl'
    Set-E2EEnv 'FIRECRAWL_BASE_URL' ("http://127.0.0.1:{0}/firecrawl/scrape" -f $StubPort)
    Set-E2EEnv 'FIRECRAWL_API_KEY' 'stub'
    # The follow-up chat registers a quick_search tool only when TAVILY_API_KEY is non-blank.
    Set-E2EEnv 'TAVILY_API_KEY' ''
    Set-E2EEnv 'MAX_SECTIONS' '2'
    Set-E2EEnv 'TEMPERATURE' '0'

    $javaProc = Start-LoggedProcess 'java' $script:javaExe @('-jar', 'target\researchrunner-api-0.1.0-SNAPSHOT.jar') $script:javaDir
    try {
        $null = Wait-ForHttp ("http://127.0.0.1:{0}/health/ready" -f $JavaPort) 240 { param($json) return ($json.ready -eq $true) }
    }
    catch {
        throw ("Java 后端未就绪：{0}`n{1}" -f $_.Exception.Message, (Get-LogTail (Join-Path $script:logDir 'java.err.log')))
    }
    Write-Host '[e2e] Java 后端 /health/ready 就绪'

    # ------------------------------------------------------------- 6. frontend
    Write-Host '[e2e] 复制前端源码到隔离目录（独立 .next 缓存，node_modules 使用目录联接）'
    if (Test-Path -LiteralPath $script:frontendCopy) {
        Remove-Item -LiteralPath $script:frontendCopy -Recurse -Force -ErrorAction SilentlyContinue
    }
    $roboArgs = @($script:frontend, $script:frontendCopy, '/E', '/XD', 'node_modules', '.next', 'test-results', '/NFL', '/NDL', '/NJH', '/NJS', '/NP', '/R:1', '/W:1')
    & robocopy @roboArgs | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "复制前端源码失败（robocopy 退出码 $LASTEXITCODE）" }
    $nodeModulesLink = Join-Path $script:frontendCopy 'node_modules'
    if (Test-Path -LiteralPath $nodeModulesLink) { Remove-Item -LiteralPath $nodeModulesLink -Recurse -Force }
    New-Item -ItemType Junction -Path $nodeModulesLink -Target (Resolve-Path $nodeModules).Path | Out-Null

    Set-E2EEnv 'NEXT_PUBLIC_GPTR_API_URL' ("http://127.0.0.1:{0}" -f $JavaPort)
    Set-E2EEnv 'INTERNAL_API_URL' ("http://127.0.0.1:{0}" -f $JavaPort)
    Set-E2EEnv 'NEXT_TELEMETRY_DISABLED' '1'
    $null = Start-LoggedProcess 'frontend' 'cmd.exe' @('/d', '/c', "node_modules\.bin\next.cmd dev -p $FrontendPort") $script:frontendCopy
    try {
        $null = Wait-ForHttp ("http://127.0.0.1:{0}/" -f $FrontendPort) 300
        # Warm the home route so Playwright does not pay the first dev compile inside a test.
        $null = Invoke-WebRequest -UseBasicParsing -Uri ("http://127.0.0.1:{0}/" -f $FrontendPort) -TimeoutSec 120
    }
    catch {
        throw ("前端未就绪：{0}`n{1}" -f $_.Exception.Message, (Get-LogTail (Join-Path $script:logDir 'frontend.err.log')))
    }
    Write-Host '[e2e] Next.js dev 就绪'

    # ------------------------------------------------------------- 7. playwright
    if ($SkipPlaywright) {
        Write-Host '[e2e] -SkipPlaywright：已跳过 Playwright（用于保留环境做协议调试）'
        $script:playwrightExit = 0
    }
    else {
    Set-E2EEnv 'PLAYWRIGHT_BASE_URL' ("http://127.0.0.1:{0}" -f $FrontendPort)
    Set-E2EEnv 'E2E_API_BASE_URL' ("http://127.0.0.1:{0}" -f $JavaPort)
    Set-E2EEnv 'E2E_STUB_BASE_URL' ("http://127.0.0.1:{0}" -f $StubPort)
    Set-E2EEnv 'PLAYWRIGHT_OUTPUT_DIR' $script:playwrightResults
    Write-Host '[e2e] 运行 Playwright full-flow（真实联调，无接口 mock）'
    $pwOut = Join-Path $script:logDir 'playwright.out.log'
    $pwErr = Join-Path $script:logDir 'playwright.err.log'
    # NO_COLOR/FORCE_COLOR may both be present in the host shell; drop both so the captured
    # log is plain text and Node does not emit a color-env warning on stderr.
    Remove-E2EEnv 'FORCE_COLOR'
    Remove-E2EEnv 'NO_COLOR'
    $playwrightCommand = 'node_modules\.bin\playwright.cmd test full-flow --reporter=list --workers=1'
    if ($PlaywrightGrep) {
        $playwrightCommand = $playwrightCommand + (' -g "{0}"' -f $PlaywrightGrep)
    }
    $script:playwrightExit = Invoke-NativeLogged 'cmd.exe' @('/d', '/c', $playwrightCommand) `
        $script:frontend $pwOut $pwErr
    $combined = ((Get-Content -LiteralPath $pwOut -ErrorAction SilentlyContinue) + (Get-Content -LiteralPath $pwErr -ErrorAction SilentlyContinue)) -join "`n"
    Set-Content -LiteralPath $script:playwrightLog -Value $combined -Encoding UTF8

    $passedMatch = [regex]::Match($combined, '(\d+)\s+passed')
    $failedMatch = [regex]::Match($combined, '(\d+)\s+failed')
    $skippedMatch = [regex]::Match($combined, '(\d+)\s+skipped')
    if ($passedMatch.Success) { $script:playwrightSummary['passed'] = [int] $passedMatch.Groups[1].Value }
    if ($failedMatch.Success) { $script:playwrightSummary['failed'] = [int] $failedMatch.Groups[1].Value }
    if ($skippedMatch.Success) { $script:playwrightSummary['skipped'] = [int] $skippedMatch.Groups[1].Value }
    $script:playwrightSummary['total'] = $script:playwrightSummary['passed'] + $script:playwrightSummary['failed'] + $script:playwrightSummary['skipped']
    Write-Host ("[e2e] Playwright 结果：passed={0} failed={1} skipped={2} exit={3}" -f $script:playwrightSummary.passed, $script:playwrightSummary.failed, $script:playwrightSummary.skipped, $script:playwrightExit)
    }
}
catch {
    $script:failure = $_
    $script:playwrightExit = 1
    Write-Host ("[e2e] 运行失败：{0}" -f $_.Exception.Message) -ForegroundColor Red
    if ($_.ScriptStackTrace) { Write-Host $_.ScriptStackTrace }
}
finally {
    Restore-E2EEnv

    # Capture stub stage counters before any cleanup so the summary always records which
    # stages actually ran (planner/reviewer/reviser/writer/...).
    try {
        if ($script:processes -and ($script:processes | Where-Object { $_.Name -eq 'stub' })) {
            $script:stubStats = Invoke-RestMethod -Uri ("http://127.0.0.1:{0}/control/stats" -f $StubPort) -TimeoutSec 5
        }
    }
    catch { }

    if ($KeepEnvironment) {
        Write-Host '[e2e] -KeepEnvironment 已指定，保留进程与容器：'
        foreach ($entry in $script:processes) {
            Write-Host ("        {0}: PID {1}，日志 {2}" -f $entry.Name, $entry.Proc.Id, $entry.Out)
        }
        Write-Host ("        容器：{0}，网络：{1}" -f $script:dbName, $script:networkName)
        Write-Host ("        前端：http://127.0.0.1:{0}  Java：http://127.0.0.1:{1}  stub：http://127.0.0.1:{2}" -f $FrontendPort, $JavaPort, $StubPort)
    }
    else {
        Write-Host '[e2e] 清理进程、容器与端口…'
        foreach ($entry in $script:processes) {
            if ($entry.Name -eq 'playwright') { continue }
            $alive = $false
            try { $alive = ($null -ne $entry.Proc) -and (-not $entry.Proc.HasExited) } catch { $alive = $false }
            if ($alive) {
                & taskkill /PID $entry.Proc.Id /T /F 2>$null | Out-Null
            }
        }
        Start-Sleep -Seconds 2
        foreach ($portPair in @(@($FrontendPort, 'frontend'), @($JavaPort, 'java'), @($StubPort, 'stub'))) {
            Stop-PortOwner $portPair[0] $portPair[1]
        }
        if ($script:dbCreated) {
            & docker rm -f $script:dbName *> $null
        }
        if ($script:networkCreated) {
            & docker network rm $script:networkName *> $null
        }
        if (Test-Path -LiteralPath $script:frontendCopy) {
            Remove-Item -LiteralPath $script:frontendCopy -Recurse -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Seconds 1
        $leftoverPorts = @()
        foreach ($port in @($StubPort, $JavaPort, $FrontendPort, $DbPort)) {
            $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
            if ($listener) { $leftoverPorts += $port }
        }
        $leftoverContainers = @()
        & docker ps -a --filter "name=$($script:dbName)" --format '{{.Names}}' 2>$null | ForEach-Object {
            if ($_ -and $_.Trim().Length -gt 0) { $leftoverContainers += $_ }
        }
        $leftoverNetworks = @()
        & docker network ls --filter "name=$($script:networkName)" --format '{{.Name}}' 2>$null | ForEach-Object {
            if ($_ -and $_.Trim().Length -gt 0) { $leftoverNetworks += $_ }
        }
        $script:cleanupReport.ports_free = ($leftoverPorts.Count -eq 0)
        $script:cleanupReport.leftover_ports = $leftoverPorts
        $script:cleanupReport.containers_removed = ($leftoverContainers.Count -eq 0)
        $script:cleanupReport.leftover_containers = $leftoverContainers
        $script:cleanupReport.networks_removed = ($leftoverNetworks.Count -eq 0)
        $script:cleanupReport.leftover_networks = $leftoverNetworks
        if ($leftoverPorts.Count -gt 0) { Write-Warning ("[e2e] 端口仍被占用：{0}" -f ($leftoverPorts -join ', ')) }
        if ($leftoverContainers.Count -gt 0) { Write-Warning ("[e2e] 容器未删除：{0}" -f ($leftoverContainers -join ', ')) }
        if ($leftoverNetworks.Count -gt 0) { Write-Warning ("[e2e] 网络未删除：{0}" -f ($leftoverNetworks -join ', ')) }
        Write-Host ('[e2e] 清理完成：端口空闲={0}，容器已删除={1}，网络已删除={2}' -f $script:cleanupReport.ports_free, $script:cleanupReport.containers_removed, $script:cleanupReport.networks_removed)
    }

    # ------------------------------------------------------------- summary
    $finishedAt = Get-Date
    $summary = [ordered]@{
        started_from = $PSScriptRoot
        log_root = $script:logRoot
        started_at = $script:startedAt.ToString('o')
        finished_at = $finishedAt.ToString('o')
        duration_seconds = [math]::Round(($finishedAt - $script:startedAt).TotalSeconds, 1)
        build_seconds = $script:buildSeconds
        exit_code = $script:playwrightExit
        failure = $(if ($null -ne $script:failure) { $script:failure.Exception.Message } else { $null })
        playwright = $script:playwrightSummary
        playwright_log = $script:playwrightLog
        playwright_results = $script:playwrightResults
        build_log = (Join-Path $script:logDir 'java-build.log')
        java_log = (Join-Path $script:logDir 'java.out.log')
        java_error_log = (Join-Path $script:logDir 'java.err.log')
        frontend_log = (Join-Path $script:logDir 'frontend.out.log')
        frontend_error_log = (Join-Path $script:logDir 'frontend.err.log')
        stub_log = (Join-Path $script:logDir 'stub.jsonl')
        stub_stdout_log = (Join-Path $script:logDir 'stub.out.log')
        cleanup = $script:cleanupReport
        kept_environment = [bool] $KeepEnvironment
        stub_stats = $script:stubStats
    }
    $summaryPath = Join-Path $script:logRoot 'summary.json'
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding UTF8

    Write-Host '--------------------------------------------------------------'
    Write-Host ('[e2e] 退出码：{0}（passed={1} failed={2} skipped={3}）' -f $script:playwrightExit, $script:playwrightSummary.passed, $script:playwrightSummary.failed, $script:playwrightSummary.skipped)
    Write-Host ("[e2e] 汇总：{0}" -f $summaryPath)
    Write-Host ("[e2e] Playwright 日志：{0}" -f $script:playwrightLog)
    Write-Host ("[e2e] Java 日志：{0}" -f (Join-Path $script:logDir 'java.out.log'))
    Write-Host ("[e2e] 前端日志：{0}" -f (Join-Path $script:logDir 'frontend.out.log'))
    Write-Host '--------------------------------------------------------------'
}

if ($null -ne $script:failure) { exit 1 }
exit $script:playwrightExit
