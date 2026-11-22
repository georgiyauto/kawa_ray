; Kawa VPN installer for Windows (NSIS 3).

;

; Installs the app with xray + Wintun, registers the TUN helper as a Windows

; service (the only admin step — connecting later needs no UAC prompt),

; creates shortcuts and an entry in "Apps & features".

;

; Build (from this directory): makensis duoray.nsi



Unicode true

!include "signing.nsh"

SetCompressor /SOLID lzma



!define APP "Kawa VPN"

!ifndef VERSION

  !define VERSION "0.4.26"

!endif

; x64 (default) or x86. Wintun must match the OS bitness, so each build

; only installs on its own architecture.

!ifndef ARCH

  !define ARCH "x64"

!endif

!ifndef STAGE

!define STAGE "stage-${ARCH}"

!endif

!define SERVICE "KawaVpnHelper"

!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\Kawa VPN"



Name "${APP}"

OutFile "..\..\dist\KawaVPN-Setup-${VERSION}-${ARCH}.exe"

!if "${ARCH}" == "x64"

  InstallDir "$PROGRAMFILES64\Kawa VPN"

!else

  InstallDir "$PROGRAMFILES\Kawa VPN"

!endif

RequestExecutionLevel admin

; Upgrades (also the silent ones the app starts) go where Kawa VPN already is.

InstallDirRegKey HKLM "${UNINST_KEY}" "InstallLocation"

BrandingText "Kawa VPN ${VERSION}"



VIProductVersion "${VERSION}.0"

VIAddVersionKey "ProductName" "Kawa VPN"

VIAddVersionKey "FileDescription" "Kawa VPN Setup"

VIAddVersionKey "FileVersion" "${VERSION}"

VIAddVersionKey "ProductVersion" "${VERSION}"

VIAddVersionKey "LegalCopyright" "Kawa VPN contributors; see accompanying license notices"



!include "MUI2.nsh"

!include "x64.nsh"



!define MUI_ICON "KawaVPN.ico"

!define MUI_UNICON "KawaVPN.ico"

!define MUI_ABORTWARNING

!define MUI_FINISHPAGE_RUN "$INSTDIR\KawaVPN.exe"

!define MUI_FINISHPAGE_RUN_TEXT "Запустить Kawa VPN"



!insertmacro MUI_PAGE_WELCOME

!insertmacro MUI_PAGE_DIRECTORY

!insertmacro MUI_PAGE_INSTFILES

!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM

!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Russian"



Function .onInit

!if "${ARCH}" == "x64"

  ${IfNot} ${RunningX64}

    MessageBox MB_ICONSTOP "Это 64-битная версия Kawa VPN. Для 32-битной Windows скачайте установщик x86."

    Abort

  ${EndIf}

  SetRegView 64

!else

  ${If} ${RunningX64}

    MessageBox MB_ICONSTOP "Это 32-битная версия Kawa VPN, а Windows у вас 64-битная. Скачайте установщик x64 — драйвер туннеля должен совпадать с разрядностью системы."

    Abort

  ${EndIf}

  SetRegView 32

!endif

FunctionEnd



; Stops a running Kawa VPN and its service so the files can be replaced.

!macro StopRunning

  nsExec::Exec 'taskkill /IM KawaVPN.exe /F'

  nsExec::Exec 'sc.exe stop ${SERVICE}'

  Sleep 2500

  nsExec::Exec 'taskkill /IM xray.exe /F'

  nsExec::Exec 'taskkill /IM KawaVPNHelper.exe /F'

  Sleep 1000

!macroend



