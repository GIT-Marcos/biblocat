package com.biblocat.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FileFormatTest {

    @Test
    void fromExtension_pdfLowercase() {
        assertEquals(FileFormat.PDF, FileFormat.fromExtension(".pdf"));
    }

    @Test
    void fromExtension_pdfUppercase() {
        assertEquals(FileFormat.PDF, FileFormat.fromExtension(".PDF"));
    }

    @Test
    void fromExtension_pdfMixedCase() {
        assertEquals(FileFormat.PDF, FileFormat.fromExtension(".Pdf"));
    }

    @Test
    void fromExtension_epub() {
        assertEquals(FileFormat.EPUB, FileFormat.fromExtension(".ePUB"));
    }

    @Test
    void fromExtension_epubLowercase() {
        assertEquals(FileFormat.EPUB, FileFormat.fromExtension(".epub"));
    }

    @Test
    void fromExtension_mhtml() {
        assertEquals(FileFormat.MHTML, FileFormat.fromExtension(".MHTML"));
    }

    @Test
    void fromExtension_mhtmlLowercase() {
        assertEquals(FileFormat.MHTML, FileFormat.fromExtension(".mhtml"));
    }

    @Test
    void fromExtension_unsupported() {
        assertNull(FileFormat.fromExtension(".txt"));
        assertNull(FileFormat.fromExtension(".doc"));
        assertNull(FileFormat.fromExtension(".pdfx"));
    }

    @Test
    void fromExtension_noExtension() {
        assertNull(FileFormat.fromExtension("Makefile"));
    }

    @Test
    void fromExtension_emptyString() {
        assertNull(FileFormat.fromExtension(""));
    }
}
