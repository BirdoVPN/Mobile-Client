# POPCNT is not in the Android x86 (32-bit) ABI baseline (SSSE3).
    .text
    .globl popcnt_fixture
    .type popcnt_fixture, @function
popcnt_fixture:
    popcnt %eax, %ebx
    ret
    .size popcnt_fixture, .-popcnt_fixture
