use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::{Mutex, RwLock};
use std::time::Duration;

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const PROTOCOL_VERSION: u64 = 1;
pub const READ_TIMEOUT: Duration = Duration::from_secs(90);
pub const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

pub const T_HELLO: u8 = 1;
pub const T_CLIP: u8 = 2;
pub const T_PING: u8 = 3;
pub const T_PONG: u8 = 4;
pub const T_BYE: u8 = 15;

pub const FRAME_OVERHEAD: usize = 5;

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Hello {
    pub v: u64,
    pub id: String,
    pub device: String,
    pub port: u16,
    /// The node id of the endpoint that opened this TCP connection. Both sides use it to decide
    /// which of two parallel connections should survive.
    pub initiator: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Clip {
    pub seq: u64,
    pub mime: String,
    pub sha256: String,
    pub data: String,
}

#[derive(Debug, Deserialize, Serialize)]
pub struct ByeMessage {
    pub reason: String,
}

pub struct Channel {
    reader: Mutex<TcpStream>,
    writer: Mutex<TcpStream>,
    remote: SocketAddr,
    device: RwLock<String>,
    node_id: RwLock<String>,
    pub is_initiator: bool,
}

impl Channel {
    pub fn connect(address: &str, port: u16) -> Result<Self> {
        let stream = connect_tcp(address, port)?;
        let channel = Self::from_stream(stream, true)?;
        channel
            .reader
            .lock()
            .unwrap()
            .set_read_timeout(Some(HANDSHAKE_TIMEOUT))?;
        Ok(channel)
    }

    pub fn accept(stream: TcpStream) -> Result<Self> {
        stream.set_nonblocking(false)?;
        let channel = Self::from_stream(stream, false)?;
        channel
            .reader
            .lock()
            .unwrap()
            .set_read_timeout(Some(HANDSHAKE_TIMEOUT))?;
        Ok(channel)
    }

    fn from_stream(stream: TcpStream, is_initiator: bool) -> Result<Self> {
        stream.set_nodelay(true)?;
        let remote = stream.peer_addr()?;
        Ok(Self {
            reader: Mutex::new(stream.try_clone()?),
            writer: Mutex::new(stream),
            remote,
            device: RwLock::new(String::new()),
            node_id: RwLock::new(String::new()),
            is_initiator,
        })
    }

    pub fn after_handshake(&self) -> Result<()> {
        self.reader
            .lock()
            .unwrap()
            .set_read_timeout(Some(READ_TIMEOUT))?;
        Ok(())
    }

    pub fn send(&self, kind: u8, payload: &[u8]) -> Result<()> {
        let length =
            u32::try_from(payload.len() + 1).map_err(|_| anyhow::anyhow!("帧长度超过 u32"))?;
        let mut frame = Vec::with_capacity(payload.len() + FRAME_OVERHEAD);
        frame.extend_from_slice(&length.to_be_bytes());
        frame.push(kind);
        frame.extend_from_slice(payload);
        let mut writer = self.writer.lock().unwrap();
        writer.write_all(&frame)?;
        writer.flush()?;
        Ok(())
    }

    pub fn recv(&self, max_frame: usize) -> Result<(u8, Vec<u8>)> {
        let mut reader = self.reader.lock().unwrap();
        let mut length_bytes = [0u8; 4];
        reader.read_exact(&mut length_bytes)?;
        let length = u32::from_be_bytes(length_bytes) as usize;
        if length == 0 || length > max_frame {
            bail!("帧长度无效：{length}");
        }
        let mut frame = vec![0u8; length];
        reader.read_exact(&mut frame)?;
        let kind = frame.remove(0);
        Ok((kind, frame))
    }

    pub fn send_json<T: Serialize>(&self, kind: u8, value: &T) -> Result<()> {
        self.send(kind, &serde_json::to_vec(value)?)
    }

    pub fn send_hello(&self, hello: &Hello) -> Result<()> {
        self.send_json(T_HELLO, hello)
    }

    pub fn read_hello(&self, max_frame: usize) -> Result<Hello> {
        let (kind, payload) = self.recv(max_frame)?;
        if kind != T_HELLO {
            bail!("期望 HELLO，收到类型 {kind}");
        }
        let hello: Hello = serde_json::from_slice(&payload).context("解析 HELLO 失败")?;
        if hello.v != PROTOCOL_VERSION {
            bail!(
                "协议版本不匹配：对端 {}，本端 {}",
                hello.v,
                PROTOCOL_VERSION
            );
        }
        if hello.id.is_empty() || hello.initiator.is_empty() {
            bail!("HELLO 缺少节点 ID");
        }
        *self.device.write().unwrap() = hello.device.clone();
        *self.node_id.write().unwrap() = hello.id.clone();
        Ok(hello)
    }

    pub fn send_clip(&self, seq: u64, text: &str) -> Result<()> {
        self.send_json(
            T_CLIP,
            &Clip {
                seq,
                mime: "text/plain".to_string(),
                sha256: sha256_hex(text.as_bytes()),
                data: text.to_string(),
            },
        )
    }

    pub fn send_bye(&self, reason: &str) {
        let _ = self.send_json(
            T_BYE,
            &ByeMessage {
                reason: reason.to_string(),
            },
        );
    }

    pub fn shutdown(&self) {
        let _ = self
            .writer
            .lock()
            .unwrap()
            .shutdown(std::net::Shutdown::Both);
    }

    pub fn remote(&self) -> SocketAddr {
        self.remote
    }

    pub fn device(&self) -> String {
        self.device.read().unwrap().clone()
    }

    pub fn node_id(&self) -> String {
        self.node_id.read().unwrap().clone()
    }
}

pub fn parse_clip(payload: &[u8], max_bytes: usize) -> Result<Clip> {
    let clip: Clip = serde_json::from_slice(payload).context("解析 CLIP 失败")?;
    if clip.mime != "text/plain" {
        bail!("仅支持 text/plain");
    }
    if clip.data.len() > max_bytes {
        bail!("文本超过 max_bytes");
    }
    let actual = sha256_hex(clip.data.as_bytes());
    if actual != clip.sha256 {
        bail!("CLIP 哈希不匹配");
    }
    Ok(clip)
}

pub fn sha256_hex(data: &[u8]) -> String {
    hex::encode(Sha256::digest(data))
}

pub fn normalize_text(text: &str) -> String {
    text.replace("\r\n", "\n").replace('\r', "\n")
}

fn connect_tcp(address: &str, port: u16) -> Result<TcpStream> {
    let addresses = (address, port)
        .to_socket_addrs()
        .with_context(|| format!("解析地址失败 {address}:{port}"))?;
    let mut last_error = None;
    for remote in addresses {
        match TcpStream::connect_timeout(&remote, Duration::from_secs(10)) {
            Ok(stream) => return Ok(stream),
            Err(error) => last_error = Some(error),
        }
    }
    match last_error {
        Some(error) => Err(error).with_context(|| format!("连接失败 {address}:{port}")),
        None => bail!("{address}:{port} 没有可用地址"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frame_round_trip() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let client = Channel::connect(&address.ip().to_string(), address.port()).unwrap();
        let server = Channel::accept(listener.accept().unwrap().0).unwrap();
        client.send(T_PING, b"hello").unwrap();
        assert_eq!(server.recv(1024).unwrap(), (T_PING, b"hello".to_vec()));
    }

    #[test]
    fn hello_round_trip() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let client = Channel::connect(&address.ip().to_string(), address.port()).unwrap();
        let server = Channel::accept(listener.accept().unwrap().0).unwrap();
        let hello = Hello {
            v: PROTOCOL_VERSION,
            id: "client".to_string(),
            device: "Client".to_string(),
            port: address.port(),
            initiator: "client".to_string(),
        };
        client.send_hello(&hello).unwrap();
        let received = server.read_hello(1024).unwrap();
        assert_eq!(received.id, "client");
        assert_eq!(server.device(), "Client");
    }

    #[test]
    fn normalizes_line_endings() {
        assert_eq!(normalize_text("a\r\nb\rc\nd"), "a\nb\nc\nd");
    }

    #[test]
    fn clip_hash_is_verified() {
        let text = "hello";
        let clip = Clip {
            seq: 1,
            mime: "text/plain".to_string(),
            sha256: sha256_hex(text.as_bytes()),
            data: text.to_string(),
        };
        let payload = serde_json::to_vec(&clip).unwrap();
        assert_eq!(parse_clip(&payload, 1024).unwrap().data, text);
    }
}
