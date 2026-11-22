# Windows publisher signing

For 0.4.26 the owner-approved unsigned publisher exception may be selected
explicitly with the existing local publisher `--allow-unsigned-publisher` flag.
This does not relax Ed25519 update verification, add a Windows publisher
certificate or change Smart App Control. CI builds unsigned artifacts only and
never publishes them. The trusted-signing workflow below remains the default
when a real signing identity becomes available.

0.4.11 is unsigned Authenticode and is blocked by Smart App Control on the release PC. Its existing Ed25519 update signature verifies the download, not the Windows publisher. Do not replace the Ed25519 key and do not publish different bytes under 0.4.11.

A trusted public code-signing identity is required. The current Windows CurrentUser/My and LocalMachine/My stores contain no code-signing certificate with an available key. Identity validation and account setup must be completed by the owner. Do not generate a self-signed certificate, modify root stores, disable Smart App Control, or collect a PFX password through chat to pretend this is solved.

After enrolling with a trusted issuer, bump the app/release version. For a certificate available through the Windows certificate store/token, set `KAWA_SIGNTOOL`, `KAWA_CERT_THUMBPRINT`, and `KAWA_TIMESTAMP_URL` locally; run `sign-stage.ps1 -Stage STAGE`. Do not export the certificate key to the application/server/source archive. An Artifact Signing/cloud-provider adapter will instead require the approved service configuration; the current wrapper targets the Windows certificate store.

Build NSIS with `KAWA_SIGN_COMMAND` set to a trusted wrapper calling `sign-file.ps1` and accepting the final path as its last argument. `signing.nsh` signs and verifies both the embedded uninstaller and the outer installer during compilation. Sign stage binaries before packaging them. Preserve Wintun's valid upstream signature.

Run `verify-authenticode.ps1 -Installer INSTALLER -Stage STAGE`. The publisher now requires `--windows-stage STAGE` and runs this gate before reading the update signing key or connecting over SSH. It rejects unsigned, invalid, self-signed-leaf, and untimestamped signatures. Recreate portable/source artifacts and SHA256 manifests from the final signed bytes. Then publish the new immutable version with the existing Ed25519 update key and validate the public download as before.

Finally test install, update, uninstall, GUI, helper startup, Xray and Wintun on Windows with Smart App Control enabled. Trusted signing addresses publisher verification; do not promise that Microsoft reputation/malware analysis can never produce another warning.

References: [Microsoft Smart App Control signing](https://learn.microsoft.com/en-us/windows/apps/develop/smart-app-control/code-signing-for-smart-app-control), [signing options and eligibility](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options), [NSIS finalizer commands](https://nsis.sourceforge.io/Docs/Chapter5.html).
