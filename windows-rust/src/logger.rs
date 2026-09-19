use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use anyhow::{Context, Result};

pub struct Logger {
    path: PathBuf,
    file: Mutex<File>,
    max_bytes: u64,
}

impl Logger {
    pub fn open(path: &Path, max_bytes: u64) -> Result<Self> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .with_context(|| format!("无法创建日志目录 {}", parent.display()))?;
        }
        let file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(path)
            .with_context(|| format!("无法打开日志 {}", path.display()))?;
        let mut logger = Self {
            path: path.to_path_buf(),
            file: Mutex::new(file),
            max_bytes,
        };
        logger.rotate_if_needed()?;
        Ok(logger)
    }

    pub fn info(&self, message: impl AsRef<str>) {
        self.write("INFO", message.as_ref());
    }

    pub fn warn(&self, message: impl AsRef<str>) {
        self.write("WARN", message.as_ref());
    }

    fn write(&self, level: &str, message: &str) {
        let line = format!(
            "{} [{}] {}\n",
            chrono_like_now(),
            level,
            message.replace('\n', " ")
        );
        let mut file = self.file.lock().unwrap();
        if let Err(error) = file.write_all(line.as_bytes()).and_then(|_| file.flush()) {
            eprintln!("写入日志失败：{error}");
        }
        let _ = self.try_rotate(&mut file);
    }

    fn rotate_if_needed(&mut self) -> Result<()> {
        let size = {
            let file = self.file.lock().unwrap();
            file.metadata()?.len()
        };
        if size > self.max_bytes {
            let mut file = self.file.lock().unwrap();
            self.try_rotate(&mut file)?;
        }
        Ok(())
    }

    fn try_rotate(&self, file: &mut File) -> io::Result<()> {
        let size = file.metadata()?.len();
        if size <= self.max_bytes {
            return Ok(());
        }
        file.sync_all()?;
        let old = self.path.with_extension("log.old");
        let _ = std::fs::remove_file(&old);
        std::fs::rename(&self.path, &old)?;
        *file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.path)?;
        Ok(())
    }
}

fn chrono_like_now() -> String {
    // Avoid a datetime dependency: RFC3339 seconds are enough for event ordering in this app.
    let seconds = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|value| value.as_secs())
        .unwrap_or_default();
    let days = seconds / 86_400;
    let time = seconds % 86_400;
    let (year, month, day) = civil_from_days(days as i64);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z",
        time / 3600,
        (time % 3600) / 60,
        time % 60
    )
}

fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe + era * 400;
    let day_of_year = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * day_of_year + 2) / 153;
    let day = (day_of_year - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if month <= 2 { year + 1 } else { year }, month, day)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn rotates_when_large() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("kclipsync.log");
        let logger = Logger::open(&path, 10).unwrap();
        logger.info("short");
        assert!(path.with_extension("log.old").exists());
    }
}
