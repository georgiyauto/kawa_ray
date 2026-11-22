//! Windows uses DPAPI, macOS uses Keychain, Linux uses owner-only local files.
use anyhow::{Result, bail};
use serde::{Serialize, de::DeserializeOwned};
use std::path::Path;

#[cfg(not(target_os = "macos"))]
pub fn save<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    let encrypted = protect(&serde_json::to_vec(value)?, false)?;
    std::fs::create_dir_all(path.parent().unwrap())?;
    #[cfg(not(target_os = "linux"))]
    let tmp = path.with_extension("tmp");
    #[cfg(target_os = "linux")]
    {
        use std::io::Write;
        use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
        std::fs::set_permissions(
            path.parent().unwrap(),
            std::fs::Permissions::from_mode(0o700),
        )?;
        let tmp = path.with_extension(format!("{:032x}.tmp", fastrand::u128(..)));
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&tmp)?;
        file.write_all(&encrypted)?;
        file.sync_all()?;
        drop(file);
        std::fs::rename(tmp, path)?;
        return Ok(());
    }
    #[cfg(not(target_os = "linux"))]
    std::fs::write(&tmp, encrypted)?;
    #[cfg(not(target_os = "linux"))]
    std::fs::rename(tmp, path)?;
    #[cfg(not(target_os = "linux"))]
    Ok(())
}
#[cfg(not(target_os = "macos"))]
pub fn load<T: DeserializeOwned>(path: &Path) -> Result<Option<T>> {
    if !path.exists() {
        return Ok(None);
    }
    #[cfg(target_os = "linux")]
    {
        use std::os::unix::fs::MetadataExt;
        let m = std::fs::symlink_metadata(path)?;
        if !m.is_file() || m.uid() != unsafe { libc::geteuid() } || m.mode() & 0o077 != 0 {
            bail!("Файл аккаунта должен принадлежать текущему пользователю и иметь права 600");
        }
    }
    Ok(Some(serde_json::from_slice(&protect(
        &std::fs::read(path)?,
        true,
    )?)?))
}

#[cfg(windows)]
fn protect(data: &[u8], decrypt: bool) -> Result<Vec<u8>> {
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::Cryptography::{
            CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
        },
    };
    let input = CRYPT_INTEGER_BLOB {
        cbData: data.len().try_into()?,
        pbData: data.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    let ok = unsafe {
        if decrypt {
            CryptUnprotectData(
                &input,
                std::ptr::null_mut(),
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        } else {
            CryptProtectData(
                &input,
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        }
    };
    if ok == 0 {
        bail!("Windows не удалось защитить данные аккаунта");
    }
    let bytes =
        unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
    unsafe {
        LocalFree(output.pbData as *mut _);
    }
    Ok(bytes)
}
#[cfg(target_os = "linux")]
fn protect(data: &[u8], _: bool) -> Result<Vec<u8>> {
    Ok(data.to_vec())
}
#[cfg(not(any(windows, target_os = "linux", target_os = "macos")))]
fn protect(_: &[u8], _: bool) -> Result<Vec<u8>> {
    bail!("Сохранение аккаунта доступно в этой сборке только на Windows")
}

#[cfg(test)]
mod tests {
    #[cfg(target_os = "linux")]
    #[test]
    fn linux_login_and_pending_order_survive_restart_with_private_permissions() {
        use std::os::unix::fs::MetadataExt;
        let dir = std::env::temp_dir().join(format!("kawa-login-test-{:032x}", fastrand::u128(..)));
        let path = dir.join("account.json");
        let value = serde_json::json!({"refresh":"rotated-refresh", "pending":{"checkout_id":"a".repeat(32), "paid":true}});
        super::save(&path, &value).unwrap();
        assert_eq!(
            super::load::<serde_json::Value>(&path).unwrap(),
            Some(value)
        );
        assert_eq!(std::fs::metadata(&path).unwrap().mode() & 0o777, 0o600);
        assert_eq!(std::fs::metadata(&dir).unwrap().mode() & 0o777, 0o700);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[cfg(target_os = "linux")]
    #[test]
    fn linux_rejects_shared_credentials_and_symlinks() {
        use std::os::unix::fs::PermissionsExt;
        let dir = std::env::temp_dir().join(format!("kawa-login-test-{:032x}", fastrand::u128(..)));
        let path = dir.join("account.json");
        super::save(&path, &serde_json::json!({"refresh":"private"})).unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644)).unwrap();
        assert!(super::load::<serde_json::Value>(&path).is_err());
        let link = dir.join("linked.json");
        std::os::unix::fs::symlink(&path, &link).unwrap();
        assert!(super::load::<serde_json::Value>(&link).is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[cfg(windows)]
    #[test]
    fn saved_login_and_order_survive_multiple_updates_and_restart() {
        let dir =
            std::env::temp_dir().join(format!("kawa-account-test-{:032x}", fastrand::u128(..)));
        let path = dir.join("account.dpapi");
        let first = serde_json::json!({"refresh":"original","pending":{"checkout_id":"a".repeat(32),"paid":false}});
        super::save(&path, &first).unwrap();
        let second = serde_json::json!({"refresh":"rotated","pending":{"checkout_id":"a".repeat(32),"paid":true}});
        super::save(&path, &second).unwrap();
        let restored: serde_json::Value = super::load(&path).unwrap().unwrap();
        assert_eq!(restored, second);
        assert!(
            !std::fs::read(&path)
                .unwrap()
                .windows(7)
                .any(|s| s == b"rotated")
        );
        std::fs::remove_file(path).unwrap();
        std::fs::remove_dir(dir).unwrap();
    }
    #[cfg(windows)]
    #[test]
    fn dpapi_roundtrip_and_corruption() {
        let secret = b"refresh-token-not-plaintext";
        let encrypted = super::protect(secret, false).unwrap();
        assert!(!encrypted.windows(secret.len()).any(|s| s == secret));
        assert_eq!(super::protect(&encrypted, true).unwrap(), secret);
        let mut broken = encrypted;
        broken.truncate(20);
        assert!(super::protect(&broken, true).is_err());
    }
}

/// Remove the platform's stored credentials (including macOS Keychain entries).
#[cfg(not(target_os = "macos"))]
pub fn remove(path: &Path) -> Result<()> {
    match std::fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.into()),
    }
}

#[cfg(test)]
mod logout_tests {
    #[test]
    fn logout_deletes_saved_secret_and_is_idempotent() {
        let dir =
            std::env::temp_dir().join(format!("kawa-logout-test-{:032x}", fastrand::u128(..)));
        let path = dir.join("account");
        super::save(&path, &serde_json::json!({"refresh":"unique-test-secret"})).unwrap();
        super::remove(&path).unwrap();
        assert!(super::load::<serde_json::Value>(&path).unwrap().is_none());
        super::remove(&path).unwrap();
        if dir.exists() {
            std::fs::remove_dir_all(dir).unwrap();
        }
    }
}

#[cfg(any(target_os = "macos", test))]
fn keychain_account(path: &Path) -> Result<String> {
    use sha2::{Digest, Sha256};
    let absolute = std::path::absolute(path)?;
    Ok(format!(
        "{:x}",
        Sha256::digest(absolute.as_os_str().as_encoded_bytes())
    ))
}

#[cfg(target_os = "macos")]
const KEYCHAIN_SERVICE: &str = "pro.vinnikov.kawavpn.account";
#[cfg(target_os = "macos")]
const KEYCHAIN_NOT_FOUND: i32 = -25300;

#[cfg(target_os = "macos")]
pub fn save<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    security_framework::passwords::set_generic_password(
        KEYCHAIN_SERVICE,
        &keychain_account(path)?,
        &serde_json::to_vec(value)?,
    )?;
    Ok(())
}

