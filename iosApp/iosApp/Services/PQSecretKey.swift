import Foundation

/// The ML-KEM-1024 secret key in ONE manually allocated buffer (MR-2123).
///
/// `Data` and `[UInt8]` are copy-on-write values: handing one around can leave
/// further copies behind, and nothing wipes their storage when the last
/// reference goes. `BirdoPQManager` used to keep the 3168-byte secret key as
/// `Data` in a cache that lived as long as the process, while /privacy says the
/// mobile apps' post-quantum key code clears key material from memory after
/// use. The Rust layer did; the Swift layer above it did not.
///
/// This buffer is allocated once, never copied into Swift-managed storage, and
/// overwritten with `memset_s` (which the optimiser may not remove) by
/// `wipe()`, and again before it is freed.
///
/// Foundation-only by design, so `PQSecretKeyTests` can pin it:
/// `BirdoPQManager.swift` imports `BirdoPQNative` and cannot be compiled into
/// the unit-test bundle.
final class PQSecretKey {
    private let storage: UnsafeMutableRawBufferPointer

    /// Copies `bytes` in. Wiping the source is the caller's job.
    init<Bytes: Collection>(copying bytes: Bytes) where Bytes.Element == UInt8 {
        storage = UnsafeMutableRawBufferPointer.allocate(byteCount: bytes.count, alignment: 1)
        storage.copyBytes(from: bytes)
    }

    var count: Int { storage.count }

    /// Read access for the native call. The pointer must not escape `body`.
    func withUnsafeBytes<R>(_ body: (UnsafeRawBufferPointer) throws -> R) rethrows -> R {
        try body(UnsafeRawBufferPointer(storage))
    }

    /// Overwrites the key with zeros. Idempotent; the key is unusable after it.
    func wipe() {
        guard let base = storage.baseAddress, storage.count > 0 else { return }
        _ = memset_s(base, storage.count, 0, storage.count)
    }

    deinit {
        wipe()
        storage.deallocate()
    }
}
