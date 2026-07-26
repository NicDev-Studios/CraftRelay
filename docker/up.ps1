$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot

& "$repositoryRoot\gradlew.bat" `
    :craftrelay-platform-paper:shadowJar `
    :craftrelay-platform-velocity:shadowJar `
    --no-daemon

if ($LASTEXITCODE -ne 0) {
    throw "CraftRelay plugin build failed with exit code $LASTEXITCODE."
}

docker compose --file "$PSScriptRoot\compose.yml" up --detach --wait

if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose startup failed with exit code $LASTEXITCODE."
}

Write-Host "CraftRelay is available at localhost:25565."