Section "Kawa VPN" SecMain

  SectionIn RO

  SetShellVarContext all

  !insertmacro StopRunning



  SetOutPath "$INSTDIR"

  File "${STAGE}\KawaVPN.exe"

  File "${STAGE}\KawaVPNHelper.exe"

  File "${STAGE}\xray.exe"

  File "${STAGE}\geoip.dat"

  File "${STAGE}\geosite.dat"

  File "${STAGE}\wintun.dll"

  File "${STAGE}\LICENSE-xray.txt"

  File "${STAGE}\LICENSE-wintun.txt"

  File "${STAGE}\LICENSE.txt"

  File "${STAGE}\LICENSE-EXCEPTION.md"

  File "${STAGE}\THIRD-PARTY-NOTICES.md"

  File "${STAGE}\THIRD-PARTY-CRATES.txt"
  File "${STAGE}\LICENSE-DUOTUN.txt"

  File "${STAGE}\KawaVPN-${VERSION}-source.zip"

  File "${STAGE}\README-KawaVPN.txt"

  File "KawaVPN.ico"







  ; TUN helper service: recreate so an upgrade always points at the new binary.

  nsExec::Exec 'sc.exe delete ${SERVICE}'

  Sleep 1000

  nsExec::ExecToLog 'sc.exe create ${SERVICE} binPath= "\"$INSTDIR\KawaVPNHelper.exe\" --service" start= auto DisplayName= "Kawa VPN TUN helper"'

  nsExec::ExecToLog 'sc.exe description ${SERVICE} "Kawa VPN: поднимает VPN-туннель (TUN) по запросу приложения"'

  nsExec::ExecToLog 'sc.exe failure ${SERVICE} reset= 60 actions= restart/2000/restart/5000/restart/10000'

  nsExec::ExecToLog 'sc.exe start ${SERVICE}'



  ; The icon lives inside KawaVPN.exe; recreate shortcuts and tell Explorer to

  ; drop its cached icons, so an upgrade never shows the old one.

  Delete "$SMPROGRAMS\Kawa VPN.lnk"

  Delete "$DESKTOP\Kawa VPN.lnk"

  CreateShortcut "$SMPROGRAMS\Kawa VPN.lnk" "$INSTDIR\KawaVPN.exe" "" "$INSTDIR\KawaVPN.exe" 0

  CreateShortcut "$DESKTOP\Kawa VPN.lnk" "$INSTDIR\KawaVPN.exe" "" "$INSTDIR\KawaVPN.exe" 0

  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'

  nsExec::Exec 'ie4uinit.exe -show'



  WriteUninstaller "$INSTDIR\uninstall.exe"

  WriteRegStr HKLM "${UNINST_KEY}" "DisplayName" "Kawa VPN"

  WriteRegStr HKLM "${UNINST_KEY}" "DisplayVersion" "${VERSION}"

  WriteRegStr HKLM "${UNINST_KEY}" "Publisher" "Kawa VPN"

  WriteRegStr HKLM "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\KawaVPN.exe,0"

  WriteRegStr HKLM "${UNINST_KEY}" "InstallLocation" "$INSTDIR"

  WriteRegStr HKLM "${UNINST_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'

  WriteRegDWORD HKLM "${UNINST_KEY}" "NoModify" 1

  WriteRegDWORD HKLM "${UNINST_KEY}" "NoRepair" 1

  WriteRegDWORD HKLM "${UNINST_KEY}" "EstimatedSize" 92000



  ; A silent run is the in-app update: start the new version again. Through

  ; explorer.exe it runs as the user, not elevated like this installer.

  IfSilent 0 +2

    Exec '"$WINDIR\explorer.exe" "$INSTDIR\KawaVPN.exe"'

SectionEnd



Function un.onInit

!if "${ARCH}" == "x64"

  SetRegView 64

!else

  SetRegView 32

!endif

FunctionEnd



Section "Uninstall"

  SetShellVarContext all

  ; Stopping the service restores routes and DNS if a tunnel is up.

  nsExec::Exec 'taskkill /IM KawaVPN.exe /F'

  nsExec::ExecToLog 'sc.exe stop ${SERVICE}'

  Sleep 3000

  nsExec::Exec 'taskkill /IM xray.exe /F'

  nsExec::Exec 'taskkill /IM KawaVPNHelper.exe /F'

  Sleep 1000

  nsExec::ExecToLog 'sc.exe delete ${SERVICE}'

  ; Leftover firewall rule if the service died mid-session.

  nsExec::Exec 'netsh advfirewall firewall delete rule name="Kawa VPN DNS guard"'



  Delete "$INSTDIR\KawaVPN.exe"

  Delete "$INSTDIR\KawaVPNHelper.exe"

  Delete "$INSTDIR\xray.exe"

  Delete "$INSTDIR\geoip.dat"

  Delete "$INSTDIR\geosite.dat"

  Delete "$INSTDIR\wintun.dll"

  Delete "$INSTDIR\LICENSE-xray.txt"

  Delete "$INSTDIR\LICENSE-wintun.txt"

  Delete "$INSTDIR\LICENSE.txt"

  Delete "$INSTDIR\LICENSE-EXCEPTION.md"

  Delete "$INSTDIR\THIRD-PARTY-NOTICES.md"

  Delete "$INSTDIR\THIRD-PARTY-CRATES.txt"
  Delete "$INSTDIR\LICENSE-DUOTUN.txt"

  Delete "$INSTDIR\KawaVPN-${VERSION}-source.zip"

  Delete "$INSTDIR\README-KawaVPN.txt"

  Delete "$INSTDIR\KawaVPN.ico"

  Delete "$INSTDIR\uninstall.exe"

  RMDir "$INSTDIR"



  Delete "$SMPROGRAMS\Kawa VPN.lnk"

  Delete "$DESKTOP\Kawa VPN.lnk"

  DeleteRegKey HKLM "${UNINST_KEY}"

SectionEnd

