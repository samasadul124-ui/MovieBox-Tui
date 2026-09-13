//! Mobile (Android APK) backend bridge.
//!
//! This module exposes the *exact same* provider stack the desktop TUI uses
//! ([`crate::service::MovieBoxService`], `providers::*`, `download`,
//! `cache`, …) to a Kotlin host process through JNI. There is intentionally
//! **no business logic here**: every `core_*` function is a thin JSON
//! envelope around an existing backend API, and every `Java_*` export is a
//! thin JNI adapter around a `core_*` function.
//!
//! # Contract (Kotlin side: `com.moviebox.core.NativeBridge`)
//!
//! * All calls exchange UTF-8 JSON strings.
//! * Success envelope: `{"ok": <value>}`. Failure envelope:
//!   `{"error": "<message>"}`. A `null` return means the JNI layer itself
//!   failed (out of memory) — treat as fatal for that call only.
//! * Provider names: `"moviebox" | "fourkhdhub" | "bdix_circleftp" |
//!   "bdix_dhakaflix" | "addons"` (see [`ProviderKind::parse`]).
//! * Search/homepage pages are **1-based**, mirroring the desktop client.
//! * Call every method from a background thread (`Dispatchers.IO`). Calls
//!   block the calling thread until the backend future completes.
//! * Call `init` exactly once at process start with app-private directories.
//!
//! # Panic policy
//!
//! Release builds use `panic = "abort"`, which would kill the host app, so
//! this module contains **no `unwrap` / `expect` / `panic`** by construction:
//! every fallible step maps to an `{"error": …}` envelope instead.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{
    Arc, Mutex, OnceLock,
    atomic::{AtomicBool, AtomicU64, Ordering},
};

use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jboolean, jint, jlong, jstring},
};
use serde::{Deserialize, Serialize};

use crate::providers::models::ProviderKind;
use crate::service::MovieBoxService;

// ---------------------------------------------------------------------------
// Singletons
// ---------------------------------------------------------------------------

static RUNTIME: OnceLock<Option<tokio::runtime::Runtime>> = OnceLock::new();
static SERVICE: OnceLock<MovieBoxService> = OnceLock::new();

fn runtime() -> Option<&'static tokio::runtime::Runtime> {
    RUNTIME
        .get_or_init(|| {
            tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .thread_name("moviebox-mobile")
                .build()
                .ok()
        })
        .as_ref()
}

fn service() -> &'static MovieBoxService {
    SERVICE.get_or_init(MovieBoxService::new)
}

// ---------------------------------------------------------------------------
// JSON envelopes
// ---------------------------------------------------------------------------

