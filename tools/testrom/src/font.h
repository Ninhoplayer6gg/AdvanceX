/* SPDX-License-Identifier: MPL-2.0 */
#ifndef AX_TESTROM_FONT_H
#define AX_TESTROM_FONT_H

/* Characters available in the font, in glyph order. */
extern const char kGlyphChars[];
/* Glyph bitmaps: 7 rows of 5 columns, '#' = ink. */
extern const char* const kGlyphRows[][7];
extern const int kGlyphCount;

#endif
