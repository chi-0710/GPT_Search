package io.researchrunner.report;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrBase;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STLineSpacingRule;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

/** JVM report rendering. Raw HTML is escaped and rendering does not fetch external resources. */
public final class ReportRenderer {
    /** A4 width (595.28 pt) minus two 20 mm margins is 481.89 pt; keep content inside it. */
    static final int CONTENT_WIDTH_POINTS = 480;
    /** CSS px are converted to pt at 0.75, so 640 px == 480 pt: the same cap applies to PDF and DOCX. */
    private static final int MAX_IMAGE_WIDTH_PX = 640;
    private static final float BODY_FONT_POINTS = 11;
    private static final String FONT_FAMILY = "Noto Sans SC";
    private static final String LINK_COLOR = "1565C0";
    private static final String REGULAR_FONT = "/fonts/NotoSansSC.ttf";
    private static final String BOLD_FONT = "/fonts/NotoSansSC-Bold.ttf";
    /** A4 in twips (1/1440 inch): 210 mm x 297 mm. */
    private static final BigInteger A4_WIDTH_TWIPS = BigInteger.valueOf(11906);
    private static final BigInteger A4_HEIGHT_TWIPS = BigInteger.valueOf(16838);
    /** 20 mm in twips (1 mm = 56.6929 twips). */
    private static final BigInteger MARGIN_TWIPS = BigInteger.valueOf(1134);

    private final java.util.function.Function<java.util.UUID,byte[]> images;
    public ReportRenderer() { this(id->null); }
    public ReportRenderer(java.util.function.Function<java.util.UUID,byte[]> images) {this.images=images;}

