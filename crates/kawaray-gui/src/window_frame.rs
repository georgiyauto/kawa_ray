//! The app draws its own caption on Windows (`no-frame`). Ask DWM for the
//! Windows 11 rounded corners and keep the system drop shadow and snap layouts.

use crate::AppWindow;
use slint::ComponentHandle;
use slint::winit_030::WinitWindowAccessor;
use slint::winit_030::winit::platform::windows::WindowExtWindows;
use slint::winit_030::winit::raw_window_handle::{HasWindowHandle, RawWindowHandle};
use windows_sys::Win32::Graphics::Dwm::{DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND, DwmSetWindowAttribute};

pub fn style(ui: &AppWindow) {
    let _ = ui.window().with_winit_window(|window| {
        window.set_undecorated_shadow(true);
        if let Ok(handle) = window.window_handle() {
            if let RawWindowHandle::Win32(handle) = handle.as_raw() {
                let preference = DWMWCP_ROUND;
                // Ignored before Windows 11: square corners, no harm done.
                unsafe {
                    DwmSetWindowAttribute(
                        handle.hwnd.get() as _,
                        DWMWA_WINDOW_CORNER_PREFERENCE as u32,
                        (&preference as *const i32).cast(),
                        std::mem::size_of::<i32>() as u32,
                    );
                }
            }
        }
    });
}
