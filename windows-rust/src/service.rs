use std::collections::{HashSet, VecDeque};
use std::io::ErrorKind;
use std::net::{IpAddr, Ipv4Addr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use anyhow::{Context, Result};
use uuid::Uuid;

use crate::beacon;
use crate::clipboard::{
    Event as ClipboardEvent, Sender as ClipboardSender, run_message_loop, start,
};
use crate::config::Config;
use crate::logger::Logger;
use crate::mdns::{Advertiser, Browser, Discovered};
use crate::protocol::{
    Channel, Hello, PROTOCOL_VERSION, T_BYE, T_CLIP, T_HELLO, T_PING, T_PONG, normalize_text,
    parse_clip, sha256_hex,
};

const DIAL_RETRY_MIN: Duration = Duration::from_secs(1);
const DIAL_RETRY_MAX: Duration = Duration::from_secs(30);
const PING_INTERVAL: Duration = Duration::from_secs(30);
const RECENT_HASHES: usize = 256;
const MDNS_REFRESH: Duration = Duration::from_secs(2);
const MDNS_FORGET: Duration = Duration::from_secs(30);
const ACCEPT_POLL: Duration = Duration::from_millis(200);

pub struct Shared {
    config: Config,
    logger: Arc<Logger>,
    node_id: String,
    device: String,
    clipboard: Mutex<Option<ClipboardSender>>,
    sequence: Mutex<u64>,
    recent: Mutex<RecentHashes>,
    links: Mutex<Vec<Arc<Link>>>,
    clipboard_active: AtomicBool,
    stopping: AtomicBool,
    generation: AtomicU64,
}

#[derive(Default)]
struct RecentHashes {
    order: VecDeque<String>,
    values: HashSet<String>,
}

impl RecentHashes {
    fn contains(&self, hash: &str) -> bool {
        self.values.contains(hash)
    }

    fn insert(&mut self, hash: String) {
        if self.values.insert(hash.clone()) {
            self.order.push_back(hash);
        }
        while self.order.len() > RECENT_HASHES {
            if let Some(old) = self.order.pop_front() {
                self.values.remove(&old);
            }
        }
    }
}

struct Link {
    channel: Channel,
    closed: AtomicBool,
    generation: u64,
    initiator: String,
}

impl Link {
    fn send_clip(&self, sequence: u64, text: &str) -> Result<()> {
        self.channel.send_clip(sequence, text)
    }

    fn send_bye(&self, reason: &str) {
        self.channel.send_bye(reason);
    }

    fn shutdown(&self) {
        self.closed.store(true, Ordering::SeqCst);
        self.channel.shutdown();
    }
}

struct Listener {
    stop: Arc<AtomicBool>,
    thread: JoinHandle<()>,
}

struct ClipboardRuntime {
    sender: ClipboardSender,
    worker: JoinHandle<()>,
    message_loop: JoinHandle<()>,
}

pub struct Service {
    shared: Arc<Shared>,
    listener: Mutex<Option<Listener>>,
    clipboard: Mutex<Option<ClipboardRuntime>>,
    generation: Mutex<Option<Arc<AtomicU64>>>,
}

impl Shared {
    fn generation(&self) -> u64 {
        self.generation.load(Ordering::SeqCst)
    }

    fn is_current(&self, generation: u64) -> bool {
        !self.stopping.load(Ordering::SeqCst) && self.generation() == generation
    }

    fn clipboard(&self) -> Option<ClipboardSender> {
        *self.clipboard.lock().unwrap()
    }

    fn set_clipboard(&self, sender: Option<ClipboardSender>) {
        *self.clipboard.lock().unwrap() = sender;
    }

    fn set_text(&self, text: String) -> Result<()> {
        match self.clipboard() {
            Some(sender) if self.clipboard_active.load(Ordering::SeqCst) => sender.set_text(text),
            _ => Ok(()),
        }
    }

    fn next_sequence(&self) -> u64 {
        let mut sequence = self.sequence.lock().unwrap();
        *sequence = sequence.wrapping_add(1);
        *sequence
    }

    fn hello(&self, connection_initiator: &str) -> Hello {
        Hello {
            v: PROTOCOL_VERSION,
            id: self.node_id.clone(),
            device: self.device.clone(),
            port: self.config.port,
            initiator: connection_initiator.to_string(),
        }
    }

    fn register_link(&self, generation: u64, link: Arc<Link>) -> Result<(), String> {
        if !self.is_current(generation) {
            return Err("stale generation".to_string());
        }
        let node_id = link.channel.node_id();
        if node_id.is_empty() || node_id == self.node_id {
            return Err("invalid peer node id".to_string());
        }

        let mut links = self.links.lock().unwrap();
        if !self.is_current(generation) {
            return Err("stale generation".to_string());
        }
        if let Some(existing) = links
            .iter()
            .find(|candidate| candidate.channel.node_id() == node_id)
            .cloned()
        {
            if existing.closed.load(Ordering::SeqCst) {
                links.retain(|candidate| !Arc::ptr_eq(candidate, &existing));
            } else if should_replace_ids(&existing.initiator, &link.initiator) {
                existing.send_bye("duplicate");
                existing.shutdown();
                links.retain(|candidate| !Arc::ptr_eq(candidate, &existing));
            } else {
                return Err("duplicate".to_string());
            }
        }
        links.push(link);
        Ok(())
    }

    fn unregister_link(&self, link: &Arc<Link>) {
        self.links
            .lock()
            .unwrap()
            .retain(|existing| !Arc::ptr_eq(existing, link));
    }

    fn clear_links(&self) {
        let links = std::mem::take(&mut *self.links.lock().unwrap());
        for link in links {
            link.shutdown();
        }
    }

    fn links(&self) -> Vec<Arc<Link>> {
        self.links.lock().unwrap().clone()
    }

    fn connected_count(&self) -> usize {
        self.links.lock().unwrap().len()
    }

    fn broadcast(&self, text: &str, except: Option<&Arc<Link>>) {
        let sequence = self.next_sequence();
        for link in self.links() {
            if except.is_some_and(|except| Arc::ptr_eq(&link, except)) {
                continue;
            }
            if let Err(error) = link.send_clip(sequence, text) {
                self.logger
                    .warn(format!("发送文本到 {} 失败：{error}", link_name(&link)));
                link.shutdown();
            }
        }
    }

    fn handle_local_text(&self, generation: u64, text: String) {
        if !self.is_current(generation) || !self.clipboard_active.load(Ordering::SeqCst) {
            return;
        }
        let normalized = normalize_text(&text);
        if normalized.len() > self.config.max_bytes {
            self.logger.warn("本地文本超过 max_bytes，已忽略");
            return;
        }
        let hash = sha256_hex(normalized.as_bytes());
        {
            let mut recent = self.recent.lock().unwrap();
            if recent.contains(&hash) {
                return;
            }
            recent.insert(hash);
        }
        self.logger.info(format!(
            "本地文本 -> {} 台已连接设备",
            self.connected_count()
        ));
        self.broadcast(&normalized, None);
    }

    fn handle_remote_text(&self, link: &Arc<Link>, payload: &[u8]) {
        if !self.is_current(link.generation) {
            return;
        }
        let clip = match parse_clip(payload, self.config.max_bytes) {
            Ok(clip) => clip,
            Err(error) => {
                self.logger.warn(format!("丢弃 CLIP：{error}"));
                return;
            }
        };
        let normalized = normalize_text(&clip.data);
        let hash = sha256_hex(normalized.as_bytes());
        {
            let mut recent = self.recent.lock().unwrap();
            if recent.contains(&hash) {
                return;
            }
            recent.insert(hash);
        }
        if let Err(error) = self.set_text(normalized.clone()) {
            self.logger.warn(format!("写入剪贴板失败：{error}"));
        }
        self.logger
            .info(format!("收到 {} 的文本 -> 本机剪贴板", link_name(link)));
        self.broadcast(&normalized, Some(link));
    }
}

fn should_replace_ids(existing_initiator: &str, new_initiator: &str) -> bool {
    if existing_initiator != new_initiator {
        new_initiator < existing_initiator
    } else {
        false
    }
}

fn link_name(link: &Link) -> String {
    format!("{} [{}]", link.channel.device(), link.channel.remote().ip())
}

impl Service {
    pub fn new(config: Config, log_path: &std::path::Path) -> Result<Self> {
        let logger = Arc::new(Logger::open(log_path, config.log_max_bytes)?);
        let node_id = Uuid::new_v4().to_string();
        let device = config.resolved_device_name();
        let shared = Arc::new(Shared {
            config,
            logger,
            node_id,
            device,
            clipboard: Mutex::new(None),
            sequence: Mutex::new(0),
            recent: Mutex::new(RecentHashes::default()),
            links: Mutex::new(Vec::new()),
            clipboard_active: AtomicBool::new(false),
            stopping: AtomicBool::new(false),
            generation: AtomicU64::new(0),
        });
        let mut service = Self {
            shared,
            listener: Mutex::new(None),
            clipboard: Mutex::new(None),
            generation: Mutex::new(None),
        };
        service.start_clipboard()?;
        service.shared.logger.info(format!(
            "KClipSync 节点 {}，设备 {}，协议 {}",
            &service.shared.node_id[..8],
            service.shared.device,
            PROTOCOL_VERSION
        ));
        Ok(service)
    }

    fn start_clipboard(&mut self) -> Result<()> {
        let (events_tx, events_rx) = std::sync::mpsc::channel();
        let sender = start(events_tx)?;
        self.shared.set_clipboard(Some(sender));
        let worker_shared = self.shared.clone();
        let generation = Arc::new(AtomicU64::new(0));
        let worker_generation = generation.clone();
        let worker = thread::spawn(move || {
            for event in events_rx {
                match event {
                    ClipboardEvent::Text(text) => worker_shared
                        .handle_local_text(worker_generation.load(Ordering::SeqCst), text),
                    ClipboardEvent::Error(error) => worker_shared.logger.warn(error),
                    ClipboardEvent::Stopped => break,
                }
            }
        });
        let message_sender = self.shared.clipboard().context("剪贴板发送器未创建")?;
        let logger = self.shared.logger.clone();
        let message_loop = thread::spawn(move || {
            if let Err(error) = run_message_loop(&message_sender) {
                logger.warn(format!("剪贴板消息循环结束：{error}"));
            }
        });
        *self.generation.lock().unwrap() = Some(generation);
        *self.clipboard.lock().unwrap() = Some(ClipboardRuntime {
            sender: message_sender,
            worker,
            message_loop,
        });
        Ok(())
    }

    pub fn start(&self) -> Result<()> {
        let mut listener_slot = self.listener.lock().unwrap();
        if listener_slot.is_some() {
            return Ok(());
        }
        let generation = self.shared.generation.fetch_add(1, Ordering::SeqCst) + 1;
        if let Some(clipboard_generation) = self.generation.lock().unwrap().as_ref() {
            clipboard_generation.store(generation, Ordering::SeqCst);
        }

        let listener = match start_listener(self.shared.clone(), generation) {
            Ok(listener) => listener,
            Err(error) => {
                self.shared.generation.fetch_add(1, Ordering::SeqCst);
                return Err(error);
            }
        };

        let name = format!("KClipSync on {}", self.shared.device);
        thread::spawn(start_mdns(self.shared.clone(), generation, name.clone()));
        thread::spawn(start_discovery(self.shared.clone(), generation, name));
        thread::spawn(start_beacon_discovery(self.shared.clone(), generation));
        thread::spawn(start_ping_loop(self.shared.clone(), generation));

        if let Some(sender) = self.shared.clipboard() {
            sender.set_active(true);
        }
        self.shared.clipboard_active.store(true, Ordering::SeqCst);
        self.shared.logger.info(format!(
            "开始同步：端口 {}，日志中不记录剪贴板内容",
            self.shared.config.port
        ));
        *listener_slot = Some(listener);
        Ok(())
    }

    pub fn stop(&self) {
        self.shared.stopping.store(true, Ordering::SeqCst);
        self.shared.generation.fetch_add(1, Ordering::SeqCst);
        self.shared.clear_links();
        if let Some(listener) = self.listener.lock().unwrap().take() {
            listener.stop.store(true, Ordering::SeqCst);
            let _ = listener.thread.join();
        }
        if let Some(sender) = self.shared.clipboard() {
            sender.set_active(false);
        }
        self.shared.clipboard_active.store(false, Ordering::SeqCst);
        if let Some(runtime) = self.clipboard.lock().unwrap().take() {
            runtime.sender.stop();
            let _ = runtime.worker.join();
            let _ = runtime.message_loop.join();
        }
        self.shared.set_clipboard(None);
        self.shared.logger.info("KClipSync 已停止");
    }
}

impl Drop for Service {
    fn drop(&mut self) {
        self.stop();
    }
}

pub fn run_service(config: Config, log_path: &std::path::Path) -> Result<()> {
    let service = Service::new(config, log_path)?;
    service.start()?;
    thread::park();
    Ok(())
}

fn start_listener(shared: Arc<Shared>, generation: u64) -> Result<Listener> {
    let listener = TcpListener::bind(("::", shared.config.port))
        .or_else(|_| TcpListener::bind((IpAddr::V4(Ipv4Addr::UNSPECIFIED), shared.config.port)))
        .with_context(|| format!("无法监听端口 {}", shared.config.port))?;
    listener.set_nonblocking(true)?;
    shared
        .logger
        .info(format!("监听端口 {}", shared.config.port));
    let stop = Arc::new(AtomicBool::new(false));
    let thread_stop = stop.clone();
    let thread = thread::spawn(move || {
        while !thread_stop.load(Ordering::SeqCst) && shared.is_current(generation) {
            match listener.accept() {
                Ok((stream, _)) => {
                    let shared = shared.clone();
                    thread::spawn(move || handle_inbound(shared, generation, stream));
                }
                Err(error) if error.kind() == ErrorKind::WouldBlock => {
                    thread::sleep(ACCEPT_POLL);
                }
                Err(error) => {
                    if shared.is_current(generation) {
                        shared.logger.warn(format!("接受连接失败：{error}"));
                    }
                    thread::sleep(ACCEPT_POLL);
                }
            }
        }
    });
    Ok(Listener { stop, thread })
}

fn handle_inbound(shared: Arc<Shared>, generation: u64, stream: TcpStream) {
    let result = (|| -> Result<()> {
        let channel = Channel::accept(stream)?;
        let hello = channel.read_hello(shared.config.max_frame())?;
        channel.send_hello(&shared.hello(&hello.initiator))?;
        channel.after_handshake()?;
        serve_channel(shared.clone(), generation, channel, hello.initiator)?;
        Ok(())
    })();
    if let Err(error) = result {
        if shared.is_current(generation) {
            shared.logger.info(format!("入站连接结束：{error}"));
        }
    }
}

fn start_mdns(shared: Arc<Shared>, generation: u64, name: String) -> impl FnOnce() {
    move || {
        let host = shared.device.clone();
        let node_id = shared.node_id.clone();
        let port = shared.config.port;
        let _advertiser = match Advertiser::start(&name, &host, port, &node_id) {
            Ok(advertiser) => advertiser,
            Err(error) => {
                if shared.is_current(generation) {
                    shared.logger.warn(format!("mDNS 广播不可用：{error}"));
                }
                return;
            }
        };
        if shared.is_current(generation) {
            shared.logger.info(format!("mDNS 广播 {name}，端口 {port}"));
        }
        while shared.is_current(generation) {
            thread::sleep(Duration::from_millis(200));
        }
    }
}

struct DiscoveredDialer {
    stop: Arc<AtomicBool>,
    addresses: Arc<Mutex<Vec<String>>>,
}

impl DiscoveredDialer {
    fn start(shared: Arc<Shared>, generation: u64, service: Discovered) -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let addresses = Arc::new(Mutex::new(service.addresses.clone()));
        let thread_stop = stop.clone();
        let thread_addresses = addresses.clone();
        let port = service.port;
        let label = service.name.clone();
        thread::spawn(move || {
            let mut backoff = DIAL_RETRY_MIN;
            while shared.is_current(generation) && !thread_stop.load(Ordering::SeqCst) {
                let targets = thread_addresses.lock().unwrap().clone();
                let mut last_error = None;
                for address in &targets {
                    match discover_connect(&shared, generation, address, port) {
                        Ok(()) => break,
                        Err(error) => last_error = Some(error),
                    }
                }
                if let Some(error) = last_error {
                    if shared.is_current(generation) {
                        shared
                            .logger
                            .info(format!("发现连接 {label} 失败：{error}"));
                    }
                    backoff = (backoff * 2).min(DIAL_RETRY_MAX);
                } else {
                    backoff = DIAL_RETRY_MIN;
                }
                sleep_until(&shared, generation, &thread_stop, backoff);
            }
        });
        Self { stop, addresses }
    }

    fn update(&self, service: Discovered) {
        *self.addresses.lock().unwrap() = service.addresses;
    }

    fn stop(&self) {
        self.stop.store(true, Ordering::SeqCst);
    }
}

