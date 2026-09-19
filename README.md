# KClipSync

极简的局域网纯文本剪贴板同步工具。Windows 电脑在终端运行，Android 15 手机使用
Material 3 应用。设备处于同一可信局域网时通过 mDNS 自动发现并连接，不依赖账号或云服务。

## 项目结构

- `windows-rust/`：Windows Rust 终端实现。
- `android/`：Android 15 应用，包含 system_server 后台剪贴板 hook。
- `.github/workflows/android.yml`：在 GitHub Actions 上构建 debug APK。

## Windows 快速开始

```powershell
cd windows-rust
cargo build --release --offline
..\target\release\kclipsync.exe init
..\target\release\kclipsync.exe check
..\target\release\kclipsync.exe run
```

程序目录只使用 `config.toml`，默认端口 `47631`，默认最大文本 1 MiB。首次监听时如果
Windows 弹出防火墙提示，请只允许专用网络。程序不安装服务、不写注册表、不注册开机启动。

除了 mDNS，程序还会在每个可用网卡上使用 UDP `47632` 发送发现广播。手机开启热点、电脑接
入热点这类“热点网卡不是系统默认路由”的场景，同样能发现对端。若防火墙没有一次性放行，请
同时允许专用网络上的 TCP `47631` 与 UDP `47632`。

配置示例：

```toml
port = 47631
device_name = "My PC"
max_bytes = 1048576
log_max_bytes = 1048576
```

## Android 安装

1. 从 GitHub Actions 的 `kclipsync-debug-apk` 产物下载 `kclipsync-debug.apk`。
   也可以直接下载最近一次公开调试包：
   <https://github.com/Kano-u/kclipsync/releases/download/android-debug/kclipsync-debug.apk>
2. 安装到两台 Android 15 手机。
3. 在 LSPosed 中启用 **KClipSync**，勾选 `system_server`，然后重启手机。
4. 打开应用，确认端口为 `47631`，点击“启动”。
5. 应用启动后，system_server 模块会直接通过系统服务给 KClipSync 加入 doze 白名单、后台
   appops 和 standby bucket 豁免，不需要应用进程能执行 `su`。修改模块代码后需要重新安装
   APK 并重启一次手机，新的 hook 才会生效。

未启用 LSPosed 时，电脑到手机仍能同步；Android 在后台复制文本到电脑可能失败，因为系统
默认限制后台读取剪贴板。

## 网络行为

- 服务类型：`_kclipsync._tcp.local.`，TXT 记录包含 `v=1` 和节点 ID。
- 除 mDNS 外，还会在每个可用网卡上通过 UDP `47632` 广播/监听，支持手机热点。
- TCP 明文传输，帧格式为 `u32` 大端长度 + 1 字节类型 + UTF-8 JSON。
- 支持 HELLO、CLIP、PING、PONG、BYE。
- 文本统一规范化为 LF；Windows 写入剪贴板时转换为 CRLF。
- 每个节点保留最近 256 个文本哈希，抑制回声和多设备转发环路。
- 本地文本发送给所有连接；远端文本写入本机剪贴板并转发给除来源外的连接。

局域网被视为完全可信网络，因此第一版使用明文传输。请不要在不信任的网络上运行。

## 验证

Windows：

```powershell
cargo fmt --all -- --check
cargo check --all-targets --offline
cargo test --all-targets --offline
cargo clippy --all-targets --offline -- -D warnings
cargo build --release --offline
```

Android 在 GitHub Actions 上执行 `gradle assembleDebug --no-daemon`，产物名为
`kclipsync-debug-apk`。
