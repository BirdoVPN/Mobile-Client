# The sha2 crate shape: 34 SHA-NI sites (32 sha256rnds2 + msg1 + msg2)
# reached behind a cpuid check. The gate pins exactly this count for
# librosenpass_jni.so.
    .text
    .globl shani_fixture
    .type shani_fixture, @function
shani_fixture:
    cpuid
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256rnds2 %xmm0, %xmm1, %xmm2
    sha256msg1 %xmm1, %xmm2
    sha256msg2 %xmm1, %xmm2
    ret
    .size shani_fixture, .-shani_fixture
