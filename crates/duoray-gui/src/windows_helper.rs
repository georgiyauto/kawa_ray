//! Repair uses only the protected, installer-deployed helper and native SCM.
use crate::connection::hidden;
use anyhow::{Context, Result, bail};
use duoray_core::{helper_proto::PROTOCOL_MAINTENANCE, windows_trust::protected_helper};
use std::{ffi::OsStr, os::windows::ffi::OsStrExt};
use windows_sys::Win32::{
    Foundation::CloseHandle,
    System::Threading::{GetExitCodeProcess, INFINITE, WaitForSingleObject},
    UI::{
        Shell::{SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW, ShellExecuteExW},
        WindowsAndMessaging::SW_HIDE,
    },
};

fn wide(value: &OsStr) -> Vec<u16> {
    value.encode_wide().chain(Some(0)).collect()
}

fn run_elevated(argument: &str) -> Result<()> {
    let path =
        protected_helper().context("Установите последнюю версию Kawa VPN полным установщиком")?;
    // Old helpers treat unknown arguments as foreground mode; never pass new
    // maintenance flags until the safe --protocol capability check succeeds.
    let output = hidden(
        std::process::Command::new(&path)
            .arg("--protocol")
            .current_dir(path.parent().context("no helper directory")?),
    )
    .output()?;
    let protocol = String::from_utf8_lossy(&output.stdout)
        .trim()
        .parse::<u32>()
        .unwrap_or(0);
    if !output.status.success() || protocol < PROTOCOL_MAINTENANCE {
        bail!(
            "Для безопасного восстановления службы запустите последнюю версию полного установщика Kawa VPN"
        );
    }
    let verb = wide(OsStr::new("runas"));
    let file = wide(path.as_os_str());
    let args = wide(OsStr::new(argument));
    let directory = wide(path.parent().context("no helper directory")?.as_os_str());
    let mut info: SHELLEXECUTEINFOW = unsafe { std::mem::zeroed() };
    info.cbSize = std::mem::size_of::<SHELLEXECUTEINFOW>() as u32;
    info.fMask = SEE_MASK_NOCLOSEPROCESS;
    info.lpVerb = verb.as_ptr();
    info.lpFile = file.as_ptr();
    info.lpParameters = args.as_ptr();
    info.lpDirectory = directory.as_ptr();
    info.nShow = SW_HIDE;
    if unsafe { ShellExecuteExW(&mut info) } == 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    if info.hProcess.is_null() {
        bail!("не удалось получить процесс обслуживания службы");
    }
    let mut code = 1;
    unsafe {
        WaitForSingleObject(info.hProcess, INFINITE);
        let ok = GetExitCodeProcess(info.hProcess, &mut code);
        CloseHandle(info.hProcess);
        if ok == 0 {
            return Err(std::io::Error::last_os_error().into());
        }
    }
    if code != 0 {
        bail!("обслуживание службы завершилось с кодом {code}");
    }
    Ok(())
}

pub fn install() -> Result<()> {
    run_elevated("--install-service")
}
pub fn uninstall() -> Result<()> {
    run_elevated("--uninstall-service")
}
