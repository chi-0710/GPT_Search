package io.researchrunner.document;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {
    private final DocumentParser parser=new DocumentParser();
    @Test void parsesActualOfficeFilesAndKeepsChinese() throws Exception {
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("中文研究资料");doc.write(out);
            assertTrue(parser.parse("test.docx",out.toByteArray()).getFirst().content().contains("中文研究资料"));
        }
        try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            workbook.createSheet("数据").createRow(0).createCell(0).setCellValue("市场规模");workbook.write(out);
            assertTrue(parser.parse("test.xlsx",out.toByteArray()).getFirst().content().contains("市场规模"));
        }
        try(var slides=new XMLSlideShow();var out=new ByteArrayOutputStream()) {
            slides.createSlide().createTextBox().setText("中文演示资料");slides.write(out);
            var result=parser.parse("test.pptx",out.toByteArray());
            assertTrue(result.getFirst().content().contains("中文演示资料"));assertEquals(1,result.getFirst().page());
        }
    }
    @Test void pdfPreservesPageNumbersAndEmptyPdfRequiresOcr() throws Exception {
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            var page=new PDPage();pdf.addPage(page);
            try(var content=new PDPageContentStream(pdf,page)) {
                content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                content.newLineAtOffset(50,700);content.showText("Research evidence");content.endText();
            }
            pdf.save(out);var result=parser.parse("paper.pdf",out.toByteArray());
            assertEquals(1,result.getFirst().page());assertTrue(result.getFirst().content().contains("Research evidence"));
        }
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());pdf.save(out);
            assertTrue(assertThrows(IllegalArgumentException.class,()->parser.parse("scan.pdf",out.toByteArray())).getMessage().contains("OCR"));
        }
    }
    @Test void splitRespectsCodePointsOverlapAndMetadata() throws Exception {
        var parts=parser.split(List.of(new DocumentParser.Part("😀甲乙丙丁戊己庚",3)),4,1);
        assertEquals("😀甲乙丙",parts.getFirst().content());assertEquals("丙丁戊己",parts.get(1).content());
        assertTrue(parts.stream().allMatch(part -> part.page()==3));
        assertThrows(IllegalArgumentException.class,()->parser.split(parts,4,4));
        var html=parser.parse("index.html","<p>证据</p><script>secret()</script>".getBytes(StandardCharsets.UTF_8));
        assertEquals("证据",html.getFirst().content());
        assertThrows(IllegalArgumentException.class,()->parser.parse("bad.exe",new byte[]{1}));
    }
}
