$ErrorActionPreference = "Stop"

docker compose --file "$PSScriptRoot\compose.yml" down

if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose shutdown failed with exit code $LASTEXITCODE."
}
