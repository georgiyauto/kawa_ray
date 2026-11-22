use super::{AppWindow, KawaTray, Shared, toggle_connection, tray_state};
use slint::ComponentHandle;

/// Own both objects for the entire event loop; dropping them removes the icon.
pub struct Tray {
    _icon: KawaTray,
    _timer: slint::Timer,
}

fn sync(icon: &KawaTray, ui: &AppWindow) {
    let name = ui.get_selected_name();
    let state = tray_state::menu_state(
        ui.get_can_connect().then_some(name.as_str()),
        ui.get_conn_state().as_str(),
    );
    icon.set_connect_title(state.connect_title.into());
    icon.set_can_connect(state.can_connect);
    icon.set_can_disconnect(state.can_disconnect);
    icon.set_status_tooltip(format!("Kawa VPN — {}", ui.get_conn_title()).into());
}

pub fn install(ui: &AppWindow, app: &Shared) -> Result<Tray, slint::PlatformError> {
    let exiting = std::rc::Rc::new(std::cell::Cell::new(false));
    let icon = KawaTray::new()?;
    sync(&icon, ui);
    icon.on_connect_selected({
        let (weak, app) = (ui.as_weak(), app.clone());
        move || {
            if let Some(ui) = weak.upgrade() {
                // Check live state again: a menu opened earlier may be stale.
                let name = ui.get_selected_name();
                if tray_state::menu_state(
                    ui.get_can_connect().then_some(name.as_str()),
                    ui.get_conn_state().as_str(),
                )
                .can_connect
                {
                    toggle_connection(&ui, &app);
                }
            }
        }
    });
    icon.on_disconnect({
        let (weak, app) = (ui.as_weak(), app.clone());
        move || {
            if let Some(ui) = weak.upgrade() {
                if tray_state::menu_state(None, ui.get_conn_state().as_str()).can_disconnect {
                    toggle_connection(&ui, &app);
                }
            }
        }
    });
    icon.on_restore_window({
        let weak = ui.as_weak();
        move || {
            if let Some(ui) = weak.upgrade() {
                let _ = ui.show();
                use slint::winit_030::WinitWindowAccessor;
                let _ = ui.window().with_winit_window(|window| {
                    window.set_minimized(false);
                    window.focus_window();
                });
            }
        }
    });
    // main() performs synchronous VPN cleanup after the event loop returns.
    icon.on_exit({
        let exiting = exiting.clone();
        move || exiting.set(true)
    });
    // A visible tray keeps Slint alive. Preserve the existing window-close exit.
    // Closing the window keeps Kawa VPN (and the tunnel) running in the tray;
    // "Выход" in the tray menu quits for real.
    ui.window().on_close_requested(|| slint::CloseRequestResponse::HideWindow);
    let timer = slint::Timer::default();
    timer.start(
        slint::TimerMode::Repeated,
        std::time::Duration::from_millis(150),
        {
            let (weak, tray_weak) = (ui.as_weak(), icon.as_weak());
            move || {
                if let (Some(ui), Some(icon)) = (weak.upgrade(), tray_weak.upgrade()) {
                    if exiting.get() {
                        icon.set_can_connect(false);
                        icon.set_can_disconnect(false);
                        icon.set_status_tooltip("Kawa VPN — завершение…".into());
                        if !tray_state::wait_before_exit(ui.get_conn_state().as_str()) {
                            let _ = slint::quit_event_loop();
                        }
                        return;
                    }
                    sync(&icon, &ui);
                }
            }
        },
    );
    icon.show()?;
    Ok(Tray {
        _icon: icon,
        _timer: timer,
    })
}
