# Kawa VPN for Android

Native Kotlin / Jetpack Compose application, Android 8/API26 minimum, SDK37,
arm64-v8a and x86_64. Uses real libXray with its built-in TUN, not a UI shell.
Android 0.4.31 continues the desktop workflow on a phone: bot login with recovery,
full JSON subscriptions, grouped servers, favorites, import/share/QR, routing
rules and application exclusions, custom DNS, cabinet and session statistics.
The download negotiates the full `/json` endpoint and rejects unsupported-client
placeholder nodes. Partial refresh preserves cached profiles for failed sources.
Protocol probes run in a private process so they cannot replace the active core.

The phone interface follows the black dotted-globe reference: equal session cards,
readable glass controls, icon-pill bottom navigation and an in-route ping action.
YouTube/AI shortcuts search actual subscription profiles. Expiry notices use
subscription metadata; unsupported local DPI bypass is not advertised.

Connection health tries independent Google/Cloudflare endpoints. Manual ping
failure clears its measurement without stopping the VPN. Automatic checks allow
isolated failures and a 60-second physical-network handover grace period. A
NOT_VPN callback refreshes the native resolver and cancels stale health requests;
protected sockets follow Android's default network. The TUN is retained through
brief network loss. The startup check still verifies both the proxy and TUN.

The test suite includes 44 domain regressions, HTTP status contract tests and
disposable Android15/API35 x86_64 WHPX instrumentation. Final artifact verification
and the actual instrumented test results are distributed beside the APK.
Physical ARM devices and other API versions remain untested. iOS and 32-bit
Android are outside this release. Desktop and Android have independent versions;
desktop remains 0.4.27. Compilation alone does not establish device coverage.

## Build from a clean source archive

Install JDK21, Python3 and Android SDK with platform37 and build-tools36.0.0.
Set `ANDROID_HOME` (or private `local.properties`), then from this directory:

```sh
python3 tools/fetch-dependencies.py
./gradlew --no-daemon :domain:test :app:assembleDebug :app:lintDebug
```

Windows: use `gradlew.bat` instead of `./gradlew`. Wrapper Gradle9.6.0 has a pinned
distribution SHA-256. Native AAR, geographic data and module source versions are
locked under `native/`; see its README for the matching native source build.

The source ZIP deliberately excludes AAR/data binaries, Gradle caches, local SDK
paths, accounts and signing keys. Before producing a distributable APK, generate
the corresponding source and copy legal notices to assets:

```sh
cd ..
python3 tools/make-source-archive.py --version 0.4.31 --output android/app/src/main/assets/source.zip
cd android
```

## Release signing

Set `KAWA_ANDROID_KEYSTORE` to the persistent Kawa PKCS12 store and
`KAWA_ANDROID_PASSWORD_FILE` to its password file, both outside the repository.
The key alias is `kawa-android`. Run `./gradlew :app:assembleRelease`, then verify
the resulting APK with Android build-tools `apksigner verify --verbose --print-certs`.
Never replace the key for future updates. Never commit a store, password, account,
subscription or an environment file. Debug APKs use the SDK test key and are not
public releases. No Google Play publication is included.

Before distribution, verify the APK against the exact separately distributed
source ZIP, not just its internal manifest:

```sh
python3 ../tools/verify-apk.py --apk app/build/outputs/apk/release/app-release.apk \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0" \
  --certificate-sha256 c08a348905ca3a1e736bc8af5aa358bf7bb378406046b06da7a037cc35e79b98 \
  --source-archive app/src/main/assets/source.zip
```

The verifier checks the release certificate, both ABI ELF/ZIP 16KiB alignment,
legal assets, exact source archive digest, unique members and all manifest hashes.

## Device checks

Run only on a disposable emulator/test device, with the owner's VPN untouched:

```sh
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell appops set pro.vinnikov.kawavpn ACTIVATE_VPN allow
./gradlew :app:connectedDebugAndroidTest
```

The transport instrumented test expects an owned local VLESS server on the
emulator host loopback port25080. Pass its random temporary UUID with
`-Pandroid.testInstrumentationRunnerArguments.testVlessUuid=...`; it is a test
credential, never a production subscription. A direct-only configuration must
not produce Protected. Test native startup, proxy and TUN HTTP/DNS, revocation,
offline failure, descriptor cleanup, both IP families and network changes.
UI screenshots are written by InterfaceTest to the test app's external files.
Physical ARM devices and older Android versions need separate validation.

## Behaviour and privacy

Profiles and refresh tokens use AES-GCM with Android Keystore and atomic writes
under a process-wide lock. They are excluded from backup/device migration. Full
JSON retains DNS, routing, balancers and sniffing; only app-owned inbounds and
the TUN descriptor are prepared. A temporary authenticated loopback health
listener forces a check through a real proxy independently of bypass rules.
The normal TUN route is checked too, and the running service monitors failure.

Bot login polls the existing cabinet contract; browser login is not presented as
app authentication. Refresh credentials are durably saved before further work.
Payment intent/checkout ID is saved before a one-shot mutation. Uncertain orders
are recovered, never automatically repeated; paid status waits for actual
subscription issuance. No real payments are used by automated tests.

Country-only route markers use HTTPS Cloudflare trace through the physical
network and selected proxy. IP addresses are neither stored nor logged; the
globe represents countries, not a user's precise location. Actual Xray TUN inbound counters distinguish received and sent traffic; these
figures include IP headers and are distinct from server subscription limits.

Kawa is derived from KAWARAY under GPL-3.0-only with preserved original notices.
See root README/LICENSE/THIRD-PARTY-NOTICES and `native/licenses/`.

## Platform differences

Android uses its system VPN consent and foreground service instead of the desktop
administrator helper. Site/IP rules are retained; process rules use Android
application exclusions. The full JSON editor preserves provider-specific routes.
Desktop installer/update packaging and desktop DPI controls are not phone actions.
Install Android updates from the published signed APK; Android does not yet have
the desktop in-app update downloader. Payment recovery is implemented, but this
release performs no real payment or Telegram confirmation during automated QA.
