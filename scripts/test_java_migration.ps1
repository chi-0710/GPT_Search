param([string]$MavenImage = 'maven:3.9.9-eclipse-temurin-21')
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskJava = Join-Path $taskRoot 'backend-java'
$taskName = "researchrunner-migration-test-$PID"
$taskNetwork = "$taskName-net"
$taskCache = Join-Path $taskJava '.m2/.m2/repository'

function Invoke-CheckedDocker {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed with exit code $LASTEXITCODE" }
}

# These newly created resources are isolated from the Compose application and its data volume.
$createdNetwork = $false
$createdDatabase = $false
try {
    Invoke-CheckedDocker network create $taskNetwork
    $createdNetwork = $true
    Invoke-CheckedDocker run -d --name $taskName --network $taskNetwork --network-alias test-db `
        --tmpfs /var/lib/postgresql/data -e POSTGRES_DB=researchrunner_java_write_test `
        -e POSTGRES_USER=java_test -e POSTGRES_PASSWORD=java_test pgvector/pgvector:pg16
    $createdDatabase = $true
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        & docker exec $taskName pg_isready -h 127.0.0.1 -U java_test -d researchrunner_java_write_test
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Isolated PostgreSQL did not become ready' }
    Invoke-CheckedDocker exec $taskName createdb -U java_test researchrunner_java_test
    Invoke-CheckedDocker run --rm --network $taskNetwork --entrypoint python `
        -e DATABASE_URL=postgresql+asyncpg://java_test:java_test@test-db:5432/researchrunner_java_write_test `
        --mount "type=bind,source=$taskRoot,target=/usr/src/app,readonly" `
        gptresearcher/gpt-researcher -m alembic upgrade head
    New-Item -ItemType Directory -Force -Path $taskCache | Out-Null
    Invoke-CheckedDocker run --rm --network $taskNetwork -w /workspace `
        -e JAVA_TEST_DATABASE_URL=jdbc:postgresql://test-db:5432/researchrunner_java_test `
        -e JAVA_WRITE_TEST_DATABASE_URL=jdbc:postgresql://test-db:5432/researchrunner_java_write_test `
        --mount "type=bind,source=$taskJava,target=/workspace" `
        --mount "type=bind,source=$taskCache,target=/root/.m2/repository" `
        $MavenImage mvn -B -ntp -s maven-settings.xml verify
} finally {
    if ($createdDatabase) { & docker rm -f $taskName | Out-Null }
    if ($createdNetwork) { & docker network rm $taskNetwork | Out-Null }
}
