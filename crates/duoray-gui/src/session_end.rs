//! Windows sign-out, restart and shutdown end the app without its window ever
//! closing. Without this the next start would report a crash ("previous run
//! ended without a clean exit") after every reboot.
#[cfg(windows)]
pub fn install(ui: &crate::AppWindow) {
    use slint::ComponentHandle;
    use slint::winit_030::{WinitWindowAccessor, winit::raw_window_handle::{HasWindowHandle, RawWindowHandle}};
    use windows_sys::Win32::{
        Foundation::{HWND, LPARAM, LRESULT, WPARAM},
        UI::{Shell::{DefSubclassProc, SetWindowSubclass}, WindowsAndMessaging::WM_ENDSESSION},
    };
    unsafe extern "system" fn proc(hwnd: HWND, msg: u32, w: WPARAM, l: LPARAM, _id: usize, _data: usize) -> LRESULT {
        if msg == WM_ENDSESSION && w != 0 {
            crate::diag::log("windows session ending");
            crate::diag::clean_shutdown();
        }
        unsafe { DefSubclassProc(hwnd, msg, w, l) }
    }
    let hwnd = ui.window().with_winit_window(|window| match window.window_handle().ok()?.as_raw() {
        RawWindowHandle::Win32(handle) => Some(handle.hwnd.get() as HWND),
        _ => None,
    }).flatten();
    if let Some(hwnd) = hwnd {
        if unsafe { SetWindowSubclass(hwnd, Some(proc), 0x4b415741, 0) } == 0 {
            crate::diag::log("session-end hook could not be installed");
        }
    }
}

#[cfg(not(windows))]
pub fn install(_ui: &crate::AppWindow) {}
