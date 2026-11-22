//! Maintenance runs only from the installer-owned Program Files location.
use anyhow::{Result, bail};
use std::{
    ffi::OsString,
    os::windows::{ffi::OsStringExt, fs::MetadataExt},
    path::PathBuf,
};
use windows_sys::Win32::{
    System::Com::CoTaskMemFree,
    UI::Shell::{FOLDERID_ProgramFiles, SHGetKnownFolderPath},
};

pub fn protected_helper() -> Result<PathBuf> {
    let mut raw = std::ptr::null_mut();
    let hr =
        unsafe { SHGetKnownFolderPath(&FOLDERID_ProgramFiles, 0, std::ptr::null_mut(), &mut raw) };
    if hr < 0 || raw.is_null() {
        bail!("не удалось определить системную папку Program Files");
    }
    let root = unsafe {
        let mut n = 0;
        while *raw.add(n) != 0 {
            n += 1;
        }
        let value = PathBuf::from(OsString::from_wide(std::slice::from_raw_parts(raw, n)));
        CoTaskMemFree(raw.cast());
        value
    };
    let dir = root.join("Kawa VPN");
    let path = dir.join("KawaVPNHelper.exe");
    for component in [&dir, &path] {
        let metadata = std::fs::symlink_metadata(component)?;
        if metadata.file_attributes() & 0x400 != 0 {
            bail!("путь помощника содержит перенаправление");
        }
    }
    if !path.is_file() || !path.canonicalize()?.starts_with(root.canonicalize()?) {
        bail!("переустановите Kawa VPN полным установщиком");
    }
    Ok(path)
}

pub fn verify_current_helper() -> Result<PathBuf> {
    let expected = protected_helper()?.canonicalize()?;
    if std::env::current_exe()?.canonicalize()? != expected {
        bail!("обслуживание службы доступно только установленному помощнику");
    }
    Ok(expected)
}

#[cfg(test)]
mod tests {
    #[test]
    fn maintenance_refuses_test_executable() {
        assert!(super::verify_current_helper().is_err());
    }
}