    private org.jsoup.nodes.Document html(String markdown) {
        var extensions=List.of(TablesExtension.create());
        var parser=Parser.builder().extensions(extensions).build();
        String html=HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build().render(parser.parse(markdown));
        var document=Jsoup.parse(html);
        for(var image:document.select("img")) {
            String source=image.attr("src");
            if(!source.matches("/api/artifacts/[0-9a-fA-F-]{36}"))throw new IllegalArgumentException("导出图片必须使用已保存的附件");
            byte[] bytes=images.apply(java.util.UUID.fromString(source.substring(source.lastIndexOf('/')+1)));
            if(bytes==null)throw new IllegalArgumentException("导出图片不存在");
            try {
                var decoded=javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
                if(decoded==null)throw new IllegalArgumentException("导出图片格式无效");
                var png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(decoded,"png",png);
                image.attr("src","data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(png.toByteArray()));
                image.attr("width",Integer.toString(Math.min(MAX_IMAGE_WIDTH_PX,decoded.getWidth())));
            } catch(IOException e){throw new IllegalArgumentException("无法读取导出图片",e);}
        }
        return document;
    }

    public byte[] docx(String markdown) throws Exception {
        try(var document=new XWPFDocument();var output=new ByteArrayOutputStream()) {
            applyStyles(document);
            for(var block:html(markdown).body().children()) {
                if(block.tagName().equals("table")) {
                    var rows=block.select("tr");
                    int columns=rows.stream().mapToInt(row->row.childrenSize()).max().orElse(1);
                    var table=document.createTable(rows.size(),columns);
                    for(int r=0;r<rows.size();r++)for(int c=0;c<rows.get(r).childrenSize();c++)
                        inline(rows.get(r).child(c),table.getRow(r).getCell(c).getParagraphs().getFirst(),false,false,BODY_FONT_POINTS);
                } else if(block.tagName().equals("ul") || block.tagName().equals("ol")) {
                    int number=1;
                    for(var item:block.children()) {
                        var paragraph=document.createParagraph();
                        var marker=paragraph.createRun();format(marker,BODY_FONT_POINTS,false,false);
                        marker.setText(block.tagName().equals("ol")?number+++". ":"• ");
                        inline(item,paragraph,false,false,BODY_FONT_POINTS);
                    }
                } else {
                    var paragraph=document.createParagraph();
                    boolean heading=block.tagName().matches("h[1-6]");
                    if(heading)paragraph.setStyle("Heading"+block.tagName().substring(1));
                    inline(block,paragraph,heading,false,heading?headingPoints(block.tagName()):BODY_FONT_POINTS);
                }
            }
            applyPageSetup(document);
            document.write(output);return output.toByteArray();
        }
    }

    /**
     * Creates word/styles.xml explicitly so Heading1/2/3 keep their size, weight and outline level
     * in Word/WPS instead of relying on built-in defaults. Built through the XmlBeans typed model:
     * parsing a whole {@code w:styles} document and handing it to POI wraps it in a second root.
     */
    private static void applyStyles(XWPFDocument document) {
        var styles=CTStyles.Factory.newInstance();
        var defaults=styles.addNewDocDefaults();
        runDefaults(defaults.addNewRPrDefault().addNewRPr());
        spacing(defaults.addNewPPrDefault().addNewPPr(),0,120,360);
        var normal=styles.addNewStyle();
        normal.setType(STStyleType.PARAGRAPH);normal.setStyleId("Normal");normal.setDefault(Boolean.TRUE);
        normal.addNewName().setVal("Normal");normal.addNewQFormat();
        spacing(normal.addNewPPr(),0,120,360);
        runDefaults(normal.addNewRPr());
        heading(styles,"Heading1","heading 1",44,480,240,0);
        heading(styles,"Heading2","heading 2",33,360,180,1);
        heading(styles,"Heading3","heading 3",28,240,120,2);
        document.createStyles().setStyles(styles);
    }

    private static void heading(CTStyles styles,String styleId,String name,int halfPoints,int before,int after,int outlineLevel) {
        var style=styles.addNewStyle();
        style.setType(STStyleType.PARAGRAPH);style.setStyleId(styleId);
        style.addNewName().setVal(name);
        style.addNewBasedOn().setVal("Normal");style.addNewNext().setVal("Normal");
        style.addNewUiPriority().setVal(BigInteger.valueOf(9));
        style.addNewQFormat();
        var paragraph=style.addNewPPr();
        paragraph.addNewKeepNext();paragraph.addNewKeepLines();
        spacing(paragraph,before,after,240);
        paragraph.addNewOutlineLvl().setVal(BigInteger.valueOf(outlineLevel));
        var run=style.addNewRPr();
        fonts(run);
        run.addNewB();run.addNewBCs();
        run.addNewSz().setVal(BigInteger.valueOf(halfPoints));
        run.addNewSzCs().setVal(BigInteger.valueOf(halfPoints));
    }

    private static void runDefaults(CTRPr run) {
        fonts(run);
        run.addNewSz().setVal(BigInteger.valueOf(22));
        run.addNewSzCs().setVal(BigInteger.valueOf(22));
    }

    private static void fonts(CTRPr run) {
        var fonts=run.addNewRFonts();
        fonts.setAscii(FONT_FAMILY);fonts.setHAnsi(FONT_FAMILY);
        fonts.setEastAsia(FONT_FAMILY);fonts.setCs(FONT_FAMILY);
    }

    private static void spacing(CTPPrBase paragraph,int before,int after,int line) {
        var space=paragraph.addNewSpacing();
        space.setBefore(BigInteger.valueOf(before));space.setAfter(BigInteger.valueOf(after));
        space.setLine(BigInteger.valueOf(line));space.setLineRule(STLineSpacingRule.AUTO);
    }

    /** A4 page size and 20 mm margins (sectPr/w:pgSz/w:pgMar) instead of Word defaults. */
    private static void applyPageSetup(XWPFDocument document) {
        var body=document.getDocument().getBody();
        var section=body.getSectPr()==null?body.addNewSectPr():body.getSectPr();
        var size=section.getPgSz()==null?section.addNewPgSz():section.getPgSz();
        size.setW(A4_WIDTH_TWIPS);size.setH(A4_HEIGHT_TWIPS);
        var margin=section.getPgMar()==null?section.addNewPgMar():section.getPgMar();
        margin.setTop(MARGIN_TWIPS);margin.setBottom(MARGIN_TWIPS);
        margin.setLeft(MARGIN_TWIPS);margin.setRight(MARGIN_TWIPS);
        margin.setHeader(BigInteger.valueOf(720));margin.setFooter(BigInteger.valueOf(720));
    }

    private static float headingPoints(String tagName) {
        return switch(tagName) {
            case "h1" -> 22; case "h2" -> 16.5f; case "h3" -> 14; case "h4" -> 12; default -> 11;
        };
    }

    private static void format(XWPFRun run,float points,boolean bold,boolean italic) {
        run.setFontFamily(FONT_FAMILY);
        run.setFontFamily(FONT_FAMILY,XWPFRun.FontCharRange.eastAsia);
        run.setFontSize(points);run.setBold(bold);run.setItalic(italic);
    }

    private void inline(Node node,XWPFParagraph paragraph,boolean bold,boolean italic,float fontSize) {
        if(node instanceof TextNode text) {
            var run=paragraph.createRun();format(run,fontSize,bold,italic);
            run.setText(text.getWholeText());return;
        }
        if(node instanceof Element element) {
            if(element.tagName().equals("img")) {
                byte[] bytes=java.util.Base64.getDecoder().decode(element.attr("src").split(",",2)[1]);
                try {
                    var picture=javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
                    int width=Math.min(CONTENT_WIDTH_POINTS,(int)Math.round(picture.getWidth()*0.75));
                    int height=Math.max(1,(int)Math.round((double)picture.getHeight()*width/picture.getWidth()));
                    paragraph.createRun().addPicture(new ByteArrayInputStream(bytes),Document.PICTURE_TYPE_PNG,"image.png",org.apache.poi.util.Units.toEMU(width),org.apache.poi.util.Units.toEMU(height));
                } catch(Exception e){throw new IllegalStateException("无法嵌入图片",e);}return;
            }
            if(element.tagName().equals("br")){paragraph.createRun().addBreak();return;}
            if(element.tagName().equals("a")) {
                String target=element.attr("href");
                if(target.startsWith("https://") || target.startsWith("http://")) {
                    var run=paragraph.createHyperlinkRun(target);format(run,fontSize,bold,italic);
                    run.setColor(LINK_COLOR);run.setUnderline(UnderlinePatterns.SINGLE);run.setText(element.text());return;
                }
            }
            for(var child:element.childNodes())inline(child,paragraph,bold||element.tagName().equals("strong"),italic||element.tagName().equals("em"),fontSize);
        }
    }

    public byte[] pdf(String markdown) throws Exception {
        var document=html(markdown);
        document.head().appendElement("style").text(
                "@page {size:A4;margin:20mm;}"
                +" body {font-family:ResearchRunner;font-size:11pt;line-height:1.6;font-weight:400;}"
                +" h1,h2,h3,h4,h5,h6,strong {font-weight:700;}"
                +" table {border-collapse:collapse;width:100%;}"
                +" td,th {border:1px solid #888;padding:5px;}"
                +" pre {white-space:pre-wrap;}"
                +" img {max-width:100%;}"
                +" h1,h2,h3 {page-break-after:avoid;}");
        document.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml);
        byte[] rendered;
        try(var output=new ByteArrayOutputStream()) {
            var builder=new PdfRendererBuilder();
            builder.useFont(()->font(REGULAR_FONT),"ResearchRunner",400,FontStyle.NORMAL,true);
            builder.useFont(()->font(BOLD_FONT),"ResearchRunner",700,FontStyle.NORMAL,true);
            builder.withHtmlContent(document.html(),null);builder.toStream(output);builder.run();
            rendered=output.toByteArray();
        }
        return normalizeToUnicode(rendered);
    }

