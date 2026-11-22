param([Parameter(Mandatory=$true)][string]$Stage)
$ErrorActionPreference = 'Stop'
foreach ($name in @('KawaVPN.exe','KawaVPNHelper.exe','xray.exe','wintun.dll')) {
    $file = Join-Path (Resolve-Path -LiteralPath $Stage).Path $name
    $signature = Get-AuthenticodeSignature -LiteralPath $file
    # Preserve upstream's valid timestamped signature (e.g. WireGuard Wintun).
    if ($signature.Status -eq 'Valid' -and $signature.TimeStamperCertificate) { continue }
    & "$PSScriptRoot/sign-file.ps1" -Path $file
}
