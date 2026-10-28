package io.researchrunner.document;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;

/** JVM-only parsers; no shell commands or persistent upload paths. */
public final class DocumentParser {
    public static final Set<String> FORMATS = Set.of("pdf", "txt", "doc", "docx", "pptx", "csv", "xls", "xlsx", "md", "html", "htm");
    public record Part(String content, Integer page) { }

    public List<Part> parse(String filename, byte[] bytes) throws Exception {
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(extension)) throw new IllegalArgumentException("不支持的文件格式：" + extension);
        List<Part> parts = new ArrayList<>();
        try (var input = new ByteArrayInputStream(bytes)) {
            switch (extension) {
                case "pdf" -> {
                    try (var pdf = Loader.loadPDF(bytes)) {
                        var stripper = new PDFTextStripper();
                        for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                            stripper.setStartPage(page);
                            stripper.setEndPage(page);
                            parts.add(new Part(stripper.getText(pdf), page));
                        }
                    }
                }
                case "docx" -> {
                    try (var document = new XWPFDocument(input); var extractor = new XWPFWordExtractor(document)) {
                        parts.add(new Part(extractor.getText(), null));
                    }
                }
                case "doc" -> {
                    try (var document = new HWPFDocument(input); var extractor = new WordExtractor(document)) {
                        parts.add(new Part(extractor.getText(), null));
                    }
                }
                case "pptx" -> {
                    try (var presentation = new XMLSlideShow(input)) {
                        for (var slide : presentation.getSlides()) {
                            var text = new StringBuilder();
                            for (var shape : slide.getShapes()) if (shape instanceof XSLFTextShape block) text.append(block.getText()).append('\n');
                            parts.add(new Part(text.toString(), slide.getSlideNumber()));
                        }
                    }
                }
                case "xls", "xlsx" -> {
                    try (var workbook = WorkbookFactory.create(input)) {
                        var format = new DataFormatter(Locale.ROOT);
                        for (var sheet : workbook) {
                            var text = new StringBuilder(sheet.getSheetName()).append('\n');
                            for (var row : sheet) {
                                for (var cell : row) text.append(format.formatCellValue(cell)).append('\t');
                                text.append('\n');
                            }
                            parts.add(new Part(text.toString(), null));
                        }
                    }
                }
                default -> {
                    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().replaceFirst("^\\uFEFF", "");
                    if (extension.equals("html") || extension.equals("htm")) {
                        var html = Jsoup.parse(text);
                        html.select("script,style,noscript").remove();
                        text = html.text();
                    }
                    parts.add(new Part(text, null));
                }
            }
        }
        List<Part> usable = parts.stream().filter(part -> !part.content().isBlank()).toList();
        if (usable.isEmpty()) throw new IllegalArgumentException("未提取到文本；扫描版 PDF 需要 OCR，本版本暂不支持。");
        return usable;
    }

    /** Code-point-safe chunks with paragraph/line/word boundaries and bounded overlap. */
    public List<Part> split(List<Part> parts, int size, int overlap) {
        if (size < 1 || overlap < 0 || overlap >= size) throw new IllegalArgumentException("分块参数无效");
        var splitter = new TextSplitter(size, overlap);
        var result = new ArrayList<Part>();
        for (Part part : parts) {
            for (String chunk : splitter.split(part.content())) result.add(new Part(chunk, part.page()));
        }
        return result;
    }
}
