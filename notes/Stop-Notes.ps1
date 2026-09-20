$ErrorActionPreference = 'Stop'
$notesDirectory = $PSScriptRoot
$notesNode = [IO.Path]::GetFullPath((Join-Path $notesDirectory 'runtime/node.exe'))
Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.ExecutablePath -eq $notesNode -and $_.CommandLine -like '*server.mjs*' } | ForEach-Object { Stop-Process -Id $_.ProcessId }
