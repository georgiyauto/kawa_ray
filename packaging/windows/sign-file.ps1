param([Parameter(Mandatory=$true)][string]$Path)
$ErrorActionPreference = 'Stop'
# Certificate private keys remain in the OS/token/provider. No PFX password on CLI.
if (-not $env:KAWA_SIGNTOOL -or -not (Test-Path -LiteralPath $env:KAWA_SIGNTOOL -PathType Leaf)) {
    throw 'Set KAWA_SIGNTOOL to a trusted Windows SDK signtool.exe.'
}
if ($env:KAWA_CERT_THUMBPRINT -notmatch '^[0-9a-fA-F]{40}$') {
    throw 'Set KAWA_CERT_THUMBPRINT to the issued code-signing certificate thumbprint.'
}
$timestamp = [uri]$env:KAWA_TIMESTAMP_URL
if (-not $timestamp.IsAbsoluteUri -or $timestamp.Scheme -notin @('http','https')) {
    throw 'Set KAWA_TIMESTAMP_URL to the certificate provider timestamp service.'
}
$file = (Resolve-Path -LiteralPath $Path).Path
& $env:KAWA_SIGNTOOL sign /sha1 $env:KAWA_CERT_THUMBPRINT /fd SHA256 /tr $timestamp.AbsoluteUri /td SHA256 $file
if ($LASTEXITCODE -ne 0) { throw "SignTool signing failed ($LASTEXITCODE)" }
& $env:KAWA_SIGNTOOL verify /pa /all /v $file
if ($LASTEXITCODE -ne 0) { throw "SignTool verification failed ($LASTEXITCODE)" }
$signature = Get-AuthenticodeSignature -LiteralPath $file
if ($signature.Status -ne 'Valid' -or -not $signature.TimeStamperCertificate) {
    throw 'Authenticode signature or trusted timestamp is invalid.'
}
