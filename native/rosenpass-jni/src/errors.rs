//! Error helpers for the JNI surface.
//!
//! Rust panics MUST NOT cross the JNI boundary — that's UB. We `panic = "abort"`
//! in release builds, and convert all recoverable failures to Java exceptions
//! via `throw_runtime`.

use jni::JNIEnv;
use std::fmt;

/// BirdoPQ v1 is a single ML-KEM exchange (see lib.rs), so the only failure
/// the JNI surface can report is a KEM one. The `NotImplemented` (M2-gated
/// entry points) and `Protocol` (Rosenpass frame errors) variants that used to
/// sit here were never constructed by anything and are gone; add a variant
/// back together with the code path that produces it.
#[derive(Debug)]
pub enum JniErr {
    /// PQ KEM failure (encapsulation/decapsulation rejected). Possibly an
    /// active attacker; caller should drop the session.
    Crypto(String),
}

impl fmt::Display for JniErr {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Crypto(s) => write!(f, "post-quantum crypto error: {s}"),
        }
    }
}

impl std::error::Error for JniErr {}

/// Throw `java.lang.RuntimeException(msg)` on the JVM side.
///
/// After calling this, the caller MUST return immediately — any further JNI
/// call before the exception is consumed will assert in the JVM.
pub fn throw_runtime(env: &mut JNIEnv<'_>, msg: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}
