//! Environment detection helpers shared by all targets.

/// Whether the current process runs on Android / inside Termux.
///
/// This is a pure environment probe (compile-time target plus well-known
/// Termux markers) with no UI or updater dependencies, so backend code such
/// as player detection can call it even in mobile builds where the `updater`
/// module is compiled out.
pub fn is_termux_environment() -> bool {
    cfg!(target_os = "android")
        || std::env::var("TERMUX_VERSION").is_ok()
        || std::env::var("PREFIX").is_ok_and(|p| p.contains("com.termux"))
        || std::path::Path::new("/data/data/com.termux/files/usr").exists()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn termux_probe_agrees_with_cfg_on_host() {
        // On a non-Android host without Termux markers this must be false.
        // (If this test ever runs inside Termux itself, the env-var branch
        // covers it and the assertion below is skipped.)
        if cfg!(target_os = "android")
            || std::env::var("TERMUX_VERSION").is_ok()
            || std::env::var("PREFIX").is_ok_and(|p| p.contains("com.termux"))
        {
            assert!(is_termux_environment());
        } else {
            assert!(!is_termux_environment());
        }
    }
}
