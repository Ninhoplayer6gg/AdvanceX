/* SPDX-License-Identifier: MPL-2.0
 * AdvanceX Test Cartridge.
 *
 * A small, original GBA program used to validate AdvanceX end to end without
 * any commercial software:
 *   - tiled backgrounds (text layer + scrolling pattern) and a sprite
 *   - PSG audio (background arpeggio on channel 2, SFX on channel 1)
 *   - input (D-pad moves the sprite, A recolours it, B plays a sound)
 *   - battery-backed SRAM (boot counter)
 *   - fixed-address data used by the Advance Engine runtime-patch demos
 */
#include "font.h"
#include "gba.h"

/* ---- Fixed-address data for runtime-patch demos ------------------------ */

/* EWRAM game state at a fixed address (see link.ld). The demo profile's
 * "infinite lives" memory patch writes 9 to `lives` every frame. */
struct GameState {
    u32 lives;
    u32 score;
    u32 frame;
    u32 magic;
};
#define GAME_STATE ((volatile struct GameState*) 0x02000000)

/* ROM text at 0x08000400. The demo profile's runtime ROM patch replaces
 * "OFF" with "ON!" in memory only; the .gba file is never modified. */
__attribute__((section(".axpatch"), used, aligned(4))) const char kPatchArea[16] = "ROM PATCH: OFF";

/* Lets emulators identify the save type (battery-backed SRAM). */
__attribute__((section(".axtag"), used, aligned(4))) const char kSaveTypeTag[12] = "SRAM_V113";

/* ---- Layout ------------------------------------------------------------ */

#define TEXT_SBB 30
#define PATTERN_SBB 31
#define TEXT_CBB 0
#define PATTERN_CBB 1
#define PATTERN_TILE 0

#define NOTE(f) ((u16) (2048 - (int) (131072.0 / (f) + 0.5)))

static const u16 kMelody[16] = {
    NOTE(523.25), NOTE(659.25), NOTE(783.99), NOTE(1046.50), /* C  E  G  C' */
    NOTE(440.00), NOTE(523.25), NOTE(659.25), NOTE(880.00),  /* A  C  E  A  */
    NOTE(349.23), NOTE(440.00), NOTE(523.25), NOTE(698.46),  /* F  A  C  F  */
    NOTE(392.00), NOTE(493.88), NOTE(587.33), NOTE(783.99),  /* G  B  D  G  */
};

/* 16x16 crystal sprite, colours 1..4 (outline -> core). */
static const char* const kSpriteArt[16] = {
    ".......11.......", "......1221......", ".....123321.....", "....12344321....",
    "...1234444321...", "..123444444321..", ".12344444444321.", "1234444444444321",
    "1234444444444321", ".12344444444321.", "..123444444321..", "...1234444321...",
    "....12344321....", ".....123321.....", "......1221......", ".......11.......",
};

static const u16 kSpritePalettes[2][5] = {
    {0, RGB15(6, 2, 14), RGB15(14, 6, 28), RGB15(8, 22, 31), RGB15(26, 31, 31)},  /* violet/cyan */
    {0, RGB15(12, 6, 0), RGB15(24, 14, 2), RGB15(31, 24, 6), RGB15(31, 31, 20)},  /* gold */
};

/* ---- Small helpers ----------------------------------------------------- */

static void vblankWait(void) {
    __asm__ volatile("swi 0x05" ::: "r0", "r1", "r2", "r3", "memory");
}

static int glyphIndex(char c) {
    for (int i = 0; i < kGlyphCount; ++i) {
        if (kGlyphChars[i] == c) {
            return i;
        }
    }
    return 0; /* space */
}

static void drawText(int col, int row, const char* text) {
    volatile u16* map = SCREENBLOCK(TEXT_SBB);
    for (int i = 0; text[i] && col + i < 30; ++i) {
        map[row * 32 + col + i] = (u16) glyphIndex(text[i]);
    }
}

/* Reads text through a volatile pointer so the compiler cannot constant-fold
 * ROM data that the runtime-patch demo modifies in memory. */
static void drawVolatileText(int col, int row, const volatile char* text, int maxLen) {
    char buffer[32];
    int i = 0;
    for (; i < maxLen && i < 31 && text[i]; ++i) {
        buffer[i] = text[i];
    }
    buffer[i] = '\0';
    drawText(col, row, buffer);
}

/* Unsigned to decimal without division (the ARM7TDMI has no divider). */
static void formatNumber(u32 value, char* out, int width) {
    static const u32 kPowers[10] = {1000000000u, 100000000u, 10000000u, 1000000u, 100000u,
                                    10000u,      1000u,      100u,      10u,      1u};
    int pos = 0;
    int started = 0;
    for (int p = 0; p < 10; ++p) {
        char digit = '0';
        while (value >= kPowers[p]) {
            value -= kPowers[p];
            ++digit;
        }
        if (digit != '0' || started || p >= 10 - width) {
            out[pos++] = digit;
            started = 1;
        }
    }
    out[pos] = '\0';
}

