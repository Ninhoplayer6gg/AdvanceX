/* SPDX-License-Identifier: MPL-2.0
 * Minimal GBA hardware definitions for the AdvanceX test cartridge.
 * Written from the publicly documented GBA memory map (GBATEK); no
 * third-party headers are used.
 */
#ifndef AX_TESTROM_GBA_H
#define AX_TESTROM_GBA_H

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef signed short s16;
typedef signed int s32;

#define REG16(addr) (*(volatile u16*) (addr))
#define REG32(addr) (*(volatile u32*) (addr))

/* Display */
#define REG_DISPCNT REG16(0x04000000)
#define REG_DISPSTAT REG16(0x04000004)
#define REG_VCOUNT REG16(0x04000006)
#define REG_BG0CNT REG16(0x04000008)
#define REG_BG1CNT REG16(0x0400000A)
#define REG_BG0HOFS REG16(0x04000010)
#define REG_BG0VOFS REG16(0x04000012)
#define REG_BG1HOFS REG16(0x04000014)
#define REG_BG1VOFS REG16(0x04000016)

#define DCNT_MODE0 0x0000
#define DCNT_OBJ_1D 0x0040
#define DCNT_BG0 0x0100
#define DCNT_BG1 0x0200
#define DCNT_OBJ 0x1000
#define DSTAT_VBL_IRQ 0x0008

#define BG_CBB(n) ((n) << 2)
#define BG_SBB(n) ((n) << 8)
#define BG_PRIO(n) (n)

/* Interrupts */
#define REG_IE REG16(0x04000200)
#define REG_IF REG16(0x04000202)
#define REG_IME REG16(0x04000208)
#define IRQ_VBLANK 0x0001
#define BIOS_IRQ_HANDLER (*(volatile u32*) 0x03007FFC)

/* Input */
#define REG_KEYINPUT REG16(0x04000130)
#define KEY_A 0x0001
#define KEY_B 0x0002
#define KEY_SELECT 0x0004
#define KEY_START 0x0008
#define KEY_RIGHT 0x0010
#define KEY_LEFT 0x0020
#define KEY_UP 0x0040
#define KEY_DOWN 0x0080
#define KEY_R 0x0100
#define KEY_L 0x0200

/* Sound (PSG) */
#define REG_SOUND1CNT_L REG16(0x04000060)
#define REG_SOUND1CNT_H REG16(0x04000062)
#define REG_SOUND1CNT_X REG16(0x04000064)
#define REG_SOUND2CNT_L REG16(0x04000068)
#define REG_SOUND2CNT_H REG16(0x0400006C)
#define REG_SOUNDCNT_L REG16(0x04000080)
#define REG_SOUNDCNT_H REG16(0x04000082)
#define REG_SOUNDCNT_X REG16(0x04000084)

/* Memory */
#define MEM_PAL_BG ((volatile u16*) 0x05000000)
#define MEM_PAL_OBJ ((volatile u16*) 0x05000200)
#define MEM_VRAM ((volatile u16*) 0x06000000)
#define MEM_OAM ((volatile u16*) 0x07000000)
#define MEM_SRAM ((volatile u8*) 0x0E000000)

#define CHARBLOCK(n) ((volatile u16*) (0x06000000 + (n) * 0x4000))
#define SCREENBLOCK(n) ((volatile u16*) (0x06000000 + (n) * 0x800))
#define OBJ_TILES ((volatile u16*) 0x06010000)

#define RGB15(r, g, b) ((u16) ((r) | ((g) << 5) | ((b) << 10)))

#endif
