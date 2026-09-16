// compiler-rt outlined-atomics helper shape (lib/builtins/aarch64/lse.S):
// every LSE instruction sits behind a load of __aarch64_have_lse_atomics,
// which the runtime sets from getauxval(AT_HWCAP) & HWCAP_ATOMICS.
    .bss
    .globl __aarch64_have_lse_atomics
    .hidden __aarch64_have_lse_atomics
__aarch64_have_lse_atomics:
    .byte 0
    .text
    .globl __aarch64_ldadd4_acq_rel
    .hidden __aarch64_ldadd4_acq_rel
    .type __aarch64_ldadd4_acq_rel, %function
__aarch64_ldadd4_acq_rel:
    bti c
    adrp x16, __aarch64_have_lse_atomics
    ldrb w16, [x16, :lo12:__aarch64_have_lse_atomics]
    cbz w16, 1f
    ldaddal w0, w0, [x1]
    ret
1:  mov w16, w0
2:  ldaxr w0, [x1]
    add w17, w0, w16
    stlxr w15, w17, [x1]
    cbnz w15, 2b
    ret
    .size __aarch64_ldadd4_acq_rel, .-__aarch64_ldadd4_acq_rel

    .globl __aarch64_cas4_acq_rel
    .hidden __aarch64_cas4_acq_rel
    .type __aarch64_cas4_acq_rel, %function
__aarch64_cas4_acq_rel:
    bti c
    adrp x16, __aarch64_have_lse_atomics
    ldrb w16, [x16, :lo12:__aarch64_have_lse_atomics]
    cbz w16, 1f
    casal w0, w1, [x2]
    ret
1:  mov w16, w0
2:  ldaxr w0, [x2]
    cmp w0, w16
    b.ne 3f
    stlxr w17, w1, [x2]
    cbnz w17, 2b
3:  ret
    .size __aarch64_cas4_acq_rel, .-__aarch64_cas4_acq_rel

    .globl atomic_inc_fixture
    .type atomic_inc_fixture, %function
atomic_inc_fixture:
    paciasp
    stp x29, x30, [sp, #-16]!
    mov w0, #1
    bl __aarch64_ldadd4_acq_rel
    ldp x29, x30, [sp], #16
    autiasp
    ret
    .size atomic_inc_fixture, .-atomic_inc_fixture
