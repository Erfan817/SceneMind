package com.scenemind.server.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinioUtilsTest {

    @Test
    void infersVideoMimeWhenClientSendsOctetStream() {
        assertEquals("video/mp4", MinioUtils.resolveContentType("a.mp4", "application/octet-stream"));
        assertEquals("video/mp4", MinioUtils.resolveContentType("a.M4V", null));
        assertEquals("video/quicktime", MinioUtils.resolveContentType("a.mov", null));
        assertEquals("video/webm", MinioUtils.resolveContentType("a.webm", null));
        assertEquals("video/x-matroska", MinioUtils.resolveContentType("a.mkv", null));
        assertEquals("video/x-msvideo", MinioUtils.resolveContentType("a.avi", null));
    }

    @Test
    void keepsDeclaredMimeAndFallsBackForUnknownExtensions() {
        assertEquals("video/mp4", MinioUtils.resolveContentType("a.mp4", "video/mp4"));
        assertEquals("application/octet-stream", MinioUtils.resolveContentType("a.bin", null));
    }
}
