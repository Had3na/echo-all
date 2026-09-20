$ErrorActionPreference = 'Stop'
trap { Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show($_.Exception.Message, 'Echo-All Notes') | Out-Null; exit 1 }
$notesDirectory = $PSScriptRoot
$notesData = Join-Path $env:LOCALAPPDATA 'EchoAllNotes'
New-Item -ItemType Directory -Force -Path $notesData | Out-Null
$env:ECHO_DATA = Join-Path $notesData 'Server'
$env:HOST = '0.0.0.0'
$env:ECHO_LAN = '1'
$env:PORT = '4319'
$healthy = $false
try { $response = Invoke-WebRequest -Uri 'http://127.0.0.1:4319/' -UseBasicParsing -TimeoutSec 2; $healthy = ($response.Content -like '*<title>Echo-All*Notes</title>*') } catch {}
if (-not $healthy) {
    $node = Join-Path $notesDirectory 'runtime/node.exe'
    if (-not (Test-Path -LiteralPath $node)) { throw 'Le runtime Node manque. Extrais tout le dossier ZIP avant de lancer Notes.' }
    $server = Join-Path $notesDirectory 'server.mjs'
    Start-Process -FilePath $node -ArgumentList @('"' + $server + '"') -WorkingDirectory $notesDirectory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $notesData 'server.log') -RedirectStandardError (Join-Path $notesData 'server-error.log') | Out-Null
    for ($attempt=0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Milliseconds 200
        try { $response = Invoke-WebRequest -Uri 'http://127.0.0.1:4319/' -UseBasicParsing -TimeoutSec 1; if ($response.Content -like '*<title>Echo-All*Notes</title>*') { $healthy=$true; break } } catch {}
    }
}
if (-not $healthy) { throw 'Notes ne peut pas démarrer. Le port 4319 est peut-être occupé. Consulte les journaux dans AppData/Local/EchoAllNotes.' }
$edgePaths = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe", "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe")
$edge = $edgePaths | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if ($edge) { Start-Process -FilePath $edge -ArgumentList @('--app=http://127.0.0.1:4319/', '--user-data-dir="' + (Join-Path $notesData 'Browser') + '"') }
else { Start-Process 'http://127.0.0.1:4319/' }
