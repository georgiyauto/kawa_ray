//! Shared policy for package-installed Unix helpers and their maintenance CLI.
use anyhow::{Result, bail};
#[cfg(unix)]
use std::path::Path;

pub const LINUX_HELPERS: &[&str] = &[
    "/usr/libexec/kawa-vpn/KawaVPNHelper",
    "/usr/local/libexec/kawa-vpn/KawaVPNHelper",
];
pub const MACOS_HELPER: &str = "/Library/PrivilegedHelperTools/pro.vinnikov.kawavpn.helper";
pub const MACOS_LABEL: &str = "pro.vinnikov.kawavpn.helper";
pub const LINUX_SERVICE: &str = "kawa-vpn-helper.service";

pub fn parse_uid(value: &str) -> Result<u32> {
    if value.is_empty() || !value.bytes().all(|c| c.is_ascii_digit()) {
        bail!("UID must contain only decimal digits");
    }
    let uid: u32 = value.parse()?;
    if uid == 0 || uid == u32::MAX {
        bail!("UID must identify a non-root desktop user");
    }
    Ok(uid)
}

pub fn validate_metadata(owner: u32, mode: u32, regular: bool, executable: bool) -> Result<()> {
    if owner != 0 || mode & 0o022 != 0 || !regular || (executable && mode & 0o111 == 0) {
        bail!("expected a root-owned regular file without group/other write permissions");
    }
    Ok(())
}

/// Refuse symlinks, writable directories and user-owned ancestors before elevation.
#[cfg(unix)]
pub fn validate_directory(path: &Path) -> Result<()> {
    use std::os::unix::fs::MetadataExt;
    if !path.is_absolute()
        || path
            .components()
            .any(|c| matches!(c, std::path::Component::ParentDir))
    {
        bail!("expected an absolute protected path");
    }
    for parent in path.ancestors() {
        let m = std::fs::symlink_metadata(parent)?;
        if !m.is_dir() || m.uid() != 0 || m.mode() & 0o022 != 0 {
            bail!("untrusted directory: {}", parent.display());
        }
    }
    Ok(())
}

#[cfg(unix)]
pub fn validate_file(path: &Path, executable: bool) -> Result<()> {
    use std::os::unix::fs::MetadataExt;
    validate_directory(
        path.parent()
            .ok_or_else(|| anyhow::anyhow!("missing parent"))?,
    )?;
    let m = std::fs::symlink_metadata(path)?;
    validate_metadata(m.uid(), m.mode(), m.is_file(), executable)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn uid_requires_decimal_non_root_identity() {
        assert_eq!(parse_uid("1000").unwrap(), 1000);
        for invalid in [
            "0",
            "4294967295",
            "4294967296",
            "+1000",
            "-1",
            " 1000",
            "1000\n",
            "",
            "1;id",
        ] {
            assert!(parse_uid(invalid).is_err(), "accepted {invalid:?}");
        }
    }
    #[test]
    fn protected_files_reject_writable_non_root_and_non_executable_helpers() {
        assert!(validate_metadata(0, 0o100755, true, true).is_ok());
        for (uid, mode, regular) in [
            (1000, 0o100755, true),
            (0, 0o100775, true),
            (0, 0o100757, true),
            (0, 0o100644, true),
            (0, 0o120777, false),
        ] {
            assert!(validate_metadata(uid, mode, regular, true).is_err());
        }
        assert!(validate_metadata(0, 0o100644, true, false).is_ok());
    }
}

#[cfg(all(test, unix))]
mod unix_tests {
    use super::*;
    #[test]
    fn unprivileged_paths_and_symlink_ancestors_are_not_trusted() {
        use std::os::unix::fs::PermissionsExt;
        let temporary = tempfile::tempdir().unwrap();
        let helper = temporary.path().join("helper");
        std::fs::write(&helper, b"test executable").unwrap();
        std::fs::set_permissions(&helper, std::fs::Permissions::from_mode(0o755)).unwrap();
        assert!(validate_file(&helper, true).is_err());
        let link = temporary.path().join("link");
        std::os::unix::fs::symlink("/usr", &link).unwrap();
        assert!(validate_directory(&link).is_err());
        assert!(validate_directory(Path::new("relative/path")).is_err());
        assert!(validate_directory(Path::new("/usr/../etc")).is_err());
    }
}
