package com.example.file.service;

import com.example.file.entity.FileRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileServiceAccessTest {

    private final FileService fileService = new FileService(null, null, null, null);

    @Test
    @DisplayName("Anonymous users cannot access private files")
    void anonymousCannotAccessPrivateFile() {
        FileRecord record = FileRecord.builder()
                .fileKey("2026/06/13/a.png")
                .uploaderId(7L)
                .build();

        assertFalse(fileService.canAccess(record, null));
        assertFalse(fileService.canAccess(record, 0L));
    }

    @Test
    @DisplayName("Only uploader can access file")
    void onlyUploaderCanAccess() {
        FileRecord record = FileRecord.builder()
                .fileKey("2026/06/13/a.png")
                .uploaderId(7L)
                .build();

        assertTrue(fileService.canAccess(record, 7L));
        assertFalse(fileService.canAccess(record, 8L));
    }

    @Test
    @DisplayName("Legacy files without uploader are not public by default")
    void legacyUploaderZeroIsDenied() {
        assertFalse(fileService.canAccess(FileRecord.builder().uploaderId(0L).build(), 7L));
        assertFalse(fileService.canAccess(FileRecord.builder().uploaderId(null).build(), 7L));
    }
}
