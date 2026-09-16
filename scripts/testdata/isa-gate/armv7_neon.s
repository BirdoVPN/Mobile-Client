    .syntax unified
    .text
    .globl neon_fixture
    .type neon_fixture, %function
neon_fixture:
    vadd.i32 q0, q1, q2
    bx lr
    .size neon_fixture, .-neon_fixture
