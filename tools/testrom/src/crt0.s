@ SPDX-License-Identifier: MPL-2.0
@ AdvanceX test cartridge - startup code and cartridge header.
@
@ The Nintendo logo area (0x04-0x9F) is intentionally left zeroed: this ROM
@ contains no Nintendo data. Emulators with an HLE BIOS (such as mGBA, used by
@ AdvanceX) boot it directly; real hardware would reject it, which is fine for
@ a test image.

    .syntax unified
    .section .header, "ax"
    .arm
    .global _start
_start:
    b       reset_handler           @ 0x00 entry point
    .fill   156, 1, 0               @ 0x04 logo area (left blank on purpose)
    .ascii  "ADVANCEXTEST"          @ 0xA0 game title (12 bytes)
    .ascii  "ZAXT"                  @ 0xAC game code
    .ascii  "AX"                    @ 0xB0 maker code
    .byte   0x96                    @ 0xB2 fixed value
    .byte   0x00                    @ 0xB3 main unit code
    .byte   0x00                    @ 0xB4 device type
    .fill   7, 1, 0                 @ 0xB5 reserved
    .byte   0x00                    @ 0xBC software version
    .byte   0x00                    @ 0xBD header checksum (patched by fix_header.py)
    .fill   2, 1, 0                 @ 0xBE reserved

reset_handler:
    @ IRQ mode stack
    mov     r0, #0x12
    msr     cpsr_c, r0
    ldr     sp, =__sp_irq
    @ System mode stack (main program runs in system mode)
    mov     r0, #0x1F
    msr     cpsr_c, r0
    ldr     sp, =__sp_usr

    @ Copy initialised data from ROM to IWRAM
    ldr     r0, =__data_lma
    ldr     r1, =__data_start
    ldr     r2, =__data_end
1:  cmp     r1, r2
    ldrlo   r3, [r0], #4
    strlo   r3, [r1], #4
    blo     1b

    @ Zero .bss
    ldr     r1, =__bss_start
    ldr     r2, =__bss_end
    mov     r3, #0
2:  cmp     r1, r2
    strlo   r3, [r1], #4
    blo     2b

    @ Install the IRQ handler for the BIOS dispatcher
    ldr     r0, =irq_handler
    ldr     r1, =0x03007FFC
    str     r0, [r1]

    ldr     r0, =main
    mov     lr, pc
    bx      r0
3:  b       3b

    @ Minimal IRQ handler: acknowledge and flag interrupts for VBlankIntrWait.
    .global irq_handler
irq_handler:
    mov     r2, #0x04000000
    add     r2, r2, #0x200
    ldrh    r0, [r2]                @ IE
    ldrh    r1, [r2, #2]            @ IF
    and     r0, r0, r1
    strh    r0, [r2, #2]            @ acknowledge in IF
    ldr     r1, =0x03007FF8         @ BIOS interrupt flags
    ldrh    r3, [r1]
    orr     r3, r3, r0
    strh    r3, [r1]
    bx      lr

    .ltorg
