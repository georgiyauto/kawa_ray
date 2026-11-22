//! Only one Kawa VPN window per user session. A second copy (autostart racing a
//! manual launch, a double click on the shortcut) used to start its own
//! connection: it killed the first copy's xray through the shared pid file and
//! hit "another Kawa VPN session is active" in the helper. Now the second copy
//! brings the first window forward and exits without touching anything.

/// Held for the life of the process; dropping it releases the lock.
pub struct Guard {
    #[cfg(windows)]
    _handle: isize,
    #[cfg(unix)]
    _file: std::fs::File,
}

/// `None` when another copy is already running (it has been asked to show itself).
#[cfg(windows)]
pub fn acquire(_dir: &std::path::Path) -> Option<Guard> {
    use windows_sys::Win32::Foundation::{GetLastError, ERROR_ALREADY_EXISTS};
    use windows_sys::Win32::System::Threading::CreateMutexW;
    let name: Vec<u16> = "Local\\KawaVPN-GUI-single-instance\0".encode_utf16().collect();
    // SAFETY: valid NUL-terminated wide string, default security attributes.
    let handle = unsafe { CreateMutexW(std::ptr::null(), 0, name.as_ptr()) };
    // SAFETY: plain thread-local error read right after the call.
    let exists = unsafe { GetLastError() } == ERROR_ALREADY_EXISTS;
    if handle.is_null() {
        // Cannot tell: do not block the start.
        return Some(Guard { _handle: 0 });
    }
    if exists {
        show_existing();
        return None;
    }
    Some(Guard { _handle: handle as isize })
}

#[cfg(windows)]
fn show_existing() {
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        FindWindowW, IsIconic, SetForegroundWindow, ShowWindow, SW_RESTORE, SW_SHOW,
    };
    let title: Vec<u16> = "Kawa VPN\0".encode_utf16().collect();
    // SAFETY: valid NUL-terminated wide string; a null class matches any window class.
    unsafe {
        let hwnd = FindWindowW(std::ptr::null(), title.as_ptr());
        if hwnd.is_null() {
            return;
        }
        ShowWindow(hwnd, if IsIconic(hwnd) != 0 { SW_RESTORE } else { SW_SHOW });
        SetForegroundWindow(hwnd);
    }
}

#[cfg(unix)]
pub fn acquire(dir: &std::path::Path) -> Option<Guard> {
    use std::os::fd::AsRawFd;
    let _ = std::fs::create_dir_all(dir);
    let Ok(file) = std::fs::OpenOptions::new().create(true).truncate(false).write(true).open(dir.join("gui.lock")) else {
        return Some(Guard { _file: tempfile_fallback() });
    };
    // SAFETY: valid open descriptor owned by `file`.
    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        return None;
    }
    Some(Guard { _file: file })
}

#[cfg(unix)]
fn tempfile_fallback() -> std::fs::File {
    std::fs::File::open("/dev/null").expect("/dev/null")
}
