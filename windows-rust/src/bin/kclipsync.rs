use std::path::PathBuf;

use anyhow::{Context, Result};
use clap::{Parser, Subcommand};

use kclipsync::config::{Config, default_config_path};
use kclipsync::service::run_service;

#[derive(Debug, Parser)]
#[command(name = "kclipsync", version, about = "极简局域网文本剪贴板同步")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Subcommand)]
enum Command {
    /// 创建默认 config.toml，不覆盖已有文件
    Init {
        #[arg(long, global = true)]
        config: Option<PathBuf>,
    },
    /// 检查配置
    Check {
        #[arg(long, global = true)]
        config: Option<PathBuf>,
    },
    /// 前台运行
    Run {
        #[arg(long, global = true)]
        config: Option<PathBuf>,
    },
}

fn main() {
    let cli = Cli::parse();
    if let Err(error) = run(cli) {
        eprintln!("错误：{error:#}");
        std::process::exit(1);
    }
}

fn run(cli: Cli) -> Result<()> {
    let command = cli.command;
    match command {
        Command::Init { config } => {
            let path = config.unwrap_or_else(default_config_path);
            let result = Config::write_new(&path);
            match result {
                Ok(_) => {
                    println!("已创建 {}", path.display());
                    Ok(())
                }
                Err(error) => Err(error),
            }
        }
        Command::Check { config } => {
            let path = config.unwrap_or_else(default_config_path);
            let config = load_config(&path)?;
            println!("配置有效：{}", path.display());
            println!("设备名：{}", config.resolved_device_name());
            println!("端口：{}", config.port);
            println!("最大文本：{} 字节", config.max_bytes);
            Ok(())
        }
        Command::Run { config } => {
            let path = config.unwrap_or_else(default_config_path);
            let config = load_config(&path)?;
            let log_path = program_dir()?.join("kclipsync.log");
            println!(
                "KClipSync 正在运行。按 Ctrl+C 退出。日志：{}",
                log_path.display()
            );
            run_service(config, &log_path)
        }
    }
}

fn load_config(path: &std::path::Path) -> Result<Config> {
    Config::load(path)
        .with_context(|| format!("请先运行 kclipsync init --config {}", path.display()))
}

fn program_dir() -> Result<PathBuf> {
    std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(PathBuf::from))
        .context("无法确定程序目录")
}