fn discover_connect(shared: &Arc<Shared>, generation: u64, address: &str, port: u16) -> Result<()> {
    let channel = Channel::connect(address, port)?;
    channel.send_hello(&shared.hello(&shared.node_id))?;
    let hello = channel.read_hello(shared.config.max_frame())?;
    channel.after_handshake()?;
    if hello.id == shared.node_id {
        return Ok(());
    }
    serve_channel(shared.clone(), generation, channel, shared.node_id.clone())
}

fn start_discovery(shared: Arc<Shared>, generation: u64, own_name: String) -> impl FnOnce() {
    move || {
        let mut browser = match Browser::start() {
            Ok(browser) => browser,
            Err(error) => {
                if shared.is_current(generation) {
                    shared.logger.warn(format!("mDNS 浏览不可用：{error}"));
                }
                return;
            }
        };
        let mut dialers = std::collections::HashMap::<String, DiscoveredDialer>::new();
        let own_fullname = format!("{own_name}.{}", crate::mdns::SERVICE_TYPE);
        while shared.is_current(generation) {
            let found = browser.poll(MDNS_FORGET);
            let mut active = HashSet::new();
            for service in found {
                if service.name == own_fullname {
                    continue;
                }
                let identity = service.identity();
                active.insert(identity.clone());
                if service.node_id.as_deref() == Some(shared.node_id.as_str()) {
                    continue;
                }
                if let Some(dialer) = dialers.get_mut(&identity) {
                    dialer.update(service);
                } else {
                    dialers.insert(
                        identity,
                        DiscoveredDialer::start(shared.clone(), generation, service),
                    );
                }
            }
            dialers.retain(|identity, dialer| {
                if active.contains(identity) {
                    true
                } else {
                    dialer.stop();
                    false
                }
            });
            sleep_until(&shared, generation, &AtomicBool::new(false), MDNS_REFRESH);
        }
        for dialer in dialers.values() {
            dialer.stop();
        }
    }
}

