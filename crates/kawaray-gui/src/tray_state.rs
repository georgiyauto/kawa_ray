#[derive(Debug, PartialEq)]
pub struct MenuState {
    pub connect_title: String,
    pub can_connect: bool,
    pub can_disconnect: bool,
}

pub fn menu_state(selected: Option<&str>, phase: &str) -> MenuState {
    MenuState {
        connect_title: selected.map_or_else(
            || "Подключить — сервер не выбран".into(),
            |name| format!("Подключить — {name}"),
        ),
        can_connect: selected.is_some() && matches!(phase, "idle" | "failed"),
        can_disconnect: phase == "connected",
    }
}

pub fn wait_before_exit(phase: &str) -> bool {
    matches!(phase, "connecting" | "switching" | "disconnecting")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connect_uses_current_selection_and_never_offers_an_empty_server() {
        assert_eq!(
            menu_state(Some("Нидерланды"), "idle"),
            MenuState {
                connect_title: "Подключить — Нидерланды".into(),
                can_connect: true,
                can_disconnect: false,
            }
        );
        assert_eq!(
            menu_state(Some("Германия"), "failed").connect_title,
            "Подключить — Германия"
        );
        assert!(!menu_state(None, "idle").can_connect);
    }

    #[test]
    fn connect_is_not_a_disconnect_toggle_and_busy_actions_are_disabled() {
        let connected = menu_state(Some("Германия"), "connected");
        assert!(!connected.can_connect);
        assert!(connected.can_disconnect);
        for phase in ["connecting", "switching", "disconnecting"] {
            let menu = menu_state(Some("Германия"), phase);
            assert!(!menu.can_connect);
            assert!(!menu.can_disconnect);
        }
    }

    #[test]
    fn exit_waits_for_connection_workers_before_final_cleanup() {
        for phase in ["connecting", "switching", "disconnecting"] {
            assert!(wait_before_exit(phase));
        }
        for phase in ["idle", "connected", "failed"] {
            assert!(!wait_before_exit(phase));
        }
    }
}
