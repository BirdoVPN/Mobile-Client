// Hand-assembled FEAT_SHA3 fixture: the four opcodes of PQClean's keccak2x
// feat.S round macro, exactly what crashed 1.4.25 on every non-SHA3 core.
    .text
    .globl f1600x2_fixture
    .type f1600x2_fixture, %function
f1600x2_fixture:
    eor3 v25.16b, v0.16b, v5.16b, v10.16b
    rax1 v26.2d, v1.2d, v6.2d
    xar  v27.2d, v2.2d, v7.2d, #1
    bcax v28.16b, v3.16b, v8.16b, v13.16b
    ret
    .size f1600x2_fixture, .-f1600x2_fixture
