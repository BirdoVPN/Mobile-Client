//! Error helpers for the JNI surface.
//!
//! Rust panics MUST NOT cross the JNI boundary — that's UB. We `panic = "abort"`
//! in release builds, and convert all recoverable failures to Java exceptions
//! via `throw_runtime`.

use jni::JNIEnv;
use std::fmt;

#[derive(Debug)]
pub enum JniErr {
    /// The function is part of the JNI surface but its implementation is
    /// gated behind the M2 milestone. Caller should fall back gracefully.
    NotImplemented(&'static str),
    /// Something in the Rosenpass protocol failed (decryption, signature,
    /// frame parse, etc.) — caller should NOT retry the same handshake.
    Protocol(String),
    /// PQ KEM failure (encapsulation/decapsulation rejected). Possibly an
    /// active attacker; caller should drop the session.
    Crypto(String),
}

impl fmt::Display for JniErr {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NotImplemented(s) => write!(f, "not implemented yet: {s}"),
            Self::Protocol(s) => write!(f, "rosenpass protocol error: {s}"),
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
