param([ValidateSet('web','local','hybrid')][string]$Source = 'web', [int]$Port = 18002)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskJava = Join-Path $taskRoot 'backend-java'
$ledgerPath = Join-Path $taskJava 'acceptance/live-runs.json'
$output = Join-Path $taskJava 'target/live-acceptance'
$name = "researchrunner-live-$PID"
$network = "$name-net"
$created = [Collections.Generic.List[string]]::new()
$environmentBefore = @{}
$ledger = @()
if (Test-Path -LiteralPath $ledgerPath) { $ledger = @(Get-Content -Raw -LiteralPath $ledgerPath | ConvertFrom-Json) }
if ($ledger.Count -ge 6) { throw 'Six-run acceptance budget exhausted; no provider request made.' }
New-Item -ItemType Directory -Force -Path $output,(Split-Path $ledgerPath) | Out-Null
function Docker-Checked {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw "Docker operation failed ($LASTEXITCODE)" }
}
function Save-Ledger { ConvertTo-Json -Depth 8 -InputObject @($script:ledger) | Set-Content -Encoding utf8 -LiteralPath $ledgerPath }
function Set-TaskEnvironment([string]$key,[string]$value) {
    if (-not $environmentBefore.ContainsKey($key)) { $environmentBefore[$key]=[Environment]::GetEnvironmentVariable($key,'Process') }
    [Environment]::SetEnvironmentVariable($key,$value,'Process')
}
$attempt = $null
try {
    # Read only required provider settings. Values are passed through process environment, never command text/logs.
    $allowed = @('FAST_LLM','SMART_LLM','STRATEGIC_LLM','FAST_TOKEN_LIMIT','SMART_TOKEN_LIMIT','STRATEGIC_TOKEN_LIMIT','LLM_KWARGS','DEEPSEEK_API_KEY','DASHSCOPE_API_KEY','DASHSCOPE_BASE_URL','EMBEDDING','EMBEDDING_DIMENSION','EMBEDDING_KWARGS','TAVILY_API_KEY','RETRIEVER','TEMPERATURE')
    foreach ($line in Get-Content -LiteralPath (Join-Path $taskRoot '.env')) {
        if ($line -match '^\s*([A-Z_0-9]+)\s*=\s*(.*?)\s*$' -and $allowed -contains $Matches[1]) {
            $key=$Matches[1];$value=$Matches[2]
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value=$value.Substring(1,$value.Length-2) }
            Set-TaskEnvironment $key $value
        }
    }
    foreach ($key in @('DEEPSEEK_API_KEY','DASHSCOPE_API_KEY','TAVILY_API_KEY','FAST_LLM','SMART_LLM','STRATEGIC_LLM')) {
        if (-not [Environment]::GetEnvironmentVariable($key,'Process')) { throw "Required configuration absent: $key" }
    }
    Docker-Checked network create $network | Out-Null
    $created.Add('network')
    Docker-Checked run -d --name "$name-db" --network $network --network-alias live-db --tmpfs /var/lib/postgresql/data `
        -e POSTGRES_DB=researchrunner_live_test -e POSTGRES_USER=java_test -e POSTGRES_PASSWORD=java_test pgvector/pgvector:pg16 | Out-Null
    $created.Add('db')
    $ready=$false
    for($i=0;$i -lt 30;$i++) { & docker exec "$name-db" pg_isready -U java_test -d researchrunner_live_test *> $null; if($LASTEXITCODE -eq 0){$ready=$true;break};Start-Sleep 1 }
    if(-not $ready){throw 'Isolated PostgreSQL unavailable'}
    $common=@('--network',$network,'-w','/workspace','--mount',"type=bind,source=$taskJava,target=/workspace,readonly",'-e','JAVA_DATABASE_URL=jdbc:postgresql://live-db:5432/researchrunner_live_test','-e','POSTGRES_USER=java_test','-e','POSTGRES_PASSWORD=java_test')
    Docker-Checked run --rm @common maven:3.9.9-eclipse-temurin-21 java -jar target/researchrunner-api-0.1.0-SNAPSHOT.jar migrate *> (Join-Path $output "$Source-migrate.log")
    $providerArgs=@()
    foreach($key in $allowed){if([Environment]::GetEnvironmentVariable($key,'Process')){$providerArgs+=@('-e',$key)}}
    Docker-Checked run -d --name "$name-api" @common @providerArgs -p "127.0.0.1:${Port}:8000" `
        -e JAVA_WRITES_ENABLED=true -e JAVA_DB_READ_ONLY=false -e JAVA_REQUIRE_FLYWAY=true -e IMAGE_GENERATION_ENABLED=false `
        -e JAVA_VISUALIZATION_ENABLED=true -e "PUBLIC_API_URL=http://localhost:$Port" -e LOGGING_LEVEL_ROOT=WARN `
        maven:3.9.9-eclipse-temurin-21 java -jar target/researchrunner-api-0.1.0-SNAPSHOT.jar | Out-Null
    $created.Add('api');$base="http://localhost:$Port";$ready=$false
    for($i=0;$i -lt 60;$i++){try{$health=Invoke-RestMethod "$base/health/ready" -TimeoutSec 3;if($health.ready){$ready=$true;break}}catch{};Start-Sleep 1}
    if(-not $ready){throw 'Java readiness failed'}
    $ids=@()
    if($Source -ne 'web') {
        $fixture=Join-Path $output 'local-evidence.md'
        @'
# 隔离验收资料：ResearchRunner 数据保护约定
本文是本地验收夹具，不代表外部权威资料。应用使用 PostgreSQL 保存研究报告、聊天、证据、附件和资料向量。
迁移前要导出数据库备份，在隔离数据库恢复后核对记录。命名数据卷不是备份，禁止删除原数据卷。
切换前停止旧后端接收新任务，等待任务结束，再启动新后端写入。同一应用数据库只能有一套业务写入后端。
回滚时先停止新后端写入，保留新产生的数据，再恢复旧服务；不能直接以旧备份覆盖新数据。
本次小型验收仅验证 Java 流程和来源引用，不等同于生产部署验收。
'@ | Set-Content -Encoding utf8 -LiteralPath $fixture
        $upload=Invoke-RestMethod "$base/api/documents" -Method Post -Form @{file=Get-Item -LiteralPath $fixture} -TimeoutSec 60
        $ids=@($upload.document.id);$indexed=$false
        for($i=0;$i -lt 90;$i++) {
            $documents=Invoke-RestMethod "$base/api/documents" -TimeoutSec 10
            $document=@($documents.documents | Where-Object id -eq $ids[0])[0]
            if($document.status -eq 'ready'){$indexed=$true;break}
            if($document.status -eq 'failed'){throw 'Live DashScope document indexing failed'}
            Start-Sleep 2
        }
        if(-not $indexed){throw 'Live indexing timed out'}
    }
    $attempt=[ordered]@{number=$ledger.Count+1;source=$Source;started_at=[DateTime]::UtcNow.ToString('o');status='started';max_sections=2;paid_images=$false}
    $ledger+= $attempt;Save-Ledger
    $request=@{task='用简体中文简要研究 PostgreSQL 应用从旧后端迁移到 Java 后端时的备份、验证和回滚步骤。严格依据可用来源，最多两章，不超过 1000 字。';report_source=$Source;generate_in_background=$false;max_sections=2;max_search_results=2;max_plan_revisions=1;max_draft_revisions=1;max_fact_check_revisions=1;export_pdf=$true;export_docx=$true}
    if($ids.Count){$request.document_scope='selected';$request.document_ids=$ids}
    $result=Invoke-RestMethod "$base/report/" -Method Post -ContentType 'application/json; charset=utf-8' -Body ($request | ConvertTo-Json -Depth 6) -TimeoutSec 900
    $attempt.run_id=$result.run_id;$attempt.research_id=$result.research_id
    $result | ConvertTo-Json -Depth 30 | Set-Content -Encoding utf8 -LiteralPath (Join-Path $output "$Source-result.json")
    foreach($kind in @('md','pdf','docx')) {
        $url=$result."${kind}_path";if(-not $url){throw "Missing $kind export"}
        Invoke-WebRequest "$base$url" -OutFile (Join-Path $output "$Source-report.$kind") -TimeoutSec 30
    }
    $history=Invoke-RestMethod "$base/api/reports/$($result.research_id)" -TimeoutSec 30
    $chat=Invoke-RestMethod "$base/api/chat" -Method Post -ContentType 'application/json; charset=utf-8' -Body (@{report_id=$result.research_id;messages=@(@{role='user';content='仅根据报告，简述回滚前应先做什么。不调用搜索工具。'})} | ConvertTo-Json -Depth 5) -TimeoutSec 240
    if(-not $chat.response.content){throw 'Report follow-up chat returned no content'}
    $chat | ConvertTo-Json -Depth 20 | Set-Content -Encoding utf8 -LiteralPath (Join-Path $output "$Source-chat.json")
    $attempt.status='passed';$attempt.checks=@('research','persisted_history','markdown_download','pdf_download','docx_download','followup_chat')
} catch {
    if($null -ne $attempt){$attempt.status='failed';$attempt.error_type=$_.Exception.GetType().Name}
    Write-Output "Java live acceptance failed ($Source): $($_.Exception.GetType().Name). No automatic paid retry."
    throw
} finally {
    if($null -ne $attempt){$attempt.finished_at=[DateTime]::UtcNow.ToString('o');Save-Ledger}
    if($created.Contains('api')) { & docker logs "$name-api" *> (Join-Path $output "$Source-server.log"); & docker rm -f "$name-api" *> $null }
    if($created.Contains('db')) {
        & docker exec "$name-db" pg_dump -U java_test -d researchrunner_live_test -Fc -f /tmp/live.dump
        & docker cp "${name}-db:/tmp/live.dump" (Join-Path $output "$Source-database.dump") *> $null
        & docker rm -f "$name-db" *> $null
    }
    if($created.Contains('network')){& docker network rm $network *> $null}
    foreach($entry in $environmentBefore.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
}