#[cfg(target_os = "macos")]
pub fn load<T: DeserializeOwned>(path: &Path) -> Result<Option<T>> {
    use security_framework::passwords::{PasswordOptions, generic_password};
    match generic_password(PasswordOptions::new_generic_password(
        KEYCHAIN_SERVICE,
        &keychain_account(path)?,
    )) {
        Ok(bytes) => Ok(Some(serde_json::from_slice(&bytes)?)),
        Err(e) if e.code() == KEYCHAIN_NOT_FOUND => Ok(None),
        Err(e) => Err(e.into()),
    }
}

#[cfg(target_os = "macos")]
pub fn remove(path: &Path) -> Result<()> {
    match security_framework::passwords::delete_generic_password(
        KEYCHAIN_SERVICE,
        &keychain_account(path)?,
    ) {
        Ok(()) => Ok(()),
        Err(e) if e.code() == KEYCHAIN_NOT_FOUND => Ok(()),
        Err(e) => Err(e.into()),
    }
}

#[cfg(all(test, target_os = "macos"))]
mod macos_tests {
    #[test]
    fn keychain_updates_survive_restart_without_plaintext_file() {
        let path = std::env::temp_dir()
            .join(format!("kawa-keychain-test-{:032x}", fastrand::u128(..)))
            .join("account");
        struct Cleanup(std::path::PathBuf);
        impl Drop for Cleanup {
            fn drop(&mut self) {
                let _ = super::remove(&self.0);
            }
        }
        let _cleanup = Cleanup(path.clone());
        super::save(&path, &serde_json::json!({"refresh":"first"})).unwrap();
        let value = serde_json::json!({"refresh":"rotated", "pending":{"checkout_id":"test-only"}});
        super::save(&path, &value).unwrap();
        assert_eq!(
            super::load::<serde_json::Value>(&path).unwrap(),
            Some(value)
        );
        assert!(!path.exists());
        super::remove(&path).unwrap();
        assert!(super::load::<serde_json::Value>(&path).unwrap().is_none());
    }
}

#[cfg(test)]
mod keychain_identity_tests {
    #[test]
    fn store_paths_have_stable_distinct_opaque_keychain_accounts() {
        let base = std::env::temp_dir().join(format!("kawa-keychain-{:032x}", fastrand::u128(..)));
        let first = super::keychain_account(&base.join("one")).unwrap();
        assert_eq!(first, super::keychain_account(&base.join("one")).unwrap());
        assert_ne!(first, super::keychain_account(&base.join("two")).unwrap());
        assert_eq!(first.len(), 64);
        assert!(first.bytes().all(|c| c.is_ascii_hexdigit()));
    }
}