static void drawLabelNumber(int col, int row, const char* label, u32 value, int width) {
    char number[12];
    formatNumber(value, number, width);
    drawText(col, row, label);
    int len = 0;
    while (label[len]) {
        ++len;
    }
    drawText(col + len, row, number);
}

/* ---- Setup ------------------------------------------------------------- */

static void loadFont(void) {
    volatile u16* tiles = CHARBLOCK(TEXT_CBB);
    for (int g = 0; g < kGlyphCount; ++g) {
        for (int y = 0; y < 8; ++y) {
            u32 row = 0;
            for (int x = 0; x < 8; ++x) {
                u32 color = 0;
                int gx = x - 1;
                if (y < 7 && gx >= 0 && gx < 5 && kGlyphRows[g][y][gx] == '#') {
                    color = 1;
                } else if (y >= 1 && gx >= 1 && gx <= 5 && kGlyphRows[g][y - 1][gx - 1] == '#') {
                    color = 2; /* drop shadow */
                }
                row |= color << (x * 4);
            }
            tiles[g * 16 + y * 2] = (u16) (row & 0xFFFF);
            tiles[g * 16 + y * 2 + 1] = (u16) (row >> 16);
        }
    }
}

static void loadPattern(void) {
    volatile u16* tiles = CHARBLOCK(PATTERN_CBB);
    for (int y = 0; y < 8; ++y) {
        u32 row = 0;
        for (int x = 0; x < 8; ++x) {
            u32 color = (((x + y) & 7) < 3) ? 2 : 1;
            row |= color << (x * 4);
        }
        tiles[PATTERN_TILE * 16 + y * 2] = (u16) (row & 0xFFFF);
        tiles[PATTERN_TILE * 16 + y * 2 + 1] = (u16) (row >> 16);
    }
    volatile u16* map = SCREENBLOCK(PATTERN_SBB);
    for (int i = 0; i < 32 * 32; ++i) {
        map[i] = (u16) (PATTERN_TILE | (1 << 12)); /* palette bank 1 */
    }
}

static void loadSprite(void) {
    /* 16x16, 4bpp, 1D mapping: tiles ordered TL, TR, BL, BR. */
    for (int t = 0; t < 4; ++t) {
        int ox = (t & 1) * 8;
        int oy = (t >> 1) * 8;
        for (int y = 0; y < 8; ++y) {
            u32 row = 0;
            for (int x = 0; x < 8; ++x) {
                char c = kSpriteArt[oy + y][ox + x];
                u32 color = (c >= '1' && c <= '4') ? (u32) (c - '0') : 0;
                row |= color << (x * 4);
            }
            OBJ_TILES[t * 16 + y * 2] = (u16) (row & 0xFFFF);
            OBJ_TILES[t * 16 + y * 2 + 1] = (u16) (row >> 16);
        }
    }
    for (int i = 1; i < 128; ++i) {
        MEM_OAM[i * 4] = 0x0200; /* hide unused sprites */
    }
}

static void setSpritePalette(int which) {
    for (int i = 0; i < 5; ++i) {
        MEM_PAL_OBJ[i] = kSpritePalettes[which][i];
    }
}

static void placeSprite(int x, int y) {
    MEM_OAM[0] = (u16) (y & 0xFF);                  /* attr0: normal, 4bpp, square */
    MEM_OAM[1] = (u16) ((x & 0x1FF) | (1 << 14));   /* attr1: size 16x16 */
    MEM_OAM[2] = 0;                                 /* attr2: tile 0, priority 0, palbank 0 */
}

static u32 bumpBootCounter(void) {
    /* SRAM must be accessed 8 bits at a time. Layout: "AXSV" + u32 count. */
    u32 count = 0;
    if (MEM_SRAM[0] == 'A' && MEM_SRAM[1] == 'X' && MEM_SRAM[2] == 'S' && MEM_SRAM[3] == 'V') {
        count = (u32) MEM_SRAM[4] | ((u32) MEM_SRAM[5] << 8) | ((u32) MEM_SRAM[6] << 16) | ((u32) MEM_SRAM[7] << 24);
    }
    ++count;
    MEM_SRAM[0] = 'A';
    MEM_SRAM[1] = 'X';
    MEM_SRAM[2] = 'S';
    MEM_SRAM[3] = 'V';
    MEM_SRAM[4] = (u8) count;
    MEM_SRAM[5] = (u8) (count >> 8);
    MEM_SRAM[6] = (u8) (count >> 16);
    MEM_SRAM[7] = (u8) (count >> 24);
    return count;
}

static void initSound(void) {
    REG_SOUNDCNT_X = 0x0080; /* master enable */
    REG_SOUNDCNT_L = 0xFF77; /* PSG 1-4 on both sides, max volume */
    REG_SOUNDCNT_H = 0x0002; /* PSG at 100% */
    REG_SOUND1CNT_L = 0x0022;
}

static void playMelodyNote(int step) {
    REG_SOUND2CNT_L = 0xA280; /* 50% duty, volume 10, decaying envelope */
    REG_SOUND2CNT_H = (u16) (kMelody[step & 15] | 0x8000);
}

