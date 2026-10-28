package io.researchrunner.report;

import java.io.*;
import java.util.List;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.apache.poi.xwpf.usermodel.*;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

/** JVM report rendering. Raw HTML is escaped and rendering does not fetch external resources. */
public final class ReportRenderer {
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
                image.attr("width",Integer.toString(Math.min(500,decoded.getWidth())));
            } catch(IOException e){throw new IllegalArgumentException("无法读取导出图片",e);}
        }
        return document;
    }

    public byte[] docx(String markdown) throws Exception {
        try(var document=new XWPFDocument();var output=new ByteArrayOutputStream()) {
            for(var block:html(markdown).body().children()) {
                if(block.tagName().equals("table")) {
                    var rows=block.select("tr");
                    int columns=rows.stream().mapToInt(row->row.childrenSize()).max().orElse(1);
                    var table=document.createTable(rows.size(),columns);
                    for(int r=0;r<rows.size();r++)for(int c=0;c<rows.get(r).childrenSize();c++)
                        inline(rows.get(r).child(c),table.getRow(r).getCell(c).getParagraphs().getFirst(),false,false);
                } else if(block.tagName().equals("ul") || block.tagName().equals("ol")) {
                    int number=1;
                    for(var item:block.children()) {
                        var paragraph=document.createParagraph();paragraph.createRun().setText(block.tagName().equals("ol")?number+++". ":"• ");
                        inline(item,paragraph,false,false);
                    }
                } else {
                    var paragraph=document.createParagraph();
                    if(block.tagName().matches("h[1-6]"))paragraph.setStyle("Heading"+block.tagName().substring(1));
                    inline(block,paragraph,block.tagName().matches("h[1-6]"),false);
                }
            }
            document.write(output);return output.toByteArray();
        }
    }

    private void inline(Node node,XWPFParagraph paragraph,boolean bold,boolean italic) {
        if(node instanceof TextNode text) {
            var run=paragraph.createRun();run.setFontFamily("Noto Sans SC");run.setFontSize(11);
            run.setBold(bold);run.setItalic(italic);run.setText(text.getWholeText());return;
        }
        if(node instanceof Element element) {
            if(element.tagName().equals("img")) {
                byte[] bytes=java.util.Base64.getDecoder().decode(element.attr("src").split(",",2)[1]);
                try {
                    var picture=javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
                    int width=Math.min(500,picture.getWidth());int height=Math.max(1,(int)((long)picture.getHeight()*width/picture.getWidth()));
                    paragraph.createRun().addPicture(new ByteArrayInputStream(bytes),Document.PICTURE_TYPE_PNG,"image.png",org.apache.poi.util.Units.toEMU(width),org.apache.poi.util.Units.toEMU(height));
                } catch(Exception e){throw new IllegalStateException("无法嵌入图片",e);}return;
            }
            if(element.tagName().equals("br")){paragraph.createRun().addBreak();return;}
            if(element.tagName().equals("a")) {
                String target=element.attr("href");
                if(target.startsWith("https://") || target.startsWith("http://")) {
                    var run=paragraph.createHyperlinkRun(target);run.setText(element.text());run.setColor("1565C0");return;
                }
            }
            for(var child:element.childNodes())inline(child,paragraph,bold||element.tagName().equals("strong"),italic||element.tagName().equals("em"));
        }
    }

    public byte[] pdf(String markdown) throws Exception {
        var document=html(markdown);
        document.head().appendElement("style").text("@page {size:A4;margin:20mm;} body {font-family:ResearchRunner;font-size:11pt;line-height:1.6;} table {border-collapse:collapse;width:100%;} td,th {border:1px solid #888;padding:5px;} pre {white-space:pre-wrap;} h1,h2,h3 {page-break-after:avoid;}");
        document.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml);
        try(var output=new ByteArrayOutputStream()) {
            var builder=new PdfRendererBuilder();
            builder.useFont(()->{
                InputStream font=ReportRenderer.class.getResourceAsStream("/fonts/NotoSansSC.ttf");
                if(font==null)throw new IllegalStateException("缺少中文导出字体");return font;
            },"ResearchRunner");
            builder.withHtmlContent(document.html(),null);builder.toStream(output);builder.run();return output.toByteArray();
        }
    }
}
