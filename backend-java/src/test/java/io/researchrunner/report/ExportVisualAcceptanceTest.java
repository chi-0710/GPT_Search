package io.researchrunner.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

/**
 * A6-01: export visual-acceptance fixture.
 *
 * <p>Writes a Chinese report with an embedded chart image to target/visual-acceptance/ so a human
 * can inspect PDF/DOCX rendering, while asserting the machine-checkable properties: strict text
 * layer (no NFKC workaround, no Kangxi compatibility radicals), bold heading font, embedded image,
 * clickable link, explicit DOCX styles.xml + sectPr and image width inside the A4 content box.
 */
class ExportVisualAcceptanceTest {
    private static final String MARKDOWN = """
            # 知行研究助手

            ## 市场概况

            ![趋势图](/api/artifacts/11111111-1111-1111-1111-111111111111)

            中文正文与[来源](https://example.com)。

            | 指标 | 结果 |
            | --- | --- |
            | 资料 | 通过 |
            """;
    /** 480 pt = A4 595.28 pt minus two 20 mm margins (481.89 pt), converted to EMU (1 pt = 12700). */
    private static final long CONTENT_WIDTH_EMU = 6_096_000;

    @Test
    void writesChineseReportWithImageForManualReview() throws Exception {
        var image = new java.awt.image.BufferedImage(640, 360, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, 640, 360);
        graphics.setColor(java.awt.Color.BLUE);
        graphics.fillRect(40, 40, 240, 120);
        graphics.dispose();
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", png);
        var renderer = new ReportRenderer(id -> png.toByteArray());
        Path output = Path.of("target", "visual-acceptance");
        Files.createDirectories(output);
        Files.write(output.resolve("report.pdf"), renderer.pdf(MARKDOWN));
        Files.write(output.resolve("report.docx"), renderer.docx(MARKDOWN));
        Files.write(output.resolve("report.md"), MARKDOWN.getBytes(StandardCharsets.UTF_8));

        byte[] pdfBytes = Files.readAllBytes(output.resolve("report.pdf"));
        try (var pdf = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(pdf);
            for (String expected : List.of("知行研究助手", "市场概况", "中文正文", "来源", "指标", "资料", "通过")) {
                assertTrue(text.contains(expected), "PDF text layer is missing " + expected + ": " + text);
            }
            assertTrue(text.chars().noneMatch(character -> character >= 0x2E80 && character <= 0x2FDF),
                    "PDF text layer must not use Kangxi radical code points: " + text);
            assertEquals(text, Normalizer.normalize(text, Normalizer.Form.NFKC), "PDF text layer must be NFKC stable");
            assertTrue(pdf.getPage(0).getResources().getXObjectNames().iterator().hasNext());
            var annotations = pdf.getPage(0).getAnnotations();
            assertEquals(1, annotations.size());
            var link = (PDAnnotationLink) annotations.getFirst();
            assertEquals("https://example.com", ((PDActionURI) link.getAction()).getURI());

            var headingFonts = fontsFor(pdf, "知行研究助手");
            assertFalse(headingFonts.isEmpty(), "heading text must be found");
            for (var entry : headingFonts.entrySet()) {
                assertTrue(entry.getKey().contains("Bold"), "heading font must be the bold face: " + entry.getKey());
                assertTrue(entry.getValue() >= 700f, "heading font weight: " + entry);
            }
            var bodyFonts = fontsFor(pdf, "中文正文");
            assertFalse(bodyFonts.isEmpty());
            assertTrue(bodyFonts.keySet().stream().noneMatch(name -> name.contains("Bold")),
                    "body text must stay on the regular face: " + bodyFonts.keySet());
        }

        byte[] docxBytes = Files.readAllBytes(output.resolve("report.docx"));
        var entries = zipEntries(docxBytes);
        try (var docx = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            assertTrue(docx.getParagraphs().stream().anyMatch(p -> p.getText().contains("中文正文")));
            assertTrue(docx.getParagraphs().stream().anyMatch(p -> "Heading1".equals(p.getStyle())),
                    "first paragraph must carry the Heading1 paragraph style");
            assertTrue(docx.getAllPictures().size() >= 1);
            assertEquals("指标", docx.getTables().getFirst().getRow(0).getCell(0).getText());
        }
        String styles = entries.get("word/styles.xml");
        assertNotNull(styles, "word/styles.xml must exist");
        assertEquals(1, styles.split("<w:styles", -1).length - 1, "styles.xml must have a single root element");
        for (String styleId : List.of("Heading1", "Heading2", "Heading3")) {
            assertTrue(styles.contains("w:styleId=\"" + styleId + "\""), "missing style " + styleId);
        }
        for (int outlineLevel = 0; outlineLevel < 3; outlineLevel++) {
            assertTrue(styles.contains("<w:outlineLvl w:val=\"" + outlineLevel + "\"/>"), "missing outline level " + outlineLevel);
        }
        assertTrue(styles.contains("<w:b/>"), "heading styles must be bold");
        for (String size : List.of("44", "33", "28")) {
            assertTrue(styles.contains("<w:sz w:val=\"" + size + "\"/>"), "missing heading size " + size);
        }
        String documentXml = entries.get("word/document.xml");
        assertNotNull(documentXml);
        assertTrue(documentXml.contains("<w:sectPr>"));
        assertTrue(documentXml.contains("<w:pgSz w:w=\"11906\" w:h=\"16838\"/>"), "A4 page size missing");
        assertTrue(documentXml.contains("<w:pgMar w:top=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:right=\"1134\""),
                "20 mm margins missing");
        var extent = Pattern.compile("<wp:extent cx=\"(\\d+)\" cy=\"(\\d+)\"/>").matcher(documentXml);
        assertTrue(extent.find(), "embedded picture extent missing");
        long width = Long.parseLong(extent.group(1));
        assertTrue(width <= CONTENT_WIDTH_EMU, "image wider than the A4 20 mm content box: " + width);
        assertTrue(documentXml.contains("<w:u w:val=\"single\"/>"), "hyperlink runs must be underlined");
        assertTrue(documentXml.contains("<w:hyperlink "));
    }

    /** Font names and descriptor weights of the text positions that contain {@code needle}. */
    private static Map<String, Float> fontsFor(PDDocument pdf, String needle) throws IOException {
        var fonts = new LinkedHashMap<String, Float>();
        var stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) throws IOException {
                if (!text.contains(needle)) return;
                for (var position : positions) {
                    var descriptor = position.getFont().getFontDescriptor();
                    fonts.put(position.getFont().getName(), descriptor == null ? 0f : descriptor.getFontWeight());
                }
            }
        };
        stripper.setSortByPosition(true);
        stripper.getText(pdf);
        return fonts;
    }

    private static Map<String, String> zipEntries(byte[] docx) throws IOException {
        var entries = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory()) entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
