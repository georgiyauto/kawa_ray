#[derive(Default)]
struct PrintScreenEdge {
    down: bool,
    armed: bool,
}

impl PrintScreenEdge {
    fn update(&mut self, down: bool, eligible: bool) -> bool {
        if down {
            if !self.down { self.armed = eligible; }
            self.down = true;
            false
        } else {
            let capture = if self.down { self.armed } else { eligible };
            self.down = false;
            self.armed = false;
            capture
        }
    }
}

/// Observe PrintScreen before Windows sends its release-only window message or
/// opens Snipping Tool. Never consume input or register an OS-wide shortcut.
#[cfg(windows)]
mod windows {
    use super::PrintScreenEdge;
    use crate::AppWindow;
    use slint::{ComponentHandle, Weak};
    use std::cell::RefCell;
    use windows_sys::Win32::{
        Foundation::{HWND, LPARAM, LRESULT, WPARAM},
        UI::{
            Input::KeyboardAndMouse::{GetAsyncKeyState, VK_CONTROL, VK_MENU, VK_SHIFT, VK_LWIN, VK_RWIN, VK_SNAPSHOT},
            WindowsAndMessaging::{CallNextHookEx, GetForegroundWindow, HHOOK, KBDLLHOOKSTRUCT, SetWindowsHookExW, UnhookWindowsHookEx, WH_KEYBOARD_LL, WM_KEYDOWN, WM_KEYUP, WM_SYSKEYDOWN, WM_SYSKEYUP},
        },
    };

    struct State {
        hwnd: HWND,
        ui: Weak<AppWindow>,
        edge: PrintScreenEdge,
    }

    // Low-level callbacks run on the installing GUI thread's message loop.
    thread_local! {
        static STATE: RefCell<Option<State>> = const { RefCell::new(None) };
    }

    pub struct Observer(HHOOK);

    impl Drop for Observer {
        fn drop(&mut self) {
            unsafe { UnhookWindowsHookEx(self.0); }
            STATE.with(|state| *state.borrow_mut() = None);
        }
    }

    pub fn install(ui: &AppWindow) -> Option<Observer> {
        use slint::winit_030::{WinitWindowAccessor, winit::raw_window_handle::{HasWindowHandle, RawWindowHandle}};
        let hwnd = ui.window().with_winit_window(|window| {
            match window.window_handle().ok()?.as_raw() {
                RawWindowHandle::Win32(handle) => Some(handle.hwnd.get() as HWND),
                _ => None,
            }
        }).flatten()?;
        STATE.with(|state| *state.borrow_mut() = Some(State {
            hwnd, ui: ui.as_weak(), edge: PrintScreenEdge::default(),
        }));
        let hook = unsafe { SetWindowsHookExW(WH_KEYBOARD_LL, Some(observe), std::ptr::null_mut(), 0) };
        if hook.is_null() {
            STATE.with(|state| *state.borrow_mut() = None);
            crate::diag::log("PrintScreen observer could not be installed");
            None
        } else {
            Some(Observer(hook))
        }
    }

    unsafe extern "system" fn observe(code: i32, message: WPARAM, data: LPARAM) -> LRESULT {
        if code >= 0 && matches!(message as u32, WM_KEYDOWN | WM_SYSKEYDOWN | WM_KEYUP | WM_SYSKEYUP) {
            let key = unsafe { &*(data as *const KBDLLHOOKSTRUCT) };
            if key.vkCode == VK_SNAPSHOT as u32 {
                let down = matches!(message as u32, WM_KEYDOWN | WM_SYSKEYDOWN);
                let foreground = unsafe { GetForegroundWindow() };
                let modifiers = [VK_CONTROL, VK_MENU, VK_SHIFT, VK_LWIN, VK_RWIN]
                    .into_iter().any(|key| unsafe { GetAsyncKeyState(key as i32) } < 0);
                let capture = STATE.with(|state| {
                    let mut state = state.borrow_mut();
                    let state = state.as_mut()?;
                    state.edge.update(down, foreground == state.hwnd && !modifiers)
                        .then(|| state.ui.clone())
                });
                if let Some(ui) = capture {
                    // The hook must return immediately; PNG/clipboard work belongs
                    // in the GUI loop, after Windows handles the key release.
                    // Eligibility was checked before Snipping Tool could steal focus.
                    slint::Timer::single_shot(std::time::Duration::ZERO, move || {
                        if let Some(ui) = ui.upgrade() { ui.invoke_take_screenshot(); }
                    });
                }
            }
        }
        unsafe { CallNextHookEx(std::ptr::null_mut(), code, message, data) }
    }
}

#[cfg(windows)]
pub use windows::install;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn release_only_printscreen_captures() {
        assert!(PrintScreenEdge::default().update(false, true));
    }

    #[test]
    fn press_repeat_release_captures_once() {
        let mut edge = PrintScreenEdge::default();
        assert!(!edge.update(true, true));
        assert!(!edge.update(true, true));
        assert!(edge.update(false, true));
        assert!(!edge.update(true, true));
        assert!(edge.update(false, true));
    }

    #[test]
    fn background_press_cannot_capture_after_focus_changes() {
        let mut edge = PrintScreenEdge::default();
        assert!(!edge.update(true, false));
        assert!(!edge.update(false, true));
        assert!(edge.update(false, true));
    }

    #[test]
    fn os_focus_change_after_press_does_not_capture_twice() {
        let mut edge = PrintScreenEdge::default();
        assert!(!edge.update(true, true));
        assert!(edge.update(false, false));
        assert!(!edge.update(false, false));
        assert!(!edge.update(true, true));
        assert!(edge.update(false, true));
    }
}
