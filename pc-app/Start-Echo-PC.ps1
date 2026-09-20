# Démarre Echo-All PC et ouvre l'interface dans le navigateur.
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Error "Node.js est introuvable. Installe-le depuis https://nodejs.org puis relance ce script."
}
Start-Process -FilePath 'http://localhost:4320'
node server.mjs
