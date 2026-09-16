// What `-C target-feature=+lse` produces for AtomicUsize::fetch_add: an LSE
// instruction inline, with no HWCAP check anywhere. SIGILL on ARMv8.0 cores.
    .text
    .globl atomic_inc_inline_fixture
    .type atomic_inc_inline_fixture, %function
atomic_inc_inline_fixture:
    mov w8, #1
    ldadd w8, w9, [x0]
    stadd w8, [x0]
    ret
    .size atomic_inc_inline_fixture, .-atomic_inc_inline_fixture