fn start_beacon_discovery(shared: Arc<Shared>, generation: u64) -> impl FnOnce() {
    move || {
        let _advertiser = match beacon::Advertiser::start(
            shared.node_id.clone(),
            shared.device.clone(),
            shared.config.port,
        ) {
            Ok(advertiser) => advertiser,
            Err(error) => {
                if shared.is_current(generation) {
                    shared
                        .logger
                        .warn(format!("UDP beacon advertise unavailable: {error}"));
                }
                return;
            }
        };
        let browser = match beacon::Browser::start() {
            Ok(browser) => browser,
            Err(error) => {
                if shared.is_current(generation) {
                    shared
                        .logger
                        .warn(format!("UDP beacon browse unavailable: {error}"));
                }
                return;
            }
        };
        if shared.is_current(generation) {
            shared
                .logger
                .info(format!("UDP beacon 端口 {}", beacon::BEACON_PORT));
        }
        let mut dialers = std::collections::HashMap::<String, DiscoveredDialer>::new();
        while shared.is_current(generation) {
            let mut active = HashSet::new();
            for peer in browser.poll() {
                if peer.node_id == shared.node_id {
                    continue;
                }
                let identity = peer.identity();
                active.insert(identity.clone());
                let service = Discovered {
                    name: peer.device.clone(),
                    port: peer.port,
                    addresses: peer.addresses,
                    node_id: Some(peer.node_id),
                    last_seen: peer.last_seen,
                };
                if let Some(dialer) = dialers.get_mut(&identity) {
                    dialer.update(service);
                } else {
                    dialers.insert(
                        identity,
                        DiscoveredDialer::start(shared.clone(), generation, service),
                    );
                }
            }
            dialers.retain(|identity, dialer| {
                if active.contains(identity) {
                    true
                } else {
                    dialer.stop();
                    false
                }
            });
            sleep_until(&shared, generation, &AtomicBool::new(false), MDNS_REFRESH);
        }
        for dialer in dialers.values() {
            dialer.stop();
        }
    }
}
fn start_ping_loop(shared: Arc<Shared>, generation: u64) -> impl FnOnce() {
    move || {
        while shared.is_current(generation) {
            sleep_until(&shared, generation, &AtomicBool::new(false), PING_INTERVAL);
            if !shared.is_current(generation) {
                return;
            }
            for link in shared.links() {
                if let Err(error) = link.channel.send(T_PING, &[]) {
                    shared
                        .logger
                        .warn(format!("PING {} 失败：{error}", link_name(&link)));
                    link.shutdown();
                }
            }
        }
    }
}

