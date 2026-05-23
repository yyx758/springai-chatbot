package com.example.file.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
@Slf4j
public class KnowledgeDocParser {

    public String parse(InputStream data, String contentType, String fileName) {
        String lower = fileName.toLowerCase();
        try {
            if (contentType != null && contentType.contains("pdf") || lower.endsWith(".pdf")) {
                return parsePdf(data);
            }
            if (lower.endsWith(".docx")) {
                return parseDocx(data);
            }
            if (contentType != null && contentType.startsWith("text/") || lower.endsWith(".txt") || lower.endsWith(".md")) {
                return parseText(data);
            }
            return parseText(data);
        } catch (Exception e) {
            log.error("【文档解析】解析失败: {}", fileName, e);
            throw new RuntimeException("文档解析失败: " + e.getMessage());
        }
    }

    private String parsePdf(InputStream data) throws Exception {
        try (PDDocument document = Loader.loadPDF(data.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            log.info("【文档解析】PDF 解析完成，共 {} 字符", text.length());
            return text;
        }
    }

    private String parseDocx(InputStream data) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(data);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = extractor.getText();
            log.info("【文档解析】DOCX 解析完成，共 {} 字符", text.length());
            return text;
        }
    }

    private String parseText(InputStream data) throws Exception {
        String text = new String(data.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        log.info("【文档解析】TXT 解析完成，共 {} 字符", text.length());
        return text;
    }

    public boolean isSupported(String contentType, String fileName) {
        String lower = fileName.toLowerCase();
        if (contentType != null && contentType.contains("pdf")) return true;
        if (contentType != null && contentType.startsWith("text/")) return true;
        if (lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".txt") || lower.endsWith(".md")) return true;
        return false;
    }
}
