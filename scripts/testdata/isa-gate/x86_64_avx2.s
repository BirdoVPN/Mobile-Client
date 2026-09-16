# What pqcrypto-mlkem's `avx2` feature (-mavx2 -mbmi2 -mbmi -maes -mpclmul)
# produces: wide vectors, BMI2 bit ops, AES-NI and PCLMUL, none in the
# Android x86_64 ABI baseline (SSE4.2 + POPCNT).
    .text
    .globl avx2_fixture
    .type avx2_fixture, @function
avx2_fixture:
    vpxor %ymm0, %ymm1, %ymm2
    pdep %rax, %rbx, %rcx
    pext %rax, %rbx, %rcx
    aesenc %xmm1, %xmm0
    pclmulqdq $0, %xmm1, %xmm0
    vzeroupper
    ret
    .size avx2_fixture, .-avx2_fixture
