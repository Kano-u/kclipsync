//! Interface-independent UDP beacon discovery.
//!
//! mDNS through `mdns-sd` follows the operating system's chosen multicast interfaces, which is
//! exactly what fails when a phone shares its connection through a hotspot: the hotspot interface
//! carries the LAN but is not the default route. This module enumerates every usable interface and
//! sends/receives a small beacon on each one, mirroring LocalSend's multicast strategy in a much
//! smaller form. It is additive: the mDNS path keeps working for ordinary networks.

use std::collections::HashMap;
use std::io::ErrorKind;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use anyhow::Result;

pub const BEACON_PORT: u16 = 47632;
pub const BEACON_MAGIC: &[u8] = b"KCLIPSYNC1";
const BEACON_INTERVAL: Duration = Duration::from_secs(2);
const BEACON_TTL: Duration = Duration::from_secs(12);
const MAX_PACKET: usize = 2048;

#[derive(Clone, Debug)]
struct Peer {
    node_id: String,
    device: String,
    host: String,
    port: u16,
    last_seen: Instant,
}

/// One beacon peer discovered by [`Browser::poll`].
#[derive(Clone, Debug)]
pub struct DiscoveredPeer {
    pub node_id: String,
    pub device: String,
    pub addresses: Vec<String>,
    pub port: u16,
    pub last_seen: Instant,
}

impl DiscoveredPeer {
    pub fn identity(&self) -> String {
        if self.node_id.is_empty() {
            format!("{}:{}", self.device, self.port)
        } else {
            self.node_id.clone()
        }
    }
}

/// Sends this node's beacon on every usable interface until dropped.
pub struct Advertiser {
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl Advertiser {
    pub fn start(node_id: String, device: String, port: u16) -> Result<Self> {
        let stop = Arc::new(AtomicBool::new(false));
        let thread_stop = stop.clone();
        let thread = thread::spawn(move || {
            let payload = encode_beacon(&node_id, &device, port);
            while !thread_stop.load(Ordering::SeqCst) {
                for (interface, target) in broadcast_targets() {
                    if let Ok(socket) = UdpSocket::bind((interface, 0)) {
                        let _ = socket.set_broadcast(true);
                        let _ = socket.send_to(&payload, target);
                    }
                }
                sleep_interruptible(&thread_stop, BEACON_INTERVAL);
            }
        });
        Ok(Self {
            stop,
            thread: Some(thread),
        })
    }
}

impl Drop for Advertiser {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

/// Listens for beacons on every usable interface forever.
pub struct Browser {
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
    peers: Arc<Mutex<HashMap<String, Peer>>>,
}

impl Browser {
    pub fn start() -> Result<Self> {
        let peers: Arc<Mutex<HashMap<String, Peer>>> = Arc::new(Mutex::new(HashMap::new()));
        let stop = Arc::new(AtomicBool::new(false));
        let thread_peers = peers.clone();
        let thread_stop = stop.clone();
        let thread = thread::spawn(move || {
            let socket = match UdpSocket::bind(("0.0.0.0", BEACON_PORT)) {
                Ok(socket) => socket,
                Err(_) => return,
            };
            let _ = socket.set_broadcast(true);
            let _ = socket.set_read_timeout(Some(Duration::from_millis(500)));
            let mut buffer = [0u8; MAX_PACKET];
            while !thread_stop.load(Ordering::SeqCst) {
                match socket.recv_from(&mut buffer) {
                    Ok((length, from)) => {
                        let Some(beacon) = decode_beacon(&buffer[..length], from) else {
                            continue;
                        };
                        if let Ok(mut peers) = thread_peers.lock() {
                            let key = beacon.node_id.clone();
                            match peers.get_mut(&key) {
                                Some(existing) => {
                                    if !beacon.device.is_empty() {
                                        existing.device = beacon.device;
                                    }
                                    existing.host = beacon.host;
                                    existing.port = beacon.port;
                                    existing.last_seen = Instant::now();
                                }
                                None => {
                                    peers.insert(key, beacon);
                                }
                            }
                        }
                    }
                    Err(error)
                        if error.kind() == ErrorKind::WouldBlock
                            || error.kind() == ErrorKind::TimedOut => {}
                    Err(_) => thread::sleep(Duration::from_millis(250)),
                }
            }
        });
        Ok(Self {
            stop,
            thread: Some(thread),
            peers,
        })
    }