fn ok<T: Serialize>(value: &T) -> String {
    serde_json::to_string(&serde_json::json!({"ok": value}))
        .unwrap_or_else(|_| r#"{"ok":null}"#.to_string())
}

fn err(message: impl std::fmt::Display) -> String {
    serde_json::to_string(&serde_json::json!({"error": message.to_string()}))
        .unwrap_or_else(|_| r#"{"error":"unknown"}"#.to_string())
}

fn provider_of(name: &str) -> Result<ProviderKind, String> {
    ProviderKind::parse(name).ok_or_else(|| format!("unknown provider: {name}"))
}

// ---------------------------------------------------------------------------
// Core API (pure Rust <-> JSON; also exercised by host-side tests)
// ---------------------------------------------------------------------------

/// Library identity. Never touches the network or the filesystem.
pub fn core_version() -> String {
    ok(&serde_json::json!({
        "version": env!("CARGO_PKG_VERSION"),
        "flavor": "mobile",
        "api": 1,
    }))
}

#[derive(Debug, Deserialize)]
struct InitSpec {
    config_dir: String,
    data_dir: String,
    cache_dir: String,
}

/// One-time init. `spec_json` =
/// `{"config_dir": "...", "data_dir": "...", "cache_dir": "..."}`.
///
/// Creates the directories, injects them into [`crate::config`], starts
/// logging (logcat on Android, file logging on hosts), and warms the async
/// runtime plus the backend service. First call wins; later calls report
/// `{"ok":{"first_init":false,…}}`.
pub fn core_init(spec_json: &str) -> String {
    let spec: InitSpec = match serde_json::from_str(spec_json) {
        Ok(spec) => spec,
        Err(e) => return err(format!("bad init spec: {e}")),
    };
    for dir in [&spec.config_dir, &spec.data_dir, &spec.cache_dir] {
        if dir.trim().is_empty() {
            return err("init spec contains an empty directory");
        }
        if std::fs::create_dir_all(dir).is_err() {
            return err(format!("cannot create directory: {dir}"));
        }
    }
    let first = crate::config::mobile_init(
        PathBuf::from(spec.config_dir),
        PathBuf::from(spec.data_dir),
        PathBuf::from(spec.cache_dir),
    );
    crate::logging::init_mobile();
    let rt_ok = runtime().is_some();
    let _ = service();
    ok(&serde_json::json!({"first_init": first, "runtime": rt_ok}))
}

/// Search a provider. `page` is 1-based. Returns `{"ok": [CatalogItem…]}`.
pub fn core_search(provider: &str, query: &str, page: usize) -> String {
    let provider = match provider_of(provider) {
        Ok(p) => p,
        Err(e) => return err(e),
    };
    if query.trim().is_empty() {
        return err("empty query");
    }
    let Some(rt) = runtime() else {
        return err("async runtime unavailable");
    };
    match rt.block_on(service().search_typed(provider, query, page.max(1))) {
        Ok(items) => ok(&items),
        Err(e) => err(e.user_message(provider)),
    }
}

/// Full details for one title. Returns `{"ok": MediaDetails}`.
pub fn core_details(provider: &str, subject_id: &str) -> String {
    let provider = match provider_of(provider) {
        Ok(p) => p,
        Err(e) => return err(e),
    };
    if subject_id.trim().is_empty() {
        return err("empty subject id");
    }
    let Some(rt) = runtime() else {
        return err("async runtime unavailable");
    };
    match rt.block_on(service().details_typed(provider, subject_id)) {
        Ok(details) => ok(&details),
        Err(e) => err(e.user_message(provider)),
    }
}

/// Resolvable releases (stream URLs + headers + mirrors) for an episode.
/// Movies use `season = 0, episode = 0`. Returns `{"ok": [Release…]}`.
pub fn core_streams(
    provider: &str,
    subject_id: &str,
    season: usize,
    episode: usize,
    is_series: bool,
) -> String {
    let provider = match provider_of(provider) {
        Ok(p) => p,
        Err(e) => return err(e),
    };
    if subject_id.trim().is_empty() {
        return err("empty subject id");
    }
    let Some(rt) = runtime() else {
        return err("async runtime unavailable");
    };
    match rt.block_on(service().streams_typed(provider, subject_id, season, episode, is_series)) {
        Ok(releases) => ok(&releases),
        Err(e) => err(e.user_message(provider)),
    }
}

/// Subtitle options for an episode. Returns `{"ok": [SubtitleOption…]}`.
pub fn core_subtitles(
    subject_id: &str,
    resource_id: &str,
    season: usize,
    episode: usize,
) -> String {
    if subject_id.trim().is_empty() || resource_id.trim().is_empty() {
        return err("empty subject id or resource id");
    }
    let Some(rt) = runtime() else {
        return err("async runtime unavailable");
    };
    match rt.block_on(service().get_ext_captions(subject_id, resource_id, &[], season, episode)) {
        Ok(options) => ok(&options),
        Err(e) => err(e),
    }
}

/// Homepage rail for a tab. `page` is 1-based.
/// Returns `{"ok": {"items": […], "metrics": {…}}}`.
pub fn core_homepage(tab_id: &str, page: usize) -> String {
    let Some(rt) = runtime() else {
        return err("async runtime unavailable");
    };
    match rt.block_on(service().homepage(tab_id, page.max(1))) {
        Ok((items, metrics)) => ok(&serde_json::json!({
            "items": items,
            "metrics": metrics,
        })),
        Err(e) => err(e),
    }
}

/// Search suggestions. Returns `{"ok": ["…"]}`.
pub fn core_suggest(query: &str) -> String {
    if query.trim().is_empty() {
        return err("empty query");
    }
    let Some(rt) = runtime() else {
        return err("async runtime unavailable");
    };
    match rt.block_on(service().suggest(query)) {
        Ok(suggestions) => ok(&suggestions),
        Err(e) => err(e),
    }
}

/// Parse an M3U playlist. `source` is either an `http(s)` URL (fetched with
/// the shared backend HTTP client) or raw playlist text. Kotlin should read
/// local files itself and pass the text. Returns `{"ok": [Channel…]}`.
pub fn core_m3u(source: &str) -> String {
    const MAX_PLAYLIST_BYTES: usize = 8 * 1024 * 1024;
    let Some(rt) = runtime() else {
        return err("async runtime unavailable");
    };
    let text = if crate::net::is_http_url(source) {
        match rt.block_on(service().http_client().get(source).send()) {
            Ok(resp) => match resp.error_for_status() {
                Ok(resp) => match rt.block_on(resp.text()) {
                    Ok(text) => text,
                    Err(e) => return err(format!("playlist body read failed: {e}")),
                },
                Err(e) => return err(format!("playlist HTTP error: {e}")),
            },
            Err(e) => return err(format!("playlist fetch failed: {e}")),
        }
    } else {
        source.to_string()
    };
    if text.len() > MAX_PLAYLIST_BYTES {
        return err("playlist too large (8 MiB cap)");
    }
    let parser = crate::providers::tv::parser::M3UParser::new();
    ok(&parser.parse_m3u(&text))
}

// ---------------------------------------------------------------------------
// Downloads (handle-based, pollable from Kotlin)
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct HeaderSpec {
    name: String,
    value: String,
}

#[derive(Debug, Deserialize)]
struct DownloadSpec {
    url: String,
    destination: String,
    #[serde(default)]
    headers: Vec<HeaderSpec>,
}

struct DownloadJob {
    cancel: Arc<AtomicBool>,
    downloaded: Arc<AtomicU64>,
    total: Arc<AtomicU64>,
    /// `u64::MAX` in `total` means "unknown".
    finished: Mutex<Option<serde_json::Value>>,
}

static JOBS: OnceLock<Mutex<HashMap<u64, Arc<DownloadJob>>>> = OnceLock::new();
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn jobs() -> &'static Mutex<HashMap<u64, Arc<DownloadJob>>> {
    JOBS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn lock_jobs() -> std::sync::MutexGuard<'static, HashMap<u64, Arc<DownloadJob>>> {
    jobs().lock().unwrap_or_else(|poison| poison.into_inner())
}

/// Start a download. `spec_json` =
/// `{"url": "...", "destination": "/abs/path/file.mp4",
///    "headers": [{"name": "...", "value": "..."}]}`.
/// The HTTP client is built exactly like the desktop download path
/// (per-mirror headers, no total timeout, resume-capable engine).
/// Returns `{"ok": {"id": 7}}`.
pub fn core_download_start(spec_json: &str) -> String {
    let spec: DownloadSpec = match serde_json::from_str(spec_json) {
        Ok(spec) => spec,
        Err(e) => return err(format!("bad download spec: {e}")),
    };
    if !crate::net::is_http_url(&spec.url) {
        return err("download url must be http(s)");
    }
    if spec.destination.trim().is_empty() {
        return err("empty download destination");
    }
    let destination = PathBuf::from(&spec.destination);
    let Some(parent) = destination.parent() else {
        return err("download destination has no parent directory");
    };
    if std::fs::create_dir_all(parent).is_err() {
        return err("cannot create download directory");
    }

    let mut builder = crate::net::http_client_builder()
        .connect_timeout(std::time::Duration::from_secs(15))
        .tcp_keepalive(std::time::Duration::from_secs(30));
    let mut header_map = reqwest::header::HeaderMap::new();
    let mut has_custom_ua = false;
    for h in &spec.headers {
        if h.name.eq_ignore_ascii_case("user-agent") {
            has_custom_ua = true;
            builder = builder.user_agent(h.value.clone());
        } else if let (Ok(name), Ok(value)) = (
            reqwest::header::HeaderName::from_bytes(h.name.as_bytes()),
            reqwest::header::HeaderValue::from_str(&h.value),
        ) {
            header_map.insert(name, value);
        }
    }
    if !has_custom_ua {
        builder = builder.user_agent(service().client.user_agent());
    }
    let client = match builder.default_headers(header_map).build() {
        Ok(client) => client,
        Err(e) => return err(format!("cannot build download client: {e}")),
    };

    let Some(rt) = runtime() else {
        return err("async runtime unavailable");
    };
    let job = Arc::new(DownloadJob {
        cancel: Arc::new(AtomicBool::new(false)),
        downloaded: Arc::new(AtomicU64::new(0)),
        total: Arc::new(AtomicU64::new(u64::MAX)),
        finished: Mutex::new(None),
    });
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    lock_jobs().insert(id, job.clone());

    let url = spec.url.clone();
    rt.spawn(async move {
        let downloaded = job.downloaded.clone();
        let total = job.total.clone();
        let outcome = match crate::download::download(
            &client,
            &url,
            Path::new(&destination),
            job.cancel.clone(),
            |progress: crate::download::DownloadProgress| {
                downloaded.store(progress.downloaded, Ordering::Relaxed);
                total.store(progress.total.unwrap_or(u64::MAX), Ordering::Relaxed);
            },
        )
        .await
        {
            Ok(crate::download::DownloadOutcome::Completed { bytes }) => {
                serde_json::json!({"status": "completed", "bytes": bytes})
            }
            Ok(crate::download::DownloadOutcome::Paused { bytes }) => {
                serde_json::json!({"status": "cancelled", "bytes": bytes})
            }
            Err(e) => serde_json::json!({"status": "failed", "error": e.to_string()}),
        };
        *job.finished
            .lock()
            .unwrap_or_else(|poison| poison.into_inner()) = Some(outcome);
    });

    ok(&serde_json::json!({"id": id}))
}

/// Poll a download. Returns
/// `{"ok": {"downloaded": n, "total": m|null, "finished": null|{…}}}`.
/// `finished.status` is one of `completed | cancelled | failed`.
pub fn core_download_status(id: u64) -> String {
    let guard = lock_jobs();
    let Some(job) = guard.get(&id) else {
        return err("unknown download id");
    };
    let total = job.total.load(Ordering::Relaxed);
    let finished = job
        .finished
        .lock()
        .unwrap_or_else(|poison| poison.into_inner())
        .clone();
    ok(&serde_json::json!({
        "downloaded": job.downloaded.load(Ordering::Relaxed),
        "total": if total == u64::MAX { None } else { Some(total) },
        "finished": finished,
    }))
}

/// Request cancellation. The engine stops at the next chunk boundary and the
/// job finishes with `{"status": "cancelled", …}` (resume sidecars are kept).
pub fn core_download_cancel(id: u64) -> String {
    let guard = lock_jobs();
    let Some(job) = guard.get(&id) else {
        return err("unknown download id");
    };
    job.cancel.store(true, Ordering::Relaxed);
    ok(&serde_json::json!(true))
}

/// Drop a job (finished or not) from the registry, freeing its slot.
/// Cancels first if it is still running.
pub fn core_download_release(id: u64) -> String {
    let mut guard = lock_jobs();
    let Some(job) = guard.remove(&id) else {
        return err("unknown download id");
    };
    job.cancel.store(true, Ordering::Relaxed);
    ok(&serde_json::json!(true))
}

// ---------------------------------------------------------------------------
// JNI exports (Kotlin: com.moviebox.core.NativeBridge)
// ---------------------------------------------------------------------------

fn jstr(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value)
        .map(|s| s.into())
        .map_err(|e| format!("jni string read failed: {e}"))
}

