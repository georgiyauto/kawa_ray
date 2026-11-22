# Android native engine

Kawa uses unmodified [XTLS/libXray v26.10.21](https://github.com/XTLS/libXray/tree/v26.10.21), MIT, with Xray-core MPL-2.0 at revision `1b1647cd9ddf` (full module version and hashes are in upstream `go.mod`/`go.sum`). The wrapper includes Xray's native TUN implementation; a second Go runtime or separate tun2socks library is unnecessary.

`dependencies.lock.json` pins the public AAR archive, both geographic rule files and source versions. `../tools/fetch-dependencies.py` downloads and verifies their SHA-256 before installation. Neither binaries nor private signing keys belong in the source ZIP. Both shipped ABIs, arm64-v8a and x86_64, have 16 KiB aligned ELF LOAD segments.

To build the wrapper from source, run `python3 native/build-source.py` from the Android directory with Go 1.27.2, JDK21, Android SDK and NDKr30 (`ANDROID_HOME` and `ANDROID_NDK_HOME` set). The script verifies the source archive SHA-256 and matching module files, pins gomobile/gobind, and builds only the two supported ABIs with 16KiB alignment. See the matching [build implementation](https://github.com/XTLS/libXray/blob/v26.10.21/build/app/android.py) for the upstream flags. The supplied APK uses the verified official prebuilt AAR; this local native source build and byte-for-byte reproduction of the upstream AAR have not been validated here.

Licenses are copied into APK assets. Corresponding Xray source: https://github.com/XTLS/Xray-core/tree/1b1647cd9ddf. Wrapper source: https://github.com/XTLS/libXray/tree/v26.10.21. Both are unmodified. Additional dependency versions, source repositories and checksums are recorded in their module files.
