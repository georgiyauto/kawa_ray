# Kawa desktop packaging

Run commands from the source root. Rust 1.99.0, locked Cargo dependencies and
vendored `vendor/DUOTUN` are required; no private checkout or deploy key is used.
Xray 26.3.27, AppImageTool 1.9.1 and AppImage runtime 20251108 are pinned with SHA-256 verification.
`packaging/common.sh` creates the source ZIP through the distribution tool and
embeds GPL, additional permission, third-party and MIT dependency notices.
The source ZIP is regenerated deterministically on every normal package build.
Package-only labs with a separately audited final snapshot may supply
`KAWA_SOURCE_ARCHIVE` and its mandatory `KAWA_SOURCE_SHA256`; a digest mismatch
aborts packaging. This prevents a refreshed lab from embedding an older source tree.

## Windows x64

On Linux install cargo-xwin/LLVM/MSVC target and NSIS 3; on Windows use Git Bash,
the MSVC Rust target, Windows SDK/Build Tools, Python 3 and NSIS 3.11 on PATH:

```
sh packaging/windows/build.sh x64
```

Outputs: `dist/KawaVPN-Setup-<version>-x64.exe` and
`dist/KawaVPN-<version>-windows-x64-portable.zip`. For prebuilt binaries set
`KAWA_SKIP_BUILD=1` and `KAWA_WINDOWS_BIN` (default target triplet release path).
The portable ZIP still requires the protected helper service for TUN; run the
installer for normal use. NSIS retains the service/security and optional signing
hooks. The owner-approved unsigned publisher exception remains explicit:
Ed25519 update signatures authenticate updates but are not Authenticode.
No certificate/key is generated or uploaded to CI. The existing local publisher
and update key remain separate from build-only CI.

## Linux x86_64 / aarch64

Build natively on Ubuntu 22.04 or equivalent with fontconfig, FreeType,
xkbcommon, Wayland/X11, OpenSSL, GTK development packages, Python, dpkg-deb,
curl, unzip, file and desktop-file-utils. Baseline glibc is 2.35. Packages require
systemd, polkit and Linux kernel 5.7 or newer; OpenRC/runit integration is not supplied.

```
sh packaging/linux/stage.sh x86_64
sh packaging/linux/packages.sh x86_64
sh packaging/linux/kawa-appimage.sh x86_64
sh packaging/linux/smoke-test.sh x86_64
```

Use `aarch64` for ARM64. `KAWA_SKIP_BUILD=1` and `KAWA_LINUX_BIN` select verified
prebuilt binaries. Scripts refuse stale stage/AppDir directories: inspect and
remove only the architecture's generated directories before repeating a build.
Install the deb using apt. The package owns `/usr/libexec/kawa-vpn/KawaVPNHelper`;
only this root-owned helper configures `kawa-vpn-helper.service`, IPC
`/var/run/kawa-vpn-helper.sock`, and an explicit nonzero allowed UID. If apt does
not preserve SUDO_UID, the first GUI helper repair requests that account's UID.
AppImage alone cannot securely install a root helper; install the matching deb
first. No extracted user-writable binary is elevated to copy itself.
Uninstall removes the Kawa service through its native CLI and leaves account data
and other applications' helpers alone. Package scripts do not remove shared
legacy service names. Smoke tests inspect packages without changing host network;
privileged lifecycle tests belong in an isolated systemd environment.

## macOS Intel / Apple Silicon

On macOS 12 or newer with Xcode tools, Rust target, Python 3, curl, unzip available:

```
sh packaging/macos/build.sh aarch64
sh packaging/macos/build.sh x86_64
```

DMG contains `Kawa VPN.app`, Applications link and a separate helper `.pkg`.
Copy the app to `/Applications`, then use macOS Installer to install the helper
at `/Library/PrivilegedHelperTools/pro.vinnikov.kawavpn.helper`. Installer gives
root ownership and 0755 permissions; postinstall authorizes the console UID only
when it is nonzero. The app invokes this protected binary for maintenance.
Bundle ID is `pro.vinnikov.kawavpn`; helper launchd label adds `.helper`.
App/helper signatures are ad hoc; these artifacts are not Developer ID signed
or notarized. Package creation and `codesign`/DMG verification require macOS and
do not establish that Keychain, networking or helper lifecycle was device tested.

## CI and publication

`.github/workflows/release.yml` is a manually dispatched, read-only matrix. It
stages artifacts and never publishes a release, signs an update manifest or
mutates another repository. Actions and Rust/tool downloads are pinned; operating
system SDK packages come from the runner distribution. Android CI is maintained
separately alongside its Gradle/native dependency locks. Owner repository URL is
pending: no workflow has been pushed or dispatched for this work. Legacy AUR,
Arch repository and generic upstream publisher entrypoints deliberately fail
until an owner-specific distribution destination is configured. Existing Kawa
local publisher scripts require a separately authorized release operation.

`python3 packaging/test_packaging.py` checks the packaging security/naming
contracts. Package/build success is recorded only after actual target execution;
this source documentation alone is not a claim of macOS or ARM device validation.

## Isolated build lab

`python3 packaging/lab.py --help` documents explicit prepare/start/status/fetch
actions. Credentials and pinned known_hosts are passed by path and remain outside
the source tree. It uses existing Docker images/caches, 2 CPUs, 3GiB memory and
512 PIDs, drops capabilities, checks disk space and refuses concurrent Kawa builds.
Linux and Windows must be started sequentially. The lab uses GUI release opt-level
1 to reuse the bounded-memory cache; package/source version stays 0.4.26. Full logs
and exit markers are retained by run ID. It never installs into the build host.