fn ret(env: &mut JNIEnv, payload: &str) -> jstring {
    env.new_string(payload)
        .map(|obj| obj.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_core_NativeBridge_init<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    spec: JString<'local>,
) -> jstring {
    let out = match jstr(&mut env, spec) {
        Ok(spec) => core_init(&spec),
        Err(e) => err(e),
    };
    ret(&mut env, &out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_core_NativeBridge_version<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let out = core_version();
    ret(&mut env, &out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_core_NativeBridge_search<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    provider: JString<'local>,
    query: JString<'local>,
    page: jint,
) -> jstring {
    let out = match (jstr(&mut env, provider), jstr(&mut env, query)) {
        (Ok(provider), Ok(query)) => {
            core_search(&provider, &query, usize::try_from(page).unwrap_or(1))
        }
        _ => err("jni string read failed"),
    };
    ret(&mut env, &out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_core_NativeBridge_details<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    provider: JString<'local>,
    subject_id: JString<'local>,
) -> jstring {
    let out = match (jstr(&mut env, provider), jstr(&mut env, subject_id)) {
        (Ok(provider), Ok(subject_id)) => core_details(&provider, &subject_id),
        _ => err("jni string read failed"),
    };
    ret(&mut env, &out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_core_NativeBridge_streams<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    provider: JString<'local>,
    subject_id: JString<'local>,
    season: jint,
    episode: jint,
    is_series: jboolean,
) -> jstring {
    let out = match (jstr(&mut env, provider), jstr(&mut env, subject_id)) {
        (Ok(provider), Ok(subject_id)) => core_streams(
            &provider,
            &subject_id,
            usize::try_from(season).unwrap_or(0),
            usize::try_from(episode).unwrap_or(0),
            is_series != 0,
        ),
        _ => err("jni string read failed"),
    };
    ret(&mut env, &out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_core_NativeBridge_subtitles<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    subject_id: JString<'local>,
    resource_id: JString<'local>,
    season: jint,
    episode: jint,
) -> jstring {
    let out = match (jstr(&mut env, subject_id), jstr(&mut env, resource_id)) {
        (Ok(subject_id), Ok(resource_id)) => core_subtitles(
            &subject_id,
            &resource_id,
            usize::try_from(season).unwrap_or(0),
            usize::try_from(episode).unwrap_or(0),
        ),
        _ => err("jni string read failed"),
    };
    ret(&mut env, &out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_core_NativeBridge_homepage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    tab_id: JString<'local>,
    page: jint,
) -> jstring {
    let out = match jstr(&mut env, tab_id) {
        Ok(tab_id) => core_homepage(&tab_id, usize::try_from(page).unwrap_or(1)),
        Err(e) => err(e),
    };
    ret(&mut env, &out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_core_NativeBridge_suggest<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    query: JString<'local>,
) -> jstring {
    let out = match jstr(&mut env, query) {
        Ok(query) => core_suggest(&query),
        Err(e) => err(e),
    };
    ret(&mut env, &out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_core_NativeBridge_m3u<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    source: JString<'local>,
) -> jstring {
    let out = match jstr(&mut env, source) {
        Ok(source) => core_m3u(&source),
        Err(e) => err(e),
    };
    ret(&mut env, &out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_core_NativeBridge_downloadStart<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    spec: JString<'local>,
) -> jstring {
    let out = match jstr(&mut env, spec) {
        Ok(spec) => core_download_start(&spec),
        Err(e) => err(e),
    };
    ret(&mut env, &out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_core_NativeBridge_downloadStatus<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> jstring {
    let out = core_download_status(u64::try_from(id).unwrap_or(0));
    ret(&mut env, &out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_core_NativeBridge_downloadCancel<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> jstring {
    let out = core_download_cancel(u64::try_from(id).unwrap_or(0));
    ret(&mut env, &out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_core_NativeBridge_downloadRelease<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> jstring {
    let out = core_download_release(u64::try_from(id).unwrap_or(0));
    ret(&mut env, &out)
}
