#[cfg(windows)]
mod platform {
    use std::sync::mpsc;

    use anyhow::{Context, Result, bail};
    use windows::Win32::Foundation::{
        ERROR_CLASS_ALREADY_EXISTS, GetLastError, GlobalFree, HANDLE, HGLOBAL, HWND, LPARAM,
        LRESULT, SetLastError, WPARAM,
    };
    use windows::Win32::System::DataExchange::{
        AddClipboardFormatListener, CloseClipboard, EmptyClipboard, GetClipboardData,
        IsClipboardFormatAvailable, OpenClipboard, RemoveClipboardFormatListener, SetClipboardData,
    };
    use windows::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows::Win32::System::Memory::{
        GMEM_MOVEABLE, GlobalAlloc, GlobalLock, GlobalSize, GlobalUnlock,
    };
    use windows::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DefWindowProcW, DestroyWindow, DispatchMessageW, GWLP_USERDATA,
        GetMessageW, GetWindowLongPtrW, HMENU, HWND_MESSAGE, MSG, PostMessageW, PostQuitMessage,
        RegisterClassW, SetWindowLongPtrW, TranslateMessage, WM_CLIPBOARDUPDATE, WM_CLOSE,
        WM_CREATE, WM_DESTROY, WNDCLASSW, WS_OVERLAPPED,
    };
    use windows::core::{Error as WindowsError, w};

    const WM_APP_SET_ACTIVE: u32 = 0x8000 + 1;
    const WM_APP_SET_TEXT: u32 = 0x8000 + 2;
    const CF_UNICODETEXT: u32 = 13;

    pub enum Event {
        Text(String),
        Error(String),
        Stopped,
    }

    struct State {
        events: mpsc::Sender<Event>,
        active: bool,
    }

    #[derive(Clone, Copy)]
    pub struct Sender {
        hwnd: HWND,
    }

    unsafe impl Send for Sender {}
    unsafe impl Sync for Sender {}

    impl Sender {
        pub fn set_active(&self, active: bool) {
            unsafe {
                let _ = PostMessageW(
                    Some(self.hwnd),
                    WM_APP_SET_ACTIVE,
                    WPARAM(usize::from(active)),
                    LPARAM(0),
                );
            }
        }

        pub fn set_text(&self, text: String) -> Result<()> {
            let raw = Box::into_raw(Box::new(text));
            let result = unsafe {
                PostMessageW(
                    Some(self.hwnd),
                    WM_APP_SET_TEXT,
                    WPARAM(0),
                    LPARAM(raw as isize),
                )
            };
            if result.is_err() {
                unsafe {
                    drop(Box::from_raw(raw));
                }
                let _ = result.context("发送剪贴板写入消息失败");
            }
            Ok(())
        }

        pub fn stop(&self) {
            unsafe {
                let _ = PostMessageW(Some(self.hwnd), WM_CLOSE, WPARAM(0), LPARAM(0));
            }
        }
    }

    pub fn start(events: mpsc::Sender<Event>) -> Result<Sender> {
        let instance = unsafe { GetModuleHandleW(None)? };
        let class_name = w!("KClipSyncClipboardWindow");
        let state = Box::into_raw(Box::new(State {
            events,
            active: false,
        }));
        let class = WNDCLASSW {
            lpfnWndProc: Some(window_proc),
            hInstance: instance.into(),
            lpszClassName: class_name,
            ..Default::default()
        };
        unsafe {
            SetLastError(ERROR_CLASS_ALREADY_EXISTS);
            if RegisterClassW(&class) == 0 {
                let error = GetLastError();
                if error != ERROR_CLASS_ALREADY_EXISTS {
                    drop(Box::from_raw(state));
                    return Err(WindowsError::from(error)).context("注册剪贴板窗口失败");
                }
            }
            let hwnd = match CreateWindowExW(
                Default::default(),
                class_name,
                w!(""),
                WS_OVERLAPPED,
                0,
                0,
                0,
                0,
                Some(HWND_MESSAGE),
                Some(HMENU::default()),
                Some(instance.into()),
                Some(state.cast()),
            ) {
                Ok(hwnd) => hwnd,
                Err(error) => {
                    drop(Box::from_raw(state));
                    return Err(error).context("创建剪贴板窗口失败");
                }
            };
            Ok(Sender { hwnd })
        }
    }

    pub fn run_message_loop(sender: &Sender) -> Result<()> {
        let mut message = MSG::default();
        loop {
            let result = unsafe { GetMessageW(&mut message, Some(sender.hwnd), 0, 0) };
            if result.0 == -1 {
                return Err(windows::core::Error::from_thread()).context("剪贴板消息循环失败");
            }
            if result.0 == 0 {
                return Ok(());
            }
            unsafe {
                let _ = TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
    }

    unsafe extern "system" fn window_proc(
        hwnd: HWND,
        message: u32,
        wparam: WPARAM,
        lparam: LPARAM,
    ) -> LRESULT {
        let pointer = unsafe { GetWindowLongPtrW(hwnd, GWLP_USERDATA) } as *mut State;
        if message == WM_CREATE {
            let create = unsafe {
                &*(lparam.0 as *const windows::Win32::UI::WindowsAndMessaging::CREATESTRUCTW)
            };
            unsafe {
                SetWindowLongPtrW(hwnd, GWLP_USERDATA, create.lpCreateParams as isize);
            }
            return LRESULT(0);
        }
        if pointer.is_null() {
            return unsafe { DefWindowProcW(hwnd, message, wparam, lparam) };
        }
        let state = unsafe { &mut *pointer };
        match message {
            WM_APP_SET_ACTIVE => {
                let active = wparam.0 != 0;
                if active != state.active {
                    let result = if active {
                        unsafe { AddClipboardFormatListener(hwnd) }
                    } else {
                        unsafe { RemoveClipboardFormatListener(hwnd) }
                    };
                    if let Err(error) = result {
                        let _ = state.events.send(Event::Error(format!(
                            "{} 剪贴板监听失败：{error}",
                            if active { "启用" } else { "停用" }
                        )));
                    } else {
                        state.active = active;
                    }
                }
                LRESULT(0)
            }
            WM_CLIPBOARDUPDATE => {
                if !state.active {
                    return LRESULT(0);
                }
                match read_text() {
                    Ok(Some(text)) => {
                        let _ = state.events.send(Event::Text(text));
                    }
                    Ok(None) => {}
                    Err(error) => {
                        let _ = state
                            .events
                            .send(Event::Error(format!("读取剪贴板失败：{error}")));
                    }
                }
                LRESULT(0)
            }
            WM_APP_SET_TEXT => {
                let text = unsafe { Box::from_raw(lparam.0 as *mut String) };
                if let Err(error) = set_text(&text) {
                    let _ = state
                        .events
                        .send(Event::Error(format!("写入剪贴板失败：{error}")));
                }
                LRESULT(0)
            }
            WM_CLOSE => {
                let _ = state.events.send(Event::Stopped);
                unsafe {
                    let _ = RemoveClipboardFormatListener(hwnd);
                    let _ = DestroyWindow(hwnd);
                    PostQuitMessage(0);
                }
                LRESULT(0)
            }
            WM_DESTROY => {
                unsafe {
                    SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
                    drop(Box::from_raw(pointer));
                }
                LRESULT(0)
            }
            _ => unsafe { DefWindowProcW(hwnd, message, wparam, lparam) },
        }
    }

    fn read_text() -> Result<Option<String>> {
        if !open_clipboard() {
            bail!("剪贴板被占用");
        }
        let result = (|| {
            if unsafe { IsClipboardFormatAvailable(CF_UNICODETEXT) }.is_err() {
                return Ok(None);
            }
            let handle = unsafe { GetClipboardData(CF_UNICODETEXT)? };
            let global = HGLOBAL(handle.0);
            let pointer = unsafe { GlobalLock(global) };
            if pointer.is_null() {
                return Err(windows::core::Error::from_thread()).context("锁定剪贴板文本失败");
            }
            let size = unsafe { GlobalSize(global) };
            let words = unsafe { std::slice::from_raw_parts(pointer.cast::<u16>(), size / 2) };
            let end = words
                .iter()
                .position(|word| *word == 0)
                .unwrap_or(words.len());
            let text = String::from_utf16(&words[..end]).context("解码剪贴板文本失败")?;
            let _ = unsafe { GlobalUnlock(global) };
            Ok(Some(text.replace("\r\n", "\n").replace('\r', "\n")))
        })();
        unsafe {
            let _ = CloseClipboard();
        }
        result
    }

    fn set_text(text: &str) -> Result<()> {
        let normalized = text.replace("\r\n", "\n").replace('\r', "\n");
        let normalized = normalized.replace('\n', "\r\n");
        let mut utf16: Vec<u16> = normalized.encode_utf16().collect();
        utf16.push(0);
        let bytes =
            unsafe { std::slice::from_raw_parts(utf16.as_ptr().cast::<u8>(), utf16.len() * 2) };
        if !open_clipboard() {
            bail!("剪贴板被占用");
        }
        let result = (|| {
            unsafe {
                EmptyClipboard()?;
            }
            let memory = unsafe { GlobalAlloc(GMEM_MOVEABLE, bytes.len())? };
            let pointer = unsafe { GlobalLock(memory) };
            if pointer.is_null() {
                let _ = unsafe { GlobalFree(Some(memory)) };
                return Err(windows::core::Error::from_thread()).context("锁定剪贴板内存失败");
            }
            unsafe {
                std::ptr::copy_nonoverlapping(bytes.as_ptr(), pointer.cast::<u8>(), bytes.len());
                let _ = GlobalUnlock(memory);
            }
            if let Err(error) = unsafe { SetClipboardData(CF_UNICODETEXT, Some(HANDLE(memory.0))) }
            {
                let _ = unsafe { GlobalFree(Some(memory)) };
                return Err(error).context("写入剪贴板文本失败");
            }
            Ok(())
        })();
        unsafe {
            let _ = CloseClipboard();
        }
        result
    }

    fn open_clipboard() -> bool {
        for _ in 0..10 {
            if unsafe { OpenClipboard(None) }.is_ok() {
                return true;
            }
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
        false
    }
}

#[cfg(windows)]
pub use platform::{Event, Sender, run_message_loop, start};

#[cfg(not(windows))]
mod platform {
    use std::sync::mpsc;

    use anyhow::{Result, bail};

    pub enum Event {
        Text(String),
        Error(String),
        Stopped,
    }

    #[derive(Clone, Copy, Default)]
    pub struct Sender;

    impl Sender {
        pub fn set_active(&self, _active: bool) {}
        pub fn set_text(&self, _text: String) -> Result<()> {
            bail!("当前平台不支持系统剪贴板")
        }
        pub fn stop(&self) {}
    }

    pub fn start(_events: mpsc::Sender<Event>) -> Result<Sender> {
        Ok(Sender)
    }

    pub fn run_message_loop(_sender: &Sender) -> Result<()> {
        bail!("当前平台不支持系统剪贴板")
    }
}

#[cfg(not(windows))]
pub use platform::{Event, Sender, run_message_loop, start};