    private static InputStream font(String resource) {
        InputStream font=ReportRenderer.class.getResourceAsStream(resource);
        if(font==null)throw new IllegalStateException("缺少中文导出字体: "+resource);
        return font;
    }

    /**
     * Rewrites the text layer of a finished PDF so extraction/copy/search returns standard CJK
     * code points.
     *
     * <p>PDFBox builds each subset font's /ToUnicode CMap from the embedded glyph names, and the
     * bundled Noto Sans SC names glyphs after CJK radical code points: U+884C (行) is reachable
     * through the glyph named {@code uni2F8F}, and U+957F (长) is the glyph {@code uni2ED3}. A few
     * characters therefore extract as Kangxi radicals (U+2F00-U+2FD5, fixed by Unicode NFKC) or as
     * CJK Radicals Supplement characters (U+2E80-U+2EFF, no NFKC decomposition, fixed by the
     * font-derived {@code fonts/radical-map.tsv}). Every bfchar/bfrange destination string is
     * normalized and the CMap is written back as plain bfchar entries; the original header,
     * codespace range and trailer are preserved, and nothing changes when no mapping needs it.
     * This is a product fix, not a test workaround: the generated PDF itself extracts cleanly.
     */
    static byte[] normalizeToUnicode(byte[] pdfBytes) throws IOException {
        try(var document=Loader.loadPDF(pdfBytes);var output=new ByteArrayOutputStream()) {
            for(var page:document.getPages())
                for(var name:page.getResources().getFontNames())
                    normalizeToUnicode(document,page.getResources().getFont(name).getCOSObject());
            document.save(output);return output.toByteArray();
        }
    }

