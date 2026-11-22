//! Only the immutable helper installed by the distribution package can elevate.
use super::*;
use duoray_core::unix_trust;
use std::path::{Path, PathBuf};

#[cfg(target_os = "linux")]
pub const LOG_HINT: &str = "journalctl -u kawa-vpn-helper.service";
#[cfg(target_os = "macos")]
pub const LOG_HINT: &str = "/var/log/kawa-vpn-helper.log";

fn installed_helper() -> Result<PathBuf> {
    #[cfg(target_os = "linux")]
    let candidates = unix_trust::LINUX_HELPERS;
    #[cfg(target_os = "macos")]
    let candidates = &[unix_trust::MACOS_HELPER];
    for candidate in candidates {
        let path = Path::new(candidate);
        if path.exists() {
            unix_trust::validate_file(path, true).context(
                "небезопасные права установленного помощника; переустановите пакет Kawa VPN",
            )?;
            return Ok(path.to_owned());
        }
    }
    bail!(
        "Установите полный пакет Kawa VPN (.deb для Linux или .pkg для macOS). Portable/AppImage не устанавливает привилегированный помощник."
    )
}

pub fn install() -> Result<()> {
    // The desktop user's numeric identity is the only variable privileged input.
    let uid = unix_trust::parse_uid(&unsafe { libc::getuid() }.to_string())?;
    run(&["--install-service", "--allowed-uid", &uid.to_string()])
}

pub fn uninstall() -> Result<()> {
    run(&["--uninstall-service"])
}

fn run(args: &[&str]) -> Result<()> {
    let helper = installed_helper()?;
    #[cfg(target_os = "linux")]
    let output = {
        // Do not search PATH for the authorization program.
        let pkexec =
            std::fs::canonicalize("/usr/bin/pkexec").context("Установите polkit (pkexec)")?;
        unix_trust::validate_file(&pkexec, true)?;
        std::process::Command::new(pkexec)
            .arg(&helper)
            .args(args)
            .output()?
    };
    #[cfg(target_os = "macos")]
    let output = {
        // Helper and flags are constants; UID has already been restricted to decimal digits.
        let command = format!(
            "{} {}",
            helper.to_str().context("invalid helper path")?,
            args.join(" ")
        );
        let apple = format!(
            "do shell script \"{command}\" with prompt \"Kawa VPN настраивает помощник VPN.\" with administrator privileges"
        );
        std::process::Command::new("/usr/bin/osascript")
            .args(["-e", &apple])
            .output()?
    };
    if !output.status.success() {
        bail!(
            "Настройка помощника не выполнена: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    Ok(())
}

pub fn verify_peer(stream: &std::os::unix::net::UnixStream) -> std::io::Result<()> {
    use std::os::fd::AsRawFd;
    #[cfg(target_os = "linux")]
    let uid = unsafe {
        let mut credentials: libc::ucred = std::mem::zeroed();
        let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
        if libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            &mut credentials as *mut _ as *mut _,
            &mut len,
        ) != 0
        {
            return Err(std::io::Error::last_os_error());
        }
        credentials.uid
    };
    #[cfg(target_os = "macos")]
    let uid = unsafe {
        let mut uid = 0;
        let mut gid = 0;
        if libc::getpeereid(stream.as_raw_fd(), &mut uid, &mut gid) != 0 {
            return Err(std::io::Error::last_os_error());
        }
        uid
    };
    if uid != 0 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::PermissionDenied,
            "helper peer must be root",
        ));
    }
    Ok(())
}
