package com.scenemind.server.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualCaptionServiceTest {

    private HttpServer server;
    private String baseUrl;
    private volatile String lastRequestBody = null;
    private volatile String lastAuthHeader = null;
    private volatile int respondStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastAuthHeader = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body;
            if (respondStatus == 200) {
                body = "{\"choices\":[{\"message\":{\"content\":\"讲师在白板前讲解前序遍历流程图\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                body = "{\"error\":\"model overloaded\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(respondStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private Path tinyJpeg() throws IOException {
        // 任意非空字节即可：测试里服务端不真正解码图片
        Path path = Files.createTempFile("frame", ".jpg");
        Files.write(path, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3});
        return path;
    }

    @Test
    void disabledServiceReturnsNullWithoutNetwork() throws IOException {
        VisualCaptionService service = new VisualCaptionService(false, "model", "key", baseUrl);
        assertNull(service.caption(tinyJpeg().toFile()));
        assertNull(lastRequestBody, "禁用时不应发起任何网络请求");
    }

    @Test
    void enabledServiceReturnsCaptionTextFromVisionApi() throws IOException {
        VisualCaptionService service = new VisualCaptionService(true, "Qwen/Qwen2.5-VL", "secret", baseUrl);
        assertEquals("讲师在白板前讲解前序遍历流程图", service.caption(tinyJpeg().toFile()));
        assertEquals("Bearer secret", lastAuthHeader);
        assertTrue(lastRequestBody != null && lastRequestBody.contains("image_url"));
        assertTrue(lastRequestBody != null && lastRequestBody.contains("data:image/jpeg;base64,"));
    }

    @Test
    void visionApiFailureReturnsNull() throws IOException {
        respondStatus = 500;
        VisualCaptionService service = new VisualCaptionService(true, "Qwen/Qwen2.5-VL", "secret", baseUrl);
        assertNull(service.caption(tinyJpeg().toFile()));
    }

    @Test
    void missingCredentialsReturnsNull() throws IOException {
        VisualCaptionService service = new VisualCaptionService(true, "Qwen/Qwen2.5-VL", "", baseUrl);
        assertNull(service.caption(tinyJpeg().toFile()));
        assertNull(lastRequestBody, "缺少 API Key 时不应发起网络请求");
    }
}