    pub fn poll(&self) -> Vec<DiscoveredPeer> {
        let now = Instant::now();
        let Ok(mut peers) = self.peers.lock() else {
            return Vec::new();
        };
        peers.retain(|_, peer| now.duration_since(peer.last_seen) <= BEACON_TTL);
        peers
            .values()
            .map(|peer| DiscoveredPeer {
                node_id: peer.node_id.clone(),
                device: peer.device.clone(),
                addresses: vec![peer.host.clone()],
                port: peer.port,
                last_seen: peer.last_seen,
            })
            .collect()
    }
}

impl Drop for Browser {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

fn encode_beacon(node_id: &str, device: &str, port: u16) -> Vec<u8> {
    let node_id = node_id.as_bytes();
    let device = device.as_bytes();
    let mut out =
        Vec::with_capacity(BEACON_MAGIC.len() + 1 + 2 + node_id.len() + 2 + device.len() + 2);
    out.extend_from_slice(BEACON_MAGIC);
    out.push(1);
    out.extend_from_slice(&(node_id.len() as u16).to_be_bytes());
    out.extend_from_slice(node_id);
    out.extend_from_slice(&(device.len() as u16).to_be_bytes());
    out.extend_from_slice(device);
    out.extend_from_slice(&port.to_be_bytes());
    out
}

fn decode_beacon(data: &[u8], from: SocketAddr) -> Option<Peer> {
    if data.len() < BEACON_MAGIC.len() + 1 + 2 {
        return None;
    }
    if &data[..BEACON_MAGIC.len()] != BEACON_MAGIC {
        return None;
    }
    let mut offset = BEACON_MAGIC.len();
    if data[offset] != 1 {
        return None;
    }
    offset += 1;
    let node_len = u16::from_be_bytes([data[offset], data[offset + 1]]) as usize;
    offset += 2;
    if data.len() < offset + node_len + 2 {
        return None;
    }
    let node_id = String::from_utf8_lossy(&data[offset..offset + node_len]).into_owned();
    offset += node_len;
    let device_len = u16::from_be_bytes([data[offset], data[offset + 1]]) as usize;
    offset += 2;
    if data.len() < offset + device_len + 2 {
        return None;
    }
    let device = String::from_utf8_lossy(&data[offset..offset + device_len]).into_owned();
    offset += device_len;
    let port = u16::from_be_bytes([data[offset], data[offset + 1]]);
    Some(Peer {
        node_id,
        device,
        host: from.ip().to_string(),
        port,
        last_seen: Instant::now(),
    })
}

fn broadcast_targets() -> Vec<(Ipv4Addr, SocketAddr)> {
    let mut targets = Vec::new();
    let Ok(interfaces) = if_addrs::get_if_addrs() else {
        return targets;
    };
    for interface in interfaces {
        if interface.is_loopback() {
            continue;
        }
        let if_addrs::IfAddr::V4(v4) = interface.addr else {
            continue;
        };
        if v4.ip.is_unspecified() || v4.ip.is_broadcast() {
            continue;
        }
        let broadcast = match v4.broadcast {
            Some(broadcast) if !broadcast.is_unspecified() => broadcast,
            _ => Ipv4Addr::new(v4.ip.octets()[0], v4.ip.octets()[1], v4.ip.octets()[2], 255),
        };
        targets.push((
            v4.ip,
            SocketAddr::V4(SocketAddrV4::new(broadcast, BEACON_PORT)),
        ));
    }
    targets.sort_by_key(|(ip, target)| (*ip, *target));
    targets.dedup();
    targets
}

fn sleep_interruptible(stop: &AtomicBool, duration: Duration) {
    let step = Duration::from_millis(200);
    let mut slept = Duration::ZERO;
    while slept < duration && !stop.load(Ordering::SeqCst) {
        let part = (duration - slept).min(step);
        thread::sleep(part);
        slept += part;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn beacon_round_trip() {
        let payload = encode_beacon("node-1", "Phone", 47631);
        let from: SocketAddr = "192.0.2.10:1234".parse().unwrap();
        let peer = decode_beacon(&payload, from).unwrap();
        assert_eq!(peer.node_id, "node-1");
        assert_eq!(peer.device, "Phone");
        assert_eq!(peer.host, "192.0.2.10");
        assert_eq!(peer.port, 47631);
    }

    #[test]
    fn rejects_foreign_packets() {
        let from: SocketAddr = "192.0.2.10:1234".parse().unwrap();
        assert!(decode_beacon(b"hello", from).is_none());
    }
}