static void playSfx(void) {
    REG_SOUND1CNT_L = 0x0022; /* rising sweep */
    REG_SOUND1CNT_H = 0xF180;
    REG_SOUND1CNT_X = (u16) (NOTE(523.25) | 0x8000);
}

/* ---- Main loop --------------------------------------------------------- */

int main(void) {
    REG_DISPCNT = 0; /* display off while VRAM is being filled */
    MEM_PAL_BG[0] = RGB15(2, 2, 6);       /* backdrop */
    MEM_PAL_BG[1] = RGB15(31, 31, 31);    /* text */
    MEM_PAL_BG[2] = RGB15(1, 1, 4);       /* text shadow */
    MEM_PAL_BG[16 + 1] = RGB15(3, 4, 10); /* pattern */
    MEM_PAL_BG[16 + 2] = RGB15(5, 7, 15);

    loadFont();
    loadPattern();
    loadSprite();
    setSpritePalette(0);

    REG_BG0CNT = BG_CBB(TEXT_CBB) | BG_SBB(TEXT_SBB) | BG_PRIO(0);
    REG_BG1CNT = BG_CBB(PATTERN_CBB) | BG_SBB(PATTERN_SBB) | BG_PRIO(2);
    REG_DISPCNT = DCNT_MODE0 | DCNT_BG0 | DCNT_BG1 | DCNT_OBJ | DCNT_OBJ_1D;

    GAME_STATE->lives = 3;
    GAME_STATE->score = 0;
    GAME_STATE->frame = 0;
    GAME_STATE->magic = 0x41585453; /* "AXTS" */

    u32 boots = bumpBootCounter();
    initSound();

    REG_DISPSTAT = DSTAT_VBL_IRQ;
    REG_IE = IRQ_VBLANK;
    REG_IME = 1;

    drawText(3, 1, "ADVANCEX TEST CARTRIDGE");
    drawLabelNumber(2, 5, "BOOTS: ", boots, 1);
    drawText(2, 15, "DPAD MOVE  A COLOR  B SFX");
    drawText(2, 16, "START MUSIC  SELECT SCROLL");
    drawText(2, 18, "HOMEBREW - NOT FOR SALE");

    int spriteX = 112, spriteY = 96;
    int palette = 0;
    int music = 1;
    int scroll = 1;
    int scrollX = 0, scrollY = 0;
    int step = 0;
    int livesTimer = 0;
    int musicTimer = 0;
    u16 previousKeys = 0;

    for (;;) {
        vblankWait();

        u16 keys = (u16) (~REG_KEYINPUT & 0x03FF);
        u16 pressed = (u16) (keys & ~previousKeys);
        previousKeys = keys;

        if (keys & KEY_LEFT) spriteX -= 2;
        if (keys & KEY_RIGHT) spriteX += 2;
        if (keys & KEY_UP) spriteY -= 2;
        if (keys & KEY_DOWN) spriteY += 2;
        if (spriteX < 0) spriteX = 0;
        if (spriteX > 224) spriteX = 224;
        if (spriteY < 0) spriteY = 0;
        if (spriteY > 144) spriteY = 144;
        if (pressed & KEY_A) {
            palette ^= 1;
            setSpritePalette(palette);
            GAME_STATE->score += 100;
        }
        if (pressed & KEY_B) playSfx();
        if (pressed & KEY_START) music ^= 1;
        if (pressed & KEY_SELECT) scroll ^= 1;
        placeSprite(spriteX, spriteY);

        if (scroll) {
            ++scrollX;
            if ((GAME_STATE->frame & 1) == 0) ++scrollY;
        }
        REG_BG1HOFS = (u16) scrollX;
        REG_BG1VOFS = (u16) scrollY;

        /* No '%' here: the CPU has no divider and we link no runtime library. */
        if (++musicTimer >= 10) {
            musicTimer = 0;
            if (music) playMelodyNote(step++);
        }

        GAME_STATE->frame += 1;
        GAME_STATE->score += 1;
        if (++livesTimer >= 180) {
            livesTimer = 0;
            GAME_STATE->lives = GAME_STATE->lives ? GAME_STATE->lives - 1 : 3;
        }

        drawLabelNumber(2, 3, "FRAME: ", GAME_STATE->frame, 7);
        drawLabelNumber(2, 6, "LIVES: ", GAME_STATE->lives, 1);
        drawText(10, 6, GAME_STATE->lives ? "         " : "GAME OVER");
        drawLabelNumber(2, 7, "SCORE: ", GAME_STATE->score, 7);
        drawVolatileText(2, 9, (const volatile char*) kPatchArea, 15);

        char keyLine[11];
        static const char kKeyNames[10] = {'A', 'B', 'E', 'S', '>', '<', '^', 'V', 'R', 'L'};
        for (int i = 0; i < 10; ++i) {
            keyLine[i] = (keys & (1 << i)) ? kKeyNames[i] : '-';
        }
        keyLine[10] = '\0';
        drawText(2, 11, "KEYS: ");
        drawText(8, 11, keyLine);
        drawText(2, 12, music ? "MUSIC: ON " : "MUSIC: OFF");
    }
}
