use std::collections::HashMap;
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use flume::{Receiver, TryRecvError};
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};

pub const SERVICE_TYPE: &str = "_kclipsync._tcp.local.";

pub struct Advertiser {
    daemon: ServiceDaemon,
}

impl Advertiser {
    pub fn start(name: &str, host: &str, port: u16, node_id: &str) -> Result<Self> {
        let daemon = ServiceDaemon::new().context("创建 mDNS 服务失败")?;
        let properties = [("v", "1".to_string()), ("id", node_id.to_string())];
        let service = ServiceInfo::new(
            SERVICE_TYPE,
            name,
            &format!("{}.local.", sanitize_host(host)),
            (),
            port,
            &properties[..],
        )
        .context("生成 mDNS 服务信息失败")?
        .enable_addr_auto();
        daemon.register(service).context("注册 mDNS 服务失败")?;
        Ok(Self { daemon })
    }
}

fn sanitize_host(host: &str) -> String {
    let mut out = String::new();
    let mut last_dash = false;
    for ch in host.chars() {
        if ch.is_ascii_alphanumeric() {
            out.push(ch.to_ascii_lowercase());
            last_dash = false;
        } else if !last_dash && !out.is_empty() {
            out.push('-');
            last_dash = true;
        }
        if out.len() == 63 {
            break;
        }
    }
    let host = out.trim_matches('-');
    if host.is_empty() {
        "kclipsync".to_string()
    } else {
        host.to_string()
    }
}

impl Drop for Advertiser {
    fn drop(&mut self) {
        let _ = self.daemon.shutdown();
    }
}

#[derive(Clone, Debug)]
pub struct Discovered {
    pub name: String,
    pub port: u16,
    pub addresses: Vec<String>,
    pub node_id: Option<String>,
    pub last_seen: Instant,
}

impl Discovered {
    pub fn identity(&self) -> String {
        self.node_id.clone().unwrap_or_else(|| self.name.clone())
    }
}

pub struct Browser {
    daemon: ServiceDaemon,
    events: Receiver<ServiceEvent>,
    services: HashMap<String, Discovered>,
}

impl Browser {
    pub fn start() -> Result<Self> {
        let daemon = ServiceDaemon::new().context("创建 mDNS 浏览器失败")?;
        let events = daemon.browse(SERVICE_TYPE).context("浏览 mDNS 服务失败")?;
        Ok(Self {
            daemon,
            events,
            services: HashMap::new(),
        })
    }

    pub fn poll(&mut self, stale_after: Duration) -> Vec<Discovered> {
        loop {
            match self.events.try_recv() {
                Ok(ServiceEvent::ServiceResolved(info)) => {
                    let addresses = info
                        .get_addresses()
                        .iter()
                        .map(ToString::to_string)
                        .collect::<Vec<_>>();
                    if addresses.is_empty() {
                        continue;
                    }
                    let node_id = info
                        .get_property_val_str("id")
                        .map(str::to_string)
                        .filter(|id| !id.is_empty());
                    let discovered = Discovered {
                        name: info.get_fullname().to_string(),
                        port: info.get_port(),
                        addresses,
                        node_id,
                        last_seen: Instant::now(),
                    };
                    self.services.insert(discovered.name.clone(), discovered);
                }
                Ok(ServiceEvent::ServiceRemoved(_, fullname)) => {
                    self.services.remove(&fullname);
                }
                Ok(_) => {}
                Err(TryRecvError::Empty) | Err(TryRecvError::Disconnected) => break,
            }
        }
        let now = Instant::now();
        self.services
            .retain(|_, service| now.duration_since(service.last_seen) <= stale_after);
        self.services.values().cloned().collect()
    }
}

impl Drop for Browser {
    fn drop(&mut self) {
        let _ = self.daemon.stop_browse(SERVICE_TYPE);
        let _ = self.daemon.shutdown();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_prefers_node_id() {
        let service = Discovered {
            name: "phone._kclipsync._tcp.local.".to_string(),
            port: 47631,
            addresses: vec!["192.0.2.2".to_string()],
            node_id: Some("abc".to_string()),
            last_seen: Instant::now(),
        };
        assert_eq!(service.identity(), "abc");
    }

    #[test]
    fn host_is_dns_safe() {
        assert_eq!(sanitize_host("My PC 中文"), "my-pc");
        assert_eq!(sanitize_host("  "), "kclipsync");
    }
}
