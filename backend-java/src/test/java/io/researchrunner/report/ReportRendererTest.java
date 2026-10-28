package io.researchrunner.report;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import static org.junit.jupiter.api.Assertions.*;

class ReportRendererTest {
    private final String markdown="# 知行研究助手\n\n中文研究正文与[来源](https://example.com)。\n\n| 项目 | 结果 |\n| --- | --- |\n| 资料 | 通过 |\n";
    @Test void docxPreservesChineseTablesAndHyperlinks() throws Exception {
        try(var document=new XWPFDocument(new ByteArrayInputStream(new ReportRenderer().docx(markdown)))) {
            assertTrue(document.getParagraphs().stream().anyMatch(p->p.getText().contains("中文研究正文")));
            assertEquals("资料",document.getTables().getFirst().getRow(1).getCell(0).getText());
            assertTrue(document.getHyperlinks().length>0);
        }
    }
    @Test void pdfContainsActualChineseText() throws Exception {
        try(var document=Loader.loadPDF(new ReportRenderer().pdf(markdown))) {
            String text=java.text.Normalizer.normalize(new PDFTextStripper().getText(document),java.text.Normalizer.Form.NFKC);
            assertTrue(text.contains("知行研究助手"),text);assertTrue(text.contains("资料"),text);
        }
    }
}

