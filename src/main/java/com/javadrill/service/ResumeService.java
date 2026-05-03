package com.javadrill.service;

import com.javadrill.dto.Dto;
import com.javadrill.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private final UserRepository userRepository;
    private static final int MAX_RESUME_CHARS = 12_000;

    public Dto.ResumeUploadResponse uploadResume(String uid, MultipartFile file) {
        if (file.isEmpty()) {
            throw new RuntimeException("File is empty. Please upload a valid PDF or TXT file.");
        }

        long fileSizeKb = file.getSize() / 1024;
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new RuntimeException("File too large. Maximum size is 5MB.");
        }

        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "resume";
        String contentType = file.getContentType() != null ? file.getContentType() : "";

        log.info("Resume upload for user {}: {} ({} KB, {})", uid, fileName, fileSizeKb, contentType);

        String text;
        try {
            boolean isPdf = contentType.contains("pdf") || fileName.toLowerCase().endsWith(".pdf");
            boolean isTxt = contentType.contains("text") || fileName.toLowerCase().endsWith(".txt");
            boolean isDoc = fileName.toLowerCase().endsWith(".doc") || fileName.toLowerCase().endsWith(".docx");

            if (isPdf) {
                text = extractFromPdf(file);
            } else if (isTxt) {
                text = new String(file.getBytes());
            } else if (isDoc) {
                // Try to extract text from DOC/DOCX as raw bytes (limited support)
                // For proper DOCX support, add Apache POI dependency
                text = extractDocxText(file);
            } else {
                // Try as plain text fallback
                text = new String(file.getBytes());
            }
        } catch (IOException e) {
            log.error("Resume extraction failed for user {}: {}", uid, e.getMessage());
            throw new RuntimeException("Could not read file: " + e.getMessage()
                    + ". Please upload a text-based PDF or TXT file.");
        }

        // Sanitize control characters
        text = text.replaceAll("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]", " ")
                   .replaceAll(" +", " ")
                   .trim();

        if (text.length() < 50) {
            throw new RuntimeException(
                "Could not extract enough text from your resume. " +
                "Please ensure your PDF is not scanned/image-based, or upload a TXT version.");
        }

        // Truncate if too long
        if (text.length() > MAX_RESUME_CHARS) {
            text = text.substring(0, MAX_RESUME_CHARS);
            log.debug("Resume truncated to {} chars for user {}", MAX_RESUME_CHARS, uid);
        }

        // Save to Firestore (clears cached AI summary so it gets re-generated)
        userRepository.updateResume(uid, text, fileName);
        log.info("Resume saved for user {} ({} chars, {} KB)", uid, text.length(), fileSizeKb);

        return Dto.ResumeUploadResponse.builder()
                .success(true)
                .charCount(text.length())
                .fileName(fileName)
                .message("Resume uploaded successfully! Your profile is ready for interviews.")
                .build();
    }

    private String extractFromPdf(MultipartFile file) throws IOException {
        try {
            byte[] bytes = file.getInputStream().readAllBytes();
            try (var doc = Loader.loadPDF(bytes)) {
                var stripper = new PDFTextStripper();
                stripper.setSortByPosition(true); // better text order
                String text = stripper.getText(doc);
                log.debug("PDF extraction: {} pages, {} chars", doc.getNumberOfPages(), text.length());
                return text;
            }
        } catch (Exception e) {
            log.warn("PDFBox extraction failed: {}. Trying raw text.", e.getMessage());
            // Fallback: try reading as plain text
            return new String(file.getBytes());
        }
    }

    private String extractDocxText(MultipartFile file) throws IOException {
        // Basic DOCX text extraction (DOCX is a ZIP of XML files)
        // For production, add Apache POI: poi-ooxml dependency
        try {
            // Try reading as UTF-8 text (works for .txt saved as .docx)
            String raw = new String(file.getBytes());
            // Remove XML tags if present
            raw = raw.replaceAll("<[^>]+>", " ").replaceAll("&[a-z]+;", " ");
            return raw;
        } catch (Exception e) {
            throw new RuntimeException("DOCX parsing failed. Please save your resume as PDF or TXT.");
        }
    }
}