    private static void normalizeToUnicode(PDDocument document,COSDictionary font) throws IOException {
        COSBase target=font.getDictionaryObject(COSName.TO_UNICODE);
        if(target instanceof COSObject indirect)target=indirect.getObject();
        if(!(target instanceof COSStream stream))return;
        String cmap;
        try(var input=stream.createInputStream()){cmap=new String(input.readAllBytes(),StandardCharsets.ISO_8859_1);}
        var mappings=parseToUnicode(cmap);
        if(mappings.isEmpty())return;
        var normalized=new LinkedHashMap<String,String>();
        boolean changed=false;
        for(var entry:mappings.entrySet()) {
            String value=normalizeText(entry.getValue());
            changed|=!value.equals(entry.getValue());
            normalized.put(entry.getKey(),value);
        }
        if(!changed)return;
        String rebuilt=renderToUnicode(cmap,normalized);
        if(rebuilt==null)return;
        var replacement=document.getDocument().createCOSStream();
        try(var out=replacement.createOutputStream(COSName.FLATE_DECODE)){
            out.write(rebuilt.getBytes(StandardCharsets.ISO_8859_1));
        }
        font.setItem(COSName.TO_UNICODE,replacement);
    }

    /**
     * NFKC normalization plus the font-derived radical map. The map only contains radicals whose
     * glyph is also the glyph of a unified ideograph, so replacing them cannot change the rendering
     * semantics - it only makes the text layer searchable (e.g. U+2ED3 radical LONG becomes U+957F 长).
     */
    private static String normalizeText(String value) {
        String nfkc=Normalizer.normalize(value,Normalizer.Form.NFKC);
        var builder=new StringBuilder(nfkc.length());
        for(int index=0;index<nfkc.length();) {
            int codePoint=nfkc.codePointAt(index);
            Integer unified=RADICAL_TO_UNIFIED.get(codePoint);
            builder.appendCodePoint(unified==null?codePoint:unified);
            index+=Character.charCount(codePoint);
        }
        return builder.toString();
    }

    /** Radical -> unified ideograph map derived from the bundled fonts. */
    private static final Map<Integer,Integer> RADICAL_TO_UNIFIED=loadRadicalMap();

    private static Map<Integer,Integer> loadRadicalMap() {
        var map=new HashMap<Integer,Integer>();
        try(var input=ReportRenderer.class.getResourceAsStream("/fonts/radical-map.tsv")) {
            if(input==null)throw new IllegalStateException("缺少部首归一化映射: /fonts/radical-map.tsv");
            try(var lines=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
                for(String line;(line=lines.readLine())!=null;) {
                    line=line.strip();
                    if(line.isEmpty() || line.startsWith("#"))continue;
                    var parts=line.split("\\s+");
                    if(parts.length==2)map.put(Integer.parseInt(parts[0],16),Integer.parseInt(parts[1],16));
                }
            }
        } catch(IOException e){throw new IllegalStateException("无法读取部首归一化映射",e);}
        return Map.copyOf(map);
    }

