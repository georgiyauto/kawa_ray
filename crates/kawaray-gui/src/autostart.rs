//! Start with Windows: a Task Scheduler logon task (fallback: per-user `Run` entry)
//! that launches Kawa VPN with `--autostart`. On such a launch the window opens
//! right away and, if enabled, the chosen server is connected after the chosen delay.

/// Command-line flag written into the Run entry.
pub const FLAG: &str = "--autostart";

/// Delay choices shown in the settings, in seconds. Index 0 = right away, ahead of other work.
pub const DELAYS: [u32; 5] = [0, 15, 30, 60, 120];
pub const DELAY_LABELS: [&str; 5] = [
    "Сразу — в первую очередь",
    "Через 15 секунд",
    "Через 30 секунд",
    "Через 1 минуту",
    "Через 2 минуты",
];

pub fn delay_index(seconds: u32) -> usize {
    DELAYS.iter().position(|&d| d == seconds).unwrap_or(0)
}

/// True when this process was started by the Windows Run entry.
pub fn launched_by_autostart() -> bool {
    std::env::args().skip(1).any(|a| a == FLAG)
}

#[cfg(windows)]
mod imp {
    //! Autostart lives in the Task Scheduler («Kawa VPN», at log on of this user).
    //! Logon tasks start as soon as the session opens, while `Run` entries
    //! (Steam, Discord…) wait for Explorer, so Kawa VPN comes up first. Creating
    //! a task for one's own logon needs no admin rights. If the scheduler is
    //! unavailable, the old per-user `Run` entry is used instead.
    use std::os::windows::process::CommandExt;
    use std::process::Command;
    const KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
    const NAME: &str = "Kawa VPN";
    const NO_WINDOW: u32 = 0x0800_0000;

    fn run(cmd: &mut Command) -> bool {
        cmd.creation_flags(NO_WINDOW).output().map(|o| o.status.success()).unwrap_or(false)
    }

    fn xml_escape(s: &str) -> String {
        s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
    }

    fn user() -> String {
        let name = std::env::var("USERNAME").unwrap_or_default();
        match std::env::var("USERDOMAIN") {
            Ok(d) if !d.is_empty() => format!("{d}\\{name}"),
            _ => name,
        }
    }

    fn task_xml(exe: &str) -> String {
        let user = xml_escape(&user());
        let exe = xml_escape(exe);
        format!(r#"<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo><Description>Kawa VPN: запуск при входе в Windows</Description></RegistrationInfo>
  <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>{user}</UserId></LogonTrigger></Triggers>
  <Principals><Principal id="Author"><UserId>{user}</UserId><LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal></Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>4</Priority>
  </Settings>
  <Actions Context="Author"><Exec><Command>{exe}</Command><Arguments>{flag}</Arguments></Exec></Actions>
</Task>
"#, flag = super::FLAG)
    }

    fn create_task(exe: &str) -> bool {
        // schtasks reads the XML as UTF-16 LE with a BOM.
        let path = std::env::temp_dir().join(format!("kawa-autostart-{}.xml", std::process::id()));
        let mut bytes = vec![0xFF, 0xFE];
        bytes.extend(task_xml(exe).encode_utf16().flat_map(u16::to_le_bytes));
        if std::fs::write(&path, bytes).is_err() {
            return false;
        }
        let ok = run(Command::new("schtasks").args(["/Create", "/TN", NAME, "/F", "/XML"]).arg(&path));
        let _ = std::fs::remove_file(&path);
        ok
    }

    fn task_exists() -> bool {
        run(Command::new("schtasks").args(["/Query", "/TN", NAME]))
    }

    fn run_entry_exists() -> bool {
        run(Command::new("reg").args(["query", KEY, "/v", NAME]))
    }

    fn remove_run_entry() {
        if run_entry_exists() {
            let _ = run(Command::new("reg").args(["delete", KEY, "/v", NAME, "/f"]));
        }
    }

    pub fn set(enabled: bool) -> anyhow::Result<()> {
        if enabled {
            let exe = std::env::current_exe()?.display().to_string();
            if create_task(&exe) {
                remove_run_entry();
                return Ok(());
            }
            // Scheduler unavailable: fall back to the Run entry.
            let value = format!("\"{exe}\" {}", super::FLAG);
            anyhow::ensure!(
                run(Command::new("reg").args(["add", KEY, "/v", NAME, "/t", "REG_SZ", "/d", &value, "/f"])),
                "reg.exe завершился с ошибкой"
            );
            Ok(())
        } else {
            if task_exists() {
                anyhow::ensure!(run(Command::new("schtasks").args(["/Delete", "/TN", NAME, "/F"])), "schtasks не удалил задачу");
            }
            remove_run_entry();
            Ok(())
        }
    }

    pub fn is_set() -> bool {
        task_exists() || run_entry_exists()
    }

    /// Installs from before the scheduler switch still have only the Run entry:
    /// move them to the task (and refresh the exe path if the app moved).
    pub fn migrate() {
        if run_entry_exists() && !task_exists() {
            if let Ok(exe) = std::env::current_exe() {
                if create_task(&exe.display().to_string()) {
                    remove_run_entry();
                }
            }
        }
    }

    /// "Right away": run above normal priority so the tunnel comes up before other startup apps compete for the CPU.
    pub fn raise_priority() {
        use windows_sys::Win32::System::Threading::{GetCurrentProcess, SetPriorityClass, ABOVE_NORMAL_PRIORITY_CLASS};
        unsafe { SetPriorityClass(GetCurrentProcess(), ABOVE_NORMAL_PRIORITY_CLASS); }
    }
    pub fn normal_priority() {
        use windows_sys::Win32::System::Threading::{GetCurrentProcess, SetPriorityClass, NORMAL_PRIORITY_CLASS};
        unsafe { SetPriorityClass(GetCurrentProcess(), NORMAL_PRIORITY_CLASS); }
    }
}

#[cfg(not(windows))]
mod imp {
    pub fn set(_enabled: bool) -> anyhow::Result<()> { Ok(()) }
    pub fn is_set() -> bool { false }
    pub fn migrate() {}
    pub fn raise_priority() {}
    pub fn normal_priority() {}
}

pub use imp::{is_set, migrate, normal_priority, raise_priority, set};

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn delay_choices_round_trip() {
        for (i, d) in DELAYS.iter().enumerate() { assert_eq!(delay_index(*d), i); }
        assert_eq!(delay_index(999), 0);
        assert_eq!(DELAYS.len(), DELAY_LABELS.len());
    }
}
