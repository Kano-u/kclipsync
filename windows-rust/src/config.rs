use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize};

pub const DEFAULT_PORT: u16 = 47631;
const MIN_TEXT_BYTES: usize = 1024;
const MAX_TEXT_BYTES: usize = 64 * 1024 * 1024;
const MIN_LOG_BYTES: u64 = 64 * 1024;
const MAX_LOG_BYTES: u64 = 1024 * 1024 * 1024;

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    #[serde(default = "default_port")]
    pub port: u16,
    #[serde(default)]
    pub device_name: String,
    #[serde(default = "default_max_bytes")]
    pub max_bytes: usize,
    #[serde(default = "default_log_max_bytes")]
    pub log_max_bytes: u64,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            port: DEFAULT_PORT,
            device_name: String::new(),
            max_bytes: default_max_bytes(),
            log_max_bytes: default_log_max_bytes(),
        }
    }
}

impl Config {
    pub fn load(path: &Path) -> Result<Self> {
        let raw =
            fs::read_to_string(path).with_context(|| format!("无法读取配置 {}", path.display()))?;
        let config: Config =
            toml::from_str(&raw).with_context(|| format!("配置格式错误：{}", path.display()))?;
        config.validate()?;
        Ok(config)
    }

    pub fn write_new(path: &Path) -> Result<Self> {
        if path.exists() {
            return Err(anyhow!("{} 已存在，拒绝覆盖", path.display()));
        }
        let config = Config {
            device_name: default_device_name(),
            ..Config::default()
        };
        let text = toml::to_string_pretty(&config)
            .with_context(|| format!("无法生成配置 {}", path.display()))?;
        fs::write(path, format!("{text}\n"))
            .with_context(|| format!("无法写入配置 {}", path.display()))?;
        Ok(config)
    }

    pub fn validate(&self) -> Result<()> {
        if self.port == 0 {
            return Err(anyhow!("port 必须在 1-65535 之间"));
        }
        if !(MIN_TEXT_BYTES..=MAX_TEXT_BYTES).contains(&self.max_bytes) {
            return Err(anyhow!("max_bytes 必须在 1024-67108864 之间"));
        }
        if !(MIN_LOG_BYTES..=MAX_LOG_BYTES).contains(&self.log_max_bytes) {
            return Err(anyhow!("log_max_bytes 必须在 65536-1073741824 之间"));
        }
        if self.device_name.chars().count() > 63 {
            return Err(anyhow!("device_name 最多 63 个字符"));
        }
        Ok(())
    }

    pub fn resolved_device_name(&self) -> String {
        if self.device_name.trim().is_empty() {
            default_device_name()
        } else {
            self.device_name.trim().to_string()
        }
    }

    pub fn max_frame(&self) -> usize {
        // JSON escaping can roughly double the UTF-8 text, plus frame and object overhead.
        self.max_bytes.saturating_mul(3).max(64 * 1024)
    }
}

pub fn default_config_path() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(Path::to_path_buf))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("config.toml")
}

fn default_port() -> u16 {
    DEFAULT_PORT
}

fn default_max_bytes() -> usize {
    1024 * 1024
}

fn default_log_max_bytes() -> u64 {
    1024 * 1024
}

pub fn default_device_name() -> String {
    hostname::get()
        .ok()
        .and_then(|name| name.into_string().ok())
        .map(|name| name.split('.').next().unwrap_or("windows-pc").to_string())
        .filter(|name| !name.trim().is_empty())
        .unwrap_or_else(|| "windows-pc".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn defaults_are_valid() {
        assert!(Config::default().validate().is_ok());
    }

    #[test]
    fn rejects_unknown_fields() {
        let raw = "port = 47631\nextra = true\n";
        assert!(toml::from_str::<Config>(raw).is_err());
    }

    #[test]
    fn rejects_invalid_port() {
        let config = Config {
            port: 0,
            ..Config::default()
        };
        assert!(config.validate().is_err());
    }

    #[test]
    fn write_new_refuses_overwrite() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("config.toml");
        Config::write_new(&path).unwrap();
        assert!(Config::write_new(&path).is_err());
    }

    #[test]
    fn max_frame_covers_json_escaping() {
        let config = Config::default();
        assert!(config.max_frame() >= config.max_bytes * 2);
    }
}