fn serve_channel(
    shared: Arc<Shared>,
    generation: u64,
    channel: Channel,
    connection_initiator: String,
) -> Result<()> {
    if !shared.is_current(generation) {
        channel.shutdown();
        return Ok(());
    }
    let link = Arc::new(Link {
        channel,
        closed: AtomicBool::new(false),
        generation,
        initiator: connection_initiator,
    });
    if let Err(reason) = shared.register_link(generation, link.clone()) {
        link.send_bye(&reason);
        link.shutdown();
        return Ok(());
    }
    shared.logger.info(format!(
        "已连接：{}（当前 {} 台设备）",
        link_name(&link),
        shared.connected_count()
    ));

    loop {
        if !shared.is_current(generation) || link.closed.load(Ordering::SeqCst) {
            break;
        }
        let (kind, payload) = match link.channel.recv(shared.config.max_frame()) {
            Ok(frame) => frame,
            Err(error) => {
                if shared.is_current(generation) {
                    if error
                        .downcast_ref::<std::io::Error>()
                        .is_some_and(|io| io.kind() == ErrorKind::TimedOut)
                    {
                        shared.logger.info(format!("{} 空闲超时", link_name(&link)));
                    } else {
                        shared
                            .logger
                            .info(format!("{} 断开：{error}", link_name(&link)));
                    }
                }
                break;
            }
        };
        match kind {
            T_HELLO => shared
                .logger
                .warn(format!("{} 重复发送 HELLO", link_name(&link))),
            T_CLIP => shared.handle_remote_text(&link, &payload),
            T_PING => {
                if let Err(error) = link.channel.send(T_PONG, &[]) {
                    shared.logger.warn(format!("发送 PONG 失败：{error}"));
                    break;
                }
            }
            T_PONG => {}
            T_BYE => {
                let reason = serde_json::from_slice::<serde_json::Value>(&payload)
                    .ok()
                    .and_then(|value| {
                        value
                            .get("reason")
                            .and_then(|reason| reason.as_str())
                            .map(str::to_string)
                    })
                    .unwrap_or_else(|| "未说明原因".to_string());
                shared
                    .logger
                    .info(format!("{} 说再见：{reason}", link_name(&link)));
                break;
            }
            other => shared
                .logger
                .warn(format!("{} 发来未支持的帧类型 {other}", link_name(&link))),
        }
    }
    link.shutdown();
    shared.unregister_link(&link);
    Ok(())
}

fn sleep_until(shared: &Shared, generation: u64, stop: &AtomicBool, duration: Duration) {
    let step = Duration::from_millis(200);
    let mut slept = Duration::ZERO;
    while slept < duration && shared.is_current(generation) && !stop.load(Ordering::SeqCst) {
        let part = duration.saturating_sub(slept).min(step);
        thread::sleep(part);
        slept += part;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn duplicate_prefers_smaller_initiator() {
        assert!(should_replace_ids("ffffffff", "00000000"));
        assert!(!should_replace_ids("00000000", "ffffffff"));
    }

    #[test]
    fn same_initiator_prefers_earlier_link() {
        assert!(!should_replace_ids("same", "same"));
    }
}
