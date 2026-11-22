param(
    [Parameter(Mandatory=$true)][string]$Version,
    [Parameter(Mandatory=$true)][string]$Installer,
    [Parameter(Mandatory=$true)][string]$WindowsStage,
    [Parameter(Mandatory=$true)][string]$SigningKey,
    [Parameter(Mandatory=$true)][string]$SshKey,
    [Parameter(Mandatory=$true)][string]$KnownHosts,
    [string]$Notes = "Обновление Kawa VPN",
    [int]$InterfaceIndex = 0,
    [string]$Python = "python"
)
$ErrorActionPreference = 'Stop'
& $Python "$PSScriptRoot/publish-kawa.py" --version $Version --installer $Installer --windows-stage $WindowsStage --signing-key $SigningKey --ssh-key $SshKey --known-hosts $KnownHosts --notes $Notes --interface-index $InterfaceIndex
if ($LASTEXITCODE -ne 0) { throw "Publishing failed ($LASTEXITCODE)" }
