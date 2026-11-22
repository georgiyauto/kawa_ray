//! Native maintenance for package-installed Unix helpers. No user-supplied scripts or binaries.
use anyhow::{Result, bail};
use kawaray_core::unix_trust;

#[derive(Debug, PartialEq, Eq)]
pub enum Action {
    Install(u32),
    Uninstall,
}

pub fn parse(args: &[String]) -> Result<Option<Action>> {
    if !args
        .iter()
        .any(|a| a == "--install-service" || a == "--uninstall-service")
    {
        return Ok(None);
    }
    match args
        .iter()
        .map(String::as_str)
        .collect::<Vec<_>>()
        .as_slice()
    {
        ["--install-service", "--allowed-uid", uid] => {
            Ok(Some(Action::Install(unix_trust::parse_uid(uid)?)))
        }
        ["--uninstall-service"] => Ok(Some(Action::Uninstall)),
        _ => bail!(
            "usage: KawaVPNHelper --install-service --allowed-uid <decimal non-root uid> | --uninstall-service"
        ),
    }
}

pub fn linux_unit(helper: &str, uid: u32) -> String {
    format!(
        "[Unit]\nDescription=Kawa VPN TUN helper\nAfter=network.target\n\n[Service]\nExecStart={helper} --allowed-uid {uid}\nRestart=on-failure\nRestartSec=2\nUMask=0077\n\n[Install]\nWantedBy=multi-user.target\n"
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    fn args(values: &[&str]) -> Vec<String> {
        values.iter().map(|s| s.to_string()).collect()
    }
    #[test]
    fn maintenance_arguments_are_closed_and_uid_is_mandatory() {
        assert_eq!(
            parse(&args(&["--install-service", "--allowed-uid", "1000"])).unwrap(),
            Some(Action::Install(1000))
        );
        assert_eq!(
            parse(&args(&["--uninstall-service"])).unwrap(),
            Some(Action::Uninstall)
        );
        assert_eq!(parse(&args(&["--allowed-uid", "1000"])).unwrap(), None);
        for bad in [
            vec!["--install-service"],
            vec!["--install-service", "--allowed-uid", "0"],
            vec!["--install-service", "--allowed-uid", "1000", "--anything"],
            vec!["--uninstall-service", "--allowed-uid", "1000"],
            vec!["--install-service", "--uninstall-service"],
        ] {
            assert!(parse(&args(&bad)).is_err());
        }
    }
    #[test]
    fn systemd_unit_runs_installed_helper_with_only_validated_uid() {
        let unit = linux_unit(unix_trust::LINUX_HELPERS[0], 1000);
        assert!(
            unit.contains("ExecStart=/usr/libexec/kawa-vpn/KawaVPNHelper --allowed-uid 1000\n")
        );
        assert!(unit.contains("Restart=on-failure\n"));
    }
}

#[cfg(unix)]
pub fn validate_user(uid: u32) -> Result<()> {
    unix_trust::parse_uid(&uid.to_string())?;
    // Used before any threads start. Merely validates an existing local account.
    if unsafe { libc::getpwuid(uid) }.is_null() {
        bail!("UID does not identify an existing local user");
    }
    Ok(())
}

#[cfg(unix)]
fn protected_executable() -> Result<std::path::PathBuf> {
    let path = std::env::current_exe()?;
    #[cfg(target_os = "linux")]
    let permitted = unix_trust::LINUX_HELPERS
        .iter()
        .any(|p| path == std::path::Path::new(p));
    #[cfg(target_os = "macos")]
    let permitted = path == std::path::Path::new(unix_trust::MACOS_HELPER);
    if !permitted {
        bail!("service maintenance is only available from the package-installed Kawa VPN helper");
    }
    unix_trust::validate_file(&path, true)?;
    Ok(path)
}

#[cfg(unix)]
fn command(program: &str, args: &[&str]) -> Result<()> {
    let executable = std::fs::canonicalize(program)?;
    unix_trust::validate_file(&executable, true)?;
    let output = std::process::Command::new(executable)
        .args(args)
        .env_clear()
        .env("PATH", "/usr/sbin:/usr/bin:/sbin:/bin")
        .env("LANG", "C")
        .output()?;
    if !output.status.success() {
        bail!(
            "{program} failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    Ok(())
}

/// Write in a validated root-owned directory; never follow a pre-existing symlink.
#[cfg(unix)]
fn write_service(path: &std::path::Path, contents: &[u8]) -> Result<()> {
    use std::io::Write;
    use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
    unix_trust::validate_directory(
        path.parent()
            .ok_or_else(|| anyhow::anyhow!("missing service directory"))?,
    )?;
    match std::fs::symlink_metadata(path) {
        Ok(_) => unix_trust::validate_file(path, false)?,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => return Err(e.into()),
    }
    let nonce = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)?
        .as_nanos();
    let temporary = path.with_extension(format!("{}.{}.new", std::process::id(), nonce));
    let result = (|| -> Result<()> {
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&temporary)?;
        file.write_all(contents)?;
        file.set_permissions(std::fs::Permissions::from_mode(0o644))?;
        file.sync_all()?;
        std::fs::rename(&temporary, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(temporary);
    }
    result
}

#[cfg(unix)]
fn remove_service(path: &std::path::Path) -> Result<()> {
    match std::fs::symlink_metadata(path) {
        Ok(_) => {
            unix_trust::validate_file(path, false)?;
            std::fs::remove_file(path)?;
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => return Err(e.into()),
    }
    Ok(())
}

#[cfg(unix)]
pub fn run(action: Action) -> Result<()> {
    if unsafe { libc::geteuid() } != 0 {
        bail!("service maintenance requires administrator privileges");
    }
    let helper = protected_executable()?;
    if let Action::Install(uid) = action {
        validate_user(uid)?;
    }
    #[cfg(target_os = "linux")]
    {
        if !std::path::Path::new("/run/systemd/system").is_dir() {
            bail!(
                "Native Kawa VPN maintenance requires systemd. OpenRC/runit need distribution-specific service packaging."
            );
        }
        let unit = std::path::Path::new("/etc/systemd/system/kawa-vpn-helper.service");
        match action {
            Action::Install(uid) => {
                write_service(
                    unit,
                    linux_unit(
                        helper
                            .to_str()
                            .ok_or_else(|| anyhow::anyhow!("invalid helper path"))?,
                        uid,
                    )
                    .as_bytes(),
                )?;
                command("/usr/bin/systemctl", &["daemon-reload"])?;
                command("/usr/bin/systemctl", &["enable", unix_trust::LINUX_SERVICE])?;
                command(
                    "/usr/bin/systemctl",
                    &["restart", unix_trust::LINUX_SERVICE],
                )?;
            }
            Action::Uninstall => {
                // Stop first so active sessions restore routes and DNS before unlinking metadata.
                command(
                    "/usr/bin/systemctl",
                    &["disable", "--now", unix_trust::LINUX_SERVICE],
                )?;
                remove_service(unit)?;
                command("/usr/bin/systemctl", &["daemon-reload"])?;
            }
        }
    }
    #[cfg(target_os = "macos")]
    {
        let plist =
            std::path::Path::new("/Library/LaunchDaemons/pro.vinnikov.kawavpn.helper.plist");
        let service = format!("system/{}", unix_trust::MACOS_LABEL);
        match action {
            Action::Install(uid) => {
                // A missing service is expected on the first install; other failures are surfaced by bootstrap.
                if std::process::Command::new("/bin/launchctl")
                    .args(["print", &service])
                    .output()?
                    .status
                    .success()
                {
                    command("/bin/launchctl", &["bootout", &service])?;
                }
                write_service(plist, macos_plist(uid).as_bytes())?;
                command(
                    "/bin/launchctl",
                    &["bootstrap", "system", plist.to_str().unwrap()],
                )?;
            }
            Action::Uninstall => {
                if std::process::Command::new("/bin/launchctl")
                    .args(["print", &service])
                    .output()?
                    .status
                    .success()
                {
                    command("/bin/launchctl", &["bootout", &service])?;
                }
                remove_service(plist)?;
            }
        }
    }
    // The package manager owns the executable. Service removal never deletes it.
    Ok(())
}

#[cfg(any(target_os = "macos", test))]
fn macos_plist(uid: u32) -> String {
    format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>{label}</string>
<key>ProgramArguments</key><array><string>{helper}</string><string>--allowed-uid</string><string>{uid}</string></array>
<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
<key>StandardErrorPath</key><string>/var/log/kawa-vpn-helper.log</string>
<key>StandardOutPath</key><string>/var/log/kawa-vpn-helper.log</string>
<key>Umask</key><integer>63</integer>
</dict></plist>
"#,
        label = unix_trust::MACOS_LABEL,
        helper = unix_trust::MACOS_HELPER
    )
}
