/* SPDX-License-Identifier: MPL-2.0
 * Linker script for the AdvanceX test cartridge.
 *
 * Fixed addresses (relied upon by the bundled demo profile and tests):
 *   0x08000400  .axpatch   ROM data that the demo "runtime ROM patch" edits
 *   0x02000000  GameState  EWRAM variables that the demo "memory patch" edits
 */
OUTPUT_FORMAT("elf32-littlearm")
ENTRY(_start)

MEMORY {
    rom   (rx)  : ORIGIN = 0x08000000, LENGTH = 32M
    iwram (rwx) : ORIGIN = 0x03000000, LENGTH = 32K
}

__sp_irq = 0x03007FA0;
__sp_usr = 0x03007F00;

SECTIONS {
    .header 0x08000000 : { KEEP(*(.header)) } > rom
    .axpatch 0x08000400 : { KEEP(*(.axpatch)) KEEP(*(.axtag)) } > rom
    .text : { *(.text .text.*) } > rom
    .rodata : { *(.rodata .rodata.*) . = ALIGN(4); } > rom

    __data_lma = LOADADDR(.data);
    .data : {
        __data_start = .;
        *(.data .data.*)
        . = ALIGN(4);
        __data_end = .;
    } > iwram AT > rom

    .bss (NOLOAD) : {
        __bss_start = .;
        *(.bss .bss.* COMMON)
        . = ALIGN(4);
        __bss_end = .;
    } > iwram

    /DISCARD/ : { *(.ARM.exidx*) *(.ARM.attributes) *(.comment) }
}
