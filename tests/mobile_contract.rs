#![cfg(feature = "mobile")]
//! Contract tests for the mobile (Android APK) backend bridge.
//!
//! These run on the host against the exact `core_*` functions the JNI
//! exports call, so they validate the real APK code path without an
//! emulator. Offline tests always run; live-network tests are `#[ignore]`d.

use moviebox_tui::mobile;
use serde_json::Value;

fn parse(raw: &str) -> Value {
    serde_json::from_str(raw).expect("bridge must return valid JSON")
}

fn ok_payload(raw: &str) -> Value {
    let v = parse(raw);
    assert!(
        v.get("ok").is_some(),
        "expected {{\"ok\": …}} envelope, got: {raw}"
    );
    v.get("ok").expect("ok").clone()
}

fn err_message(raw: &str) -> String {
    let v = parse(raw);
    assert!(
        v.get("error").is_some(),
        "expected {{\"error\": …}} envelope, got: {raw}"
    );
    v.get("error")
        .and_then(|e| e.as_str())
        .unwrap_or("")
        .to_string()
}

fn temp_dirs(tag: &str) -> (String, String, String) {
    let base = std::env::temp_dir().join(format!(
        "mb_mobile_contract_{}_{}_{}",
        tag,
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos()
    ));
    (
        base.join("config").to_string_lossy().into_owned(),
        base.join("data").to_string_lossy().into_owned(),
        base.join("cache").to_string_lossy().into_owned(),
    )
}

#[test]
fn version_reports_mobile_flavor() {
    let v = ok_payload(&mobile::core_version());
    assert_eq!(v.get("flavor").and_then(|f| f.as_str()), Some("mobile"));
    assert_eq!(v.get("api").and_then(|a| a.as_u64()), Some(1));
    assert!(
        v.get("version")
            .and_then(|x| x.as_str())
            .is_some_and(|s| !s.is_empty())
    );
}

#[test]
fn init_creates_dirs_and_is_idempotent() {
    let (config, data, cache) = temp_dirs("init");
    let spec = serde_json::json!({
        "config_dir": config,
        "data_dir": data,
        "cache_dir": cache,
    })
    .to_string();
    let first = ok_payload(&mobile::core_init(&spec));
    assert!(first.get("first_init").and_then(|b| b.as_bool()).is_some());
    assert_eq!(first.get("runtime").and_then(|b| b.as_bool()), Some(true));
    assert!(std::path::Path::new(&config).is_dir());
    assert!(std::path::Path::new(&data).is_dir());
    assert!(std::path::Path::new(&cache).is_dir());

    // Second init (even with different dirs) must not fail and must not
    // move the already-pinned directories.
    let (config2, data2, cache2) = temp_dirs("init2");
    let spec2 = serde_json::json!({
        "config_dir": config2,
        "data_dir": data2,
        "cache_dir": cache2,
    })
    .to_string();
    let second = ok_payload(&mobile::core_init(&spec2));
    assert_eq!(
        second.get("first_init").and_then(|b| b.as_bool()),
        Some(false)
    );
}

#[test]
fn init_rejects_bad_specs() {
    assert!(!err_message(&mobile::core_init("not json")).is_empty());
    let bad = serde_json::json!({
        "config_dir": "",
        "data_dir": "/tmp",
        "cache_dir": "/tmp",
    })
    .to_string();
    assert!(!err_message(&mobile::core_init(&bad)).is_empty());
}

#[test]
fn invalid_provider_yields_error_envelope_without_network() {
    for raw in [
        mobile::core_search("nope", "x", 1),
        mobile::core_details("nope", "x"),
        mobile::core_streams("nope", "x", 0, 0, false),
    ] {
        let msg = err_message(&raw);
        assert!(
            msg.contains("unknown provider"),
            "unexpected message: {msg}"
        );
    }
}

