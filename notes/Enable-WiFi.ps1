param([switch]$Elevated)
$ErrorActionPreference='Stop'
$notesPath=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'runtime/node.exe'))
if(-not (Test-Path -LiteralPath $notesPath)){throw 'Runtime Notes introuvable'}
$isAdmin=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if(-not $isAdmin){
    Start-Process -FilePath 'powershell.exe' -Verb RunAs -WindowStyle Hidden -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$PSCommandPath+'"'),'-Elevated')
    exit
}
try {
    $existing=Get-NetFirewallRule -Name 'EchoAllNotes-Lan-4319' -ErrorAction SilentlyContinue
    if($existing){$existing | Remove-NetFirewallRule}
    New-NetFirewallRule -Name 'EchoAllNotes-Lan-4319' -DisplayName 'Echo-All Notes - WiFi local' -Direction Inbound -Action Allow -Program $notesPath -Protocol TCP -LocalPort 4319 -RemoteAddress LocalSubnet -Profile Public,Private -EdgeTraversalPolicy Block | Out-Null
    Set-Content -LiteralPath (Join-Path $PSScriptRoot 'wifi-autorise.txt') -Value 'Accès autorisé uniquement au réseau local, TCP 4319, pour le moteur Echo-All Notes.'
} catch {
    Set-Content -LiteralPath (Join-Path $PSScriptRoot 'wifi-erreur.txt') -Value $_.Exception.Message
    Add-Type -AssemblyName PresentationFramework
    [System.Windows.MessageBox]::Show($_.Exception.Message,'Echo-All Notes') | Out-Null
}