    private static final Pattern BF_CHAR_PAIR=Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>");
    private static final Pattern BF_RANGE=Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(?:<([0-9A-Fa-f]+)>|\\[([^\\]]*)\\])");
    private static final Pattern BF_RANGE_ITEM=Pattern.compile("<([0-9A-Fa-f]+)>");

    /** Parses every bfchar/bfrange entry into a source-code -> Unicode string map. */
    private static Map<String,String> parseToUnicode(String cmap) {
        var mappings=new LinkedHashMap<String,String>();
        int cursor=0;
        while(true) {
            int charBlock=cmap.indexOf("beginbfchar",cursor);
            int rangeBlock=cmap.indexOf("beginbfrange",cursor);
            if(charBlock<0 && rangeBlock<0)break;
            boolean single=rangeBlock<0 || (charBlock>=0 && charBlock<rangeBlock);
            String begin=single?"beginbfchar":"beginbfrange", end=single?"endbfchar":"endbfrange";
            int start=single?charBlock:rangeBlock;
            int stop=cmap.indexOf(end,start+begin.length());
            if(stop<0)break;
            String body=cmap.substring(start+begin.length(),stop);
            if(single) {
                var matcher=BF_CHAR_PAIR.matcher(body);
                while(matcher.find())mapping(mappings,matcher.group(1),decodeUnits(matcher.group(2)));
            } else {
                var matcher=BF_RANGE.matcher(body);
                while(matcher.find())range(mappings,matcher.group(1),matcher.group(2),matcher.group(3),matcher.group(4));
            }
            cursor=stop+end.length();
        }
        return mappings;
    }

    private static void range(Map<String,String> mappings,String lowHex,String highHex,String destinationHex,String arrayBody) {
        int low=Integer.parseInt(lowHex,16), high=Integer.parseInt(highHex,16);
        if(high<low || high-low>0xFFFF)return;
        int digits=lowHex.length();
        if(arrayBody!=null) {
            var items=new ArrayList<int[]>();
            var matcher=BF_RANGE_ITEM.matcher(arrayBody);
            while(matcher.find())items.add(decodeUnits(matcher.group(1)));
            for(int offset=0;offset<=high-low && offset<items.size();offset++)
                mapping(mappings,code(low+offset,digits),items.get(offset));
            return;
        }
        int[] base=decodeUnits(destinationHex);
        if(base==null || base.length==0)return;
        for(int offset=0;offset<=high-low;offset++) {
            int[] units=base.clone();
            int last=units.length-1;
            if((long)units[last]+offset>0xFFFF)continue;
            units[last]+=offset;
            mapping(mappings,code(low+offset,digits),units);
        }
    }

    private static void mapping(Map<String,String> mappings,String sourceHex,int[] units) {
        if(units==null || units.length==0)return;
        mappings.putIfAbsent(sourceHex.toUpperCase(Locale.ROOT),new String(units,0,units.length));
    }

    /** Decodes a CMap destination string; returns UTF-16 code units, or null for unsupported forms. */
    private static int[] decodeUnits(String hex) {
        if(hex==null || hex.isEmpty())return null;
        if(hex.length()%4==0) {
            int[] units=new int[hex.length()/4];
            for(int index=0;index<units.length;index++)units[index]=Integer.parseInt(hex.substring(index*4,index*4+4),16);
            return units;
        }
        return null;
    }

    private static String code(int value,int digits) {
        return String.format("%0"+digits+"X",value);
    }

    /** Rebuilds the CMap using only bfchar blocks, preserving the original header and trailer. */
    private static String renderToUnicode(String cmap,Map<String,String> mappings) {
        int firstBlock=firstBlock(cmap);
        int endCmap=cmap.indexOf("endcmap");
        if(firstBlock<0 || endCmap<0 || endCmap<firstBlock)return null;
        var entries=new ArrayList<>(mappings.entrySet());
        entries.sort(Comparator.comparingInt(entry->Integer.parseInt(entry.getKey(),16)));
        var builder=new StringBuilder(cmap.substring(0,headerEnd(cmap,firstBlock)));
        for(int start=0;start<entries.size();start+=100) {
            int count=Math.min(100,entries.size()-start);
            builder.append(count).append(" beginbfchar\n");
            for(int index=start;index<start+count;index++) {
                var entry=entries.get(index);
                builder.append('<').append(entry.getKey()).append("> <");
                for(char unit:entry.getValue().toCharArray())builder.append(String.format("%04X",(int)unit));
                builder.append(">\n");
            }
            builder.append("endbfchar\n");
        }
        return builder.append(cmap.substring(endCmap)).toString();
    }

    /**
     * End of the preserved header: the first block's own "N beginbfchar" count operand is replaced
     * by the rebuilt blocks, otherwise the output would read "25 14 beginbfchar" (two operands).
     */
    private static int headerEnd(String cmap,int blockKeywordIndex) {
        int index=blockKeywordIndex;
        while(index>0 && Character.isWhitespace(cmap.charAt(index-1)))index--;
        int digitsEnd=index;
        while(index>0 && Character.isDigit(cmap.charAt(index-1)))index--;
        return index<digitsEnd?index:blockKeywordIndex;
    }

    private static int firstBlock(String cmap) {
        int single=cmap.indexOf("beginbfchar"), range=cmap.indexOf("beginbfrange");
        if(single<0)return range;
        if(range<0)return single;
        return Math.min(single,range);
    }
}
