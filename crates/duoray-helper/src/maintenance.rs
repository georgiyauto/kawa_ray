//! Constant-argument native SCM maintenance; no user-writable scripts.
use anyhow::{Result, bail};
use duoray_core::{helper_proto::SERVICE_NAME, windows_trust::verify_current_helper};
use std::{
    ffi::OsString,
    time::{Duration, Instant},
};
use windows_service::{
    service::{
        ServiceAccess, ServiceErrorControl, ServiceInfo, ServiceStartType, ServiceState,
        ServiceType,
    },
    service_manager::{ServiceManager, ServiceManagerAccess},
};

pub fn run(install: bool) -> Result<()> {
    let executable_path = verify_current_helper()?;
    let manager = ServiceManager::local_computer(
        None::<&str>,
        ServiceManagerAccess::CONNECT
            | if install {
                ServiceManagerAccess::CREATE_SERVICE
            } else {
                ServiceManagerAccess::empty()
            },
    )?;
    if install {
        let info = ServiceInfo {
            name: OsString::from(SERVICE_NAME),
            display_name: OsString::from("Kawa VPN TUN helper"),
            service_type: ServiceType::OWN_PROCESS,
            start_type: ServiceStartType::AutoStart,
            error_control: ServiceErrorControl::Normal,
            executable_path,
            launch_arguments: vec![OsString::from("--service")],
            dependencies: vec![],
            account_name: None,
            account_password: None,
        };
        let access = ServiceAccess::QUERY_STATUS
            | ServiceAccess::START
            | ServiceAccess::STOP
            | ServiceAccess::CHANGE_CONFIG;
        let service = match manager.open_service(SERVICE_NAME, access) {
            Ok(service) => {
                service.change_config(&info)?;
                service
            }
            Err(windows_service::Error::Winapi(e)) if e.raw_os_error() == Some(1060) => {
                manager.create_service(&info, access)?
            }
            Err(e) => return Err(e.into()),
        };
        // Only explicit user-initiated Repair restarts the service; normal
        // application startup and protocol probes never enter this mode.
        stop_and_wait(&service)?;
        service.start::<&str>(&[])?;
    } else {
        let service = manager.open_service(
            SERVICE_NAME,
            ServiceAccess::QUERY_STATUS | ServiceAccess::STOP | ServiceAccess::DELETE,
        )?;
        stop_and_wait(&service)?;
        service.delete()?;
    }
    Ok(())
}

fn stop_and_wait(service: &windows_service::service::Service) -> Result<()> {
    let state = service.query_status()?.current_state;
    if state == ServiceState::Stopped {
        return Ok(());
    }
    if state != ServiceState::StopPending {
        service.stop()?;
    }
    let deadline = Instant::now() + Duration::from_secs(20);
    while service.query_status()?.current_state != ServiceState::Stopped {
        if Instant::now() >= deadline {
            bail!("служба не остановилась вовремя");
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    Ok(())
}