#[test]
fn empty_inputs_yield_error_envelopes() {
    assert!(!err_message(&mobile::core_search("moviebox", "   ", 1)).is_empty());
    assert!(!err_message(&mobile::core_details("moviebox", "")).is_empty());
    assert!(!err_message(&mobile::core_suggest("")).is_empty());
    assert!(!err_message(&mobile::core_subtitles("", "", 0, 0)).is_empty());
}

#[test]
fn m3u_parses_offline_playlist_text() {
    let (config, data, cache) = temp_dirs("m3u");
    let spec = serde_json::json!({
        "config_dir": config,
        "data_dir": data,
        "cache_dir": cache,
    })
    .to_string();
    let _ = mobile::core_init(&spec);

    let playlist = "#EXTM3U\n\
        #EXTINF:-1 tvg-id=\"cnn.us\" tvg-logo=\"http://logo.png/cnn.png\" group-title=\"News\",CNN HD\n\
        http://example.com/cnn.m3u8\n\
        #EXTINF:-1,Discovery Channel\n\
        http://example.com/discovery.m3u8\n";
    let channels = ok_payload(&mobile::core_m3u(playlist));
    let list = channels.as_array().expect("channel array");
    assert_eq!(list.len(), 2);
    assert_eq!(list[0].get("id").and_then(|v| v.as_str()), Some("cnn.us"));
    assert_eq!(
        list[0].get("stream_url").and_then(|v| v.as_str()),
        Some("http://example.com/cnn.m3u8")
    );
    assert_eq!(
        list[1].get("name").and_then(|v| v.as_str()),
        Some("Discovery Channel")
    );
}

#[test]
fn download_rejects_bad_specs_and_unknown_ids() {
    assert!(!err_message(&mobile::core_download_start("nope")).is_empty());
    let bad_url = serde_json::json!({
        "url": "ftp://example.com/x.mp4",
        "destination": "/tmp/x.mp4",
    })
    .to_string();
    assert!(err_message(&mobile::core_download_start(&bad_url)).contains("http(s)"));
    // u64::MAX can never be a real job id (ids count up from 1).
    for raw in [
        mobile::core_download_status(u64::MAX),
        mobile::core_download_cancel(u64::MAX),
        mobile::core_download_release(u64::MAX),
    ] {
        assert!(err_message(&raw).contains("unknown download id"));
    }
}

#[test]
fn provider_names_cover_all_backends() {
    // Every provider id Kotlin may send must parse (offline check through
    // the validation layer: empty query fails *after* provider parsing).
    for name in [
        "moviebox",
        "fourkhdhub",
        "bdix_circleftp",
        "bdix_dhakaflix",
        "addons",
    ] {
        let msg = err_message(&mobile::core_search(name, "   ", 1));
        assert!(
            msg.contains("empty query"),
            "provider {name} did not parse: {msg}"
        );
    }
}

// Plain #[test] (not tokio): core_* blocks on its own runtime internally,
// and block_on panics if called from inside another runtime — the same
// discipline the Kotlin side follows (plain background threads).
#[test]
#[ignore = "live network test; run with cargo test --test mobile_contract -- --ignored"]
fn live_search_roundtrip() {
    let (config, data, cache) = temp_dirs("live");
    let spec = serde_json::json!({
        "config_dir": config,
        "data_dir": data,
        "cache_dir": cache,
    })
    .to_string();
    let _ = mobile::core_init(&spec);

    let items = ok_payload(&mobile::core_search("moviebox", "breaking bad", 1));
    let list = items.as_array().expect("search result array");
    assert!(!list.is_empty(), "live search returned no items");
    assert!(
        list[0]
            .get("title")
            .and_then(|t| t.as_str())
            .is_some_and(|t| !t.is_empty()),
        "first item has no title: {}",
        list[0]
    );

    let suggestions = ok_payload(&mobile::core_suggest("breaking"));
    assert!(suggestions.as_array().is_some());
}
