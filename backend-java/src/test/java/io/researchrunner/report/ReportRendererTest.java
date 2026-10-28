package io.researchrunner.report;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import static org.junit.jupiter.api.Assertions.*;

class ReportRendererTest {
    private static final long CONTENT_WIDTH_EMU=6_096_000;
    /** 501 distinct common characters: >100 ToUnicode entries, multi-block CMap rebuild. */
    private static final String LONG_CHINESE="的一是在不了有和人这中大为上个国我以要他时来用们生到作地于出就分对成会可主发年动同工也能下过子说产种面而方后多定行学法所民得经十三之进着等部度家电力里如水化高自二理起小物现实加量都两体制机当使点从业本去把性好应开它合还因由其些然前外天政四日那社义事平形相全表间样与关各重新线内数正心反你明看原又么利比或但质气第向道命此变条只没结解问意建月公无系军很情者最立代想已通并提直题党程展五果料象员革位入常文总次品式活设及管特件长求老头基资边流路级少图山统接知较将组见计别她手角期根论运农指几九区强放决西被干做必战先回则任取据处队南给色光门即保治北造百规热领七海口东导器压志世金增争济阶油思术极交受联什认六共权收证改清己美再采转更单风切打白教速花带安场身车例真务具万每目至达走积示议声报斗完类八离华名确才科张信马节话米整空元况今集温传土许步群广石记需段研界拉林律叫且究观越织装影算低持音众书布复容儿须际商非验连断深难近矿千周委素技备半办青省列习响约支般史感劳便团往酸历市克何除消构府称太准精值号率族维划选标写存候毛亲快效斯院查江型眼王按格养易置派层片始却专状育厂京识适属圆包火住调满县局照参红细引听该铁价严龙飞";
    @Test void embedsStoredImageInBothFormatsAndRejectsExternalImage() throws Exception {
        var pixels=new java.awt.image.BufferedImage(4000,2250,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var buffer=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(pixels,"png",buffer);
        var renderer=new ReportRenderer(id->buffer.toByteArray());
        String markdown="![图示](/api/artifacts/"+java.util.UUID.randomUUID()+")";
        byte[] docx=renderer.docx(markdown);
        try(var document=new XWPFDocument(new ByteArrayInputStream(docx))){assertEquals(1,document.getAllPictures().size());}
        var extent=Pattern.compile("<wp:extent cx=\"(\\d+)\" cy=\"(\\d+)\"/>").matcher(zipEntries(docx).get("word/document.xml"));
        assertTrue(extent.find(),"picture extent missing");
        assertEquals(CONTENT_WIDTH_EMU,Long.parseLong(extent.group(1)),"wide image must be capped to the A4 content width");
        assertEquals(3_429_000L,Long.parseLong(extent.group(2)));
        try(var pdf=Loader.loadPDF(renderer.pdf(markdown))){assertTrue(pdf.getPage(0).getResources().getXObjectNames().iterator().hasNext());}
        assertThrows(IllegalArgumentException.class,()->renderer.pdf("![外部](https://example.com/image.png)"));
    }
    private final String markdown="# 知行研究助手\n\n中文研究正文与[来源](https://example.com)。\n\n| 项目 | 结果 |\n| --- | --- |\n| 资料 | 通过 |\n";
    @Test void docxPreservesChineseTablesAndHyperlinks() throws Exception {
        byte[] docx=new ReportRenderer().docx(markdown);
        try(var document=new XWPFDocument(new ByteArrayInputStream(docx))) {
            assertTrue(document.getParagraphs().stream().anyMatch(p->p.getText().contains("中文研究正文")));
            assertEquals("资料",document.getTables().getFirst().getRow(1).getCell(0).getText());
            assertTrue(document.getHyperlinks().length>0);
        }
        String documentXml=zipEntries(docx).get("word/document.xml");
        assertTrue(documentXml.contains("<w:hyperlink "));
        assertTrue(documentXml.contains("<w:u w:val=\"single\"/>"),"hyperlink run must be underlined");
    }
    @Test void docxWritesExplicitStylesAndA4PageSetup() throws Exception {
        var entries=zipEntries(new ReportRenderer().docx(markdown));
        String styles=entries.get("word/styles.xml");
        assertNotNull(styles,"word/styles.xml must be created explicitly");
        assertEquals(1,styles.split("<w:styles",-1).length-1,"styles.xml must have exactly one root element");
        for(String styleId:List.of("Normal","Heading1","Heading2","Heading3"))assertTrue(styles.contains("w:styleId=\""+styleId+"\""),styleId);
        for(int outlineLevel=0;outlineLevel<3;outlineLevel++)assertTrue(styles.contains("<w:outlineLvl w:val=\""+outlineLevel+"\"/>"),"outline "+outlineLevel);
        assertTrue(styles.contains("<w:b/>"),"heading styles must be bold");
        assertTrue(styles.contains("<w:sz w:val=\"44\"/>"));assertTrue(styles.contains("<w:sz w:val=\"33\"/>"));assertTrue(styles.contains("<w:sz w:val=\"28\"/>"));
        assertTrue(styles.contains("<w:spacing w:before=\"480\" w:after=\"240\""));
        String documentXml=entries.get("word/document.xml");
        assertTrue(documentXml.contains("<w:sectPr>"));
        assertTrue(documentXml.contains("<w:pgSz w:w=\"11906\" w:h=\"16838\"/>"),"A4 page size");
        assertTrue(documentXml.contains("<w:pgMar w:top=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:right=\"1134\""),"20 mm margins");
        assertTrue(documentXml.contains("<w:pStyle w:val=\"Heading1\"/>"));
    }
    @Test void pdfContainsActualChineseTextWithoutCompatibilityCodePoints() throws Exception {
        try(var document=Loader.loadPDF(new ReportRenderer().pdf(markdown))) {
            String text=new PDFTextStripper().getText(document);
            assertTrue(text.contains("知行研究助手"),text);assertTrue(text.contains("资料"),text);assertTrue(text.contains("中文研究正文"),text);
            assertTrue(text.chars().noneMatch(c->c>=0x2E80&&c<=0x2FDF),"PDF text layer uses Kangxi radicals: "+text);
            assertEquals(text,java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC));
        }
    }
    @Test void pdfMarksHeadingsBoldAndBodyRegular() throws Exception {
        try(var document=Loader.loadPDF(new ReportRenderer().pdf(markdown))) {
            var heading=fontsFor(document,"知行研究助手");
            assertFalse(heading.isEmpty());
            heading.forEach((name,weight)->{assertTrue(name.contains("Bold"),name);assertTrue(weight>=700f,name+"="+weight);});
            var body=fontsFor(document,"中文研究正文");
            assertFalse(body.isEmpty());
            assertTrue(body.keySet().stream().noneMatch(name->name.contains("Bold")),body.keySet().toString());
        }
    }
    /** A report with many distinct characters exercises >100 ToUnicode mappings and multi-block rebuilds. */
    @Test void pdfTextLayerStaysCleanForLargeChineseContent() throws Exception {
        String markdown="# 长文本测试\n\n"+LONG_CHINESE+"\n";
        try(var document=Loader.loadPDF(new ReportRenderer().pdf(markdown))) {
            String text=new PDFTextStripper().getText(document).replaceAll("\\s+","");
            assertTrue(text.chars().noneMatch(c->c>=0x2E80&&c<=0x2FDF),"PDF text layer uses Kangxi radicals");
            assertEquals(text,java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC));
            assertTrue(text.contains(LONG_CHINESE),()->"extracted text is incomplete: "+text.length()+" of "+LONG_CHINESE.length());
        }
    }
    /** The rebuilt /ToUnicode CMaps must declare their real entry count and carry no duplicate operand. */
    @Test void pdfToUnicodeCmapsDeclareExactEntryCounts() throws Exception {
        assertWellFormedToUnicode(new ReportRenderer().pdf(markdown));
        assertWellFormedToUnicode(new ReportRenderer().pdf("# 长文本测试\n\n"+LONG_CHINESE+"\n"));
    }
    private static void assertWellFormedToUnicode(byte[] pdf) throws IOException {
        int blocks=0;
        try(var document=Loader.loadPDF(pdf)) {
            for(var page:document.getPages())
                for(var name:page.getResources().getFontNames()) {
                    var font=page.getResources().getFont(name);
                    var target=font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE);
                    if(target instanceof COSObject indirect)target=indirect.getObject();
                    if(!(target instanceof COSStream stream))continue;
                    String cmap;
                    try(var in=stream.createInputStream()){cmap=new String(in.readAllBytes(),StandardCharsets.ISO_8859_1);}
                    assertFalse(Pattern.compile("\\d+\\s+\\d+\\s+beginbfchar").matcher(cmap).find(),"duplicate bfchar count operand");
                    var block=Pattern.compile("(\\d+)\\s+beginbfchar\\n(.*?)endbfchar",Pattern.DOTALL).matcher(cmap);
                    while(block.find()) {
                        int declared=Integer.parseInt(block.group(1));
                        var entry=Pattern.compile("<[0-9A-Fa-f]+>\\s*<[0-9A-Fa-f]+>").matcher(block.group(2));
                        int actual=0;while(entry.find())actual++;
                        assertEquals(declared,actual,"bfchar block declares "+declared+" entries but has "+actual);
                        blocks++;
                    }
                }
        }
        assertTrue(blocks>0,"expected rewritten bfchar blocks");
    }
    private static Map<String,Float> fontsFor(PDDocument document,String needle) throws IOException {
        var fonts=new LinkedHashMap<String,Float>();
        var stripper=new PDFTextStripper(){
            @Override protected void writeString(String text,List<TextPosition> positions) throws IOException {
                if(!text.contains(needle))return;
                for(var position:positions) {
                    var descriptor=position.getFont().getFontDescriptor();
                    fonts.put(position.getFont().getName(),descriptor==null?0f:descriptor.getFontWeight());
                }
            }
        };
        stripper.setSortByPosition(true);stripper.getText(document);return fonts;
    }
    private static Map<String,String> zipEntries(byte[] docx) throws IOException {
        var entries=new LinkedHashMap<String,String>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(docx))) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())
                if(!entry.isDirectory())entries.put(entry.getName(),new String(zip.readAllBytes(),StandardCharsets.UTF_8));
        }
        return entries;
    }
}
