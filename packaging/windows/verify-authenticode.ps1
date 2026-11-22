param(
    [Parameter(Mandatory=$true)][string]$Installer,
    [Parameter(Mandatory=$true)][string]$Stage
)
$ErrorActionPreference = 'Stop'
$files = @($Installer)
foreach ($name in @('KawaVPN.exe','KawaVPNHelper.exe','xray.exe','wintun.dll')) {
    $files += Join-Path $Stage $name
}
foreach ($file in $files) {
    $resolved = (Resolve-Path -LiteralPath $file).Path
    $signature = Get-AuthenticodeSignature -LiteralPath $resolved
    if ($signature.Status -ne 'Valid') {
        throw "Release blocked: $resolved has Authenticode status $($signature.Status). Obtain a trusted publisher signature before publishing."
    }
    if (-not $signature.SignerCertificate -or $signature.SignerCertificate.Subject -eq $signature.SignerCertificate.Issuer) {
        throw "Release blocked: a self-signed publisher certificate is not suitable for public Smart App Control distribution: $resolved"
    }
    if (-not $signature.TimeStamperCertificate) {
        throw "Release blocked: trusted timestamp is missing: $resolved"
    }
    Write-Output "Verified $([IO.Path]::GetFileName($resolved)): $($signature.SignerCertificate.Subject)"
}
