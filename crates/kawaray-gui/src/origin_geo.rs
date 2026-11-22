//! Country of the physical uplink, even while another tunnel is active.
//! No unbound or environment-proxy fallback: that would measure the VPN exit.
use std::{io::{Read,Write},net::{SocketAddr,TcpStream},time::{Duration,Instant}};
use ureq::unversioned::{resolver::DefaultResolver,transport::{Buffers,ConnectionDetails,Connector,LazyBuffers,NextTimeout,RustlsConnector,Transport}};

fn connect_bound(address:SocketAddr,interface:&str,timeout:Duration)->anyhow::Result<TcpStream> {
    let socket=socket2::Socket::new(socket2::Domain::IPV4,socket2::Type::STREAM,Some(socket2::Protocol::TCP))?;
    crate::ping::bind_to(&socket,Some(interface))?;
    socket.connect_timeout(&address.into(),timeout)?;
    Ok(socket.into())
}

#[derive(Debug)]
struct UplinkConnector(String);
impl Connector for UplinkConnector {
    type Out=UplinkTransport;
    fn connect(&self,details:&ConnectionDetails,_:Option<()>)->Result<Option<Self::Out>,ureq::Error> {
        let started=Instant::now();
        let budget=details.timeout.not_zero().map(|v|*v).unwrap_or(Duration::from_secs(4));
        let mut last=std::io::Error::new(std::io::ErrorKind::AddrNotAvailable,"physical uplink has no IPv4 endpoint");
        for address in details.addrs.iter().filter(|a|a.is_ipv4()).take(2) {
            let remaining=budget.saturating_sub(started.elapsed()).min(Duration::from_secs(2));
            if remaining.is_zero(){return Err(ureq::Error::Timeout(details.timeout.reason));}
            match connect_bound(*address,&self.0,remaining) {
                Ok(stream)=>return Ok(Some(UplinkTransport{stream,buffers:LazyBuffers::new(details.config.input_buffer_size(),details.config.output_buffer_size())})),
                Err(error)=>last=std::io::Error::other(error.to_string()),
            }
        }
        Err(last.into())
    }
}

#[derive(Debug)]
struct UplinkTransport {stream:TcpStream,buffers:LazyBuffers}
fn timeout_error(error:std::io::Error,timeout:NextTimeout)->ureq::Error {
    if matches!(error.kind(),std::io::ErrorKind::TimedOut|std::io::ErrorKind::WouldBlock){ureq::Error::Timeout(timeout.reason)}else{error.into()}
}
impl Transport for UplinkTransport {
    fn buffers(&mut self)->&mut dyn Buffers {&mut self.buffers}
    fn transmit_output(&mut self,amount:usize,timeout:NextTimeout)->Result<(),ureq::Error> {
        self.stream.set_write_timeout(timeout.not_zero().map(|v|*v))?;
        self.stream.write_all(&self.buffers.output()[..amount]).map_err(|e|timeout_error(e,timeout))
    }
    fn await_input(&mut self,timeout:NextTimeout)->Result<bool,ureq::Error> {
        self.stream.set_read_timeout(timeout.not_zero().map(|v|*v))?;
        let amount=self.stream.read(self.buffers.input_append_buf()).map_err(|e|timeout_error(e,timeout))?;
        self.buffers.input_appended(amount);Ok(amount>0)
    }
    fn is_open(&mut self)->bool {
        if self.stream.set_nonblocking(true).is_err(){return false;}
        let state=self.stream.peek(&mut [0]);
        self.stream.set_nonblocking(false).is_ok() && match state {
            Ok(n)=>n>0,Err(error)=>error.kind()==std::io::ErrorKind::WouldBlock,
        }
    }
}

pub(crate) fn probe_country()->Option<String> {
    let interface=crate::connection::uplink_interface()?;
    let config=ureq::Agent::config_builder().proxy(None).max_idle_connections(0).timeout_global(Some(Duration::from_secs(7))).build();
    let agent=ureq::Agent::with_parts(config,UplinkConnector(interface).chain(RustlsConnector::default()),DefaultResolver::default());
    let body=agent.get("https://www.cloudflare.com/cdn-cgi/trace").call().ok()?.body_mut().read_to_string().ok()?;
    crate::globe::trace_country(&body).filter(|code|crate::globe::region(code).is_some())
}

#[cfg(test)] mod tests {
    use super::*;
    #[test]
    fn invalid_uplink_never_falls_back_to_the_default_route() {
        let listener=std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        assert!(connect_bound(listener.local_addr().unwrap(),"kawa-nonexistent-physical-uplink",Duration::from_millis(100)).is_err());
        listener.set_nonblocking(true).unwrap();
        assert_eq!(listener.accept().unwrap_err().kind(),std::io::ErrorKind::WouldBlock);
    }
    #[test] #[ignore="explicit country-only probe of the physical connection; never changes VPN state"]
    fn live_physical_origin_probe() {
        assert_eq!(std::env::var("KAWA_LIVE_ORIGIN_TEST").as_deref(),Ok("1"));
        let country=probe_country().expect("physical country probe failed");
        if let Ok(expected)=std::env::var("KAWA_EXPECT_ORIGIN_COUNTRY"){assert_eq!(country,expected);}
        println!("PHYSICAL_ORIGIN_COUNTRY={country}");
    }
}
