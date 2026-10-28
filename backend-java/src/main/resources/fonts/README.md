# Chinese report fonts

Both files are Noto Sans SC, licensed under the SIL Open Font License 1.1; the
bundled `OFL.txt` is the upstream license (the only Reserved Font Name declared
there is `Source`, so the `Noto Sans SC` names are kept in the derived file).

| File | Bytes | Source |
| --- | ---: | --- |
| `NotoSansSC.ttf` | 17772300 | Unmodified Google Fonts variable font `NotoSansSC[wght].ttf`, downloaded from https://github.com/google/fonts/tree/main/ofl/notosanssc on 2026-10-28. wght axis 100-900, default instance Thin/100. |
| `NotoSansSC-Bold.ttf` | 10585724 | Static `wght=700` instance generated from the file above on 2026-10-28 with fontTools `varLib.instancer.instantiateVariableFont`. Name table is set to family `Noto Sans SC`, subfamily `Bold`, PostScript `NotoSansSC-Bold`; OS/2 `usWeightClass=700` and `fsSelection` BOLD; head `macStyle` bold bit set. Variable tables (`fvar`/`gvar`/`avar`/`HVAR`) are removed. |
| `radical-map.tsv` | 3338 | 287 `radical -> unified ideograph` pairs derived from the two cmaps above (SHA-256 `C48627F20F6190B7F04611D3E9CBBC622046985AB506902399C99B4A9E4E0805`). Used to clean the PDF text layer; not a font. |

`ReportRenderer.pdf` registers both files with openhtmltopdf under the same
`ResearchRunner` family at weights 400 and 700, so `h1`-`h6` and `strong` use the
bold face while body text keeps the regular one. Only used subsets are embedded
in the PDF.

The fonts are packaged locally; report rendering never downloads fonts at
runtime. Both fonts map some characters to glyphs that are also reachable
through CJK radical code points: 行 U+884C shares the glyph named `uni2F8F`
(Kangxi radical, U+2F00-U+2FD5), and 长 U+957F shares the glyph `uni2ED3` (CJK
Radicals Supplement, U+2E80-U+2EFF). PDFBox builds each subset font's
`/ToUnicode` CMap from those glyph names, so `ReportRenderer` normalizes the
finished PDF's ToUnicode mappings:

1. Unicode NFKC for the Kangxi radical block (which has compatibility
   decompositions);
2. `radical-map.tsv` for the CJK Radicals Supplement block (no NFKC
   decomposition). The map is generated from the bundled cmaps and only contains
   radicals whose glyph is also the glyph of a unified ideograph (287 entries;
   both fonts agree). This makes the text layer searchable/copyable without any
   test-side normalization.

