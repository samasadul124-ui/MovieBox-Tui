//! Shared backend utilities with no UI dependencies.
//!
//! Anything in here must compile for every target, including the Android
//! `cdylib` (mobile backend) builds where the `tui` module is compiled out.
//! Historically these helpers lived in `crate::tui::text`; they were moved
//! here so providers, services, and persistence code do not depend on the
//! terminal UI. `crate::tui::text` re-exports them for compatibility.

pub mod env;
pub mod text;
