$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$serverData = Join-Path $env:LOCALAPPDATA 'Echo-All\TorrentServer'
$nodeExe = 'C:\Program Files\nodejs\node.exe'
$qbExe = 'C:\Program Files\qBittorrent\qbittorrent.exe'
if (-not (Get-Process qbittorrent -ErrorAction SilentlyContinue)) {
    Start-Process $qbExe -ArgumentList @("--profile=$serverData\qb", '--webui-port=4324') -WindowStyle Hidden
}
$running = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'node.exe' -and $_.CommandLine -like '*torrent-server\server.mjs*' }
if (-not $running) {
    Start-Process $nodeExe -ArgumentList (Join-Path $serverRoot 'server.mjs') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $serverData 'server.log') -RedirectStandardError (Join-Path $serverData 'server-error.log')
}
