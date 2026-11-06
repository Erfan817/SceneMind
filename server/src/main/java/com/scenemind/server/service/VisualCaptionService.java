package com.scenemind.server.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 关键帧 VLM 画面描述（视觉语义通道的召回来源）。
 *
 * <p>OCR 只能读到画面里的文字；PPT 图表趋势、演示动作、产品外观这类"没有字的画面"
 * 需要视觉模型描述出来，检索层才能命中。本组件对保留的关键帧调用 OpenAI 兼容的
 * 视觉接口，产出短的事实性中文描述。</p>
 *
 * <p><b>边界</b>：描述文本只参与<b>召回</b>（检索），绝不进入 {@code EvidenceAuditor}
 * 的可引用来源（仍只有 ASR / OCR / ASR+OCR）。描述可能不精确，不能当作逐字证据。</p>
 *
 * <p>默认关闭（{@code scenemind.vision.enabled=false}），未配置时返回 {@code null}，
 * 整条 OCR/上下文分支不会因为视觉服务不可用而失败。</p>
 */
@Component
public class VisualCaptionService {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    private final boolean enabled;
    private final String model;
    private final String apiKey;
    private final String baseUrl;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public VisualCaptionService(@Value("${scenemind.vision.enabled:false}") boolean enabled,
                                @Value("${scenemind.vision.model:Qwen/Qwen2.5-VL-72B-Instruct}") String model,
                                @Value("${ai.deepseek.api-key:}") String apiKey,
                                @Value("${ai.deepseek.base-url:}") String baseUrl) {
        this.enabled = enabled;
        this.model = model == null || model.isBlank() ? "Qwen/Qwen2.5-VL-72B-Instruct" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "");
    }

    /** 是否启用（供调用方在批量处理前决定是否值得做预算裁剪）。 */
    public boolean enabled() {
        return enabled && !apiKey.isBlank() && !baseUrl.isBlank();
    }

    /**
     * 对一帧画面生成一句简短的事实描述；不可用时返回 {@code null}（调用方跳过）。
     */
    public String caption(File frameFile) {
        if (!enabled || frameFile == null || !frameFile.isFile()) return null;
        if (apiKey.isBlank() || baseUrl.isBlank()) return null;
        try {
            byte[] image = Files.readAllBytes(frameFile.toPath());
            if (image.length == 0 || image.length > MAX_IMAGE_BYTES) return null;
            String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image);

            JSONObject imagePart = new JSONObject();
            JSONObject imageUrl = new JSONObject();
            imageUrl.put("url", dataUrl);
            imagePart.put("type", "image_url");
            imagePart.put("image_url", imageUrl);
            JSONObject textPart = new JSONObject();
            textPart.put("type", "text");
            textPart.put("text", "用一句不超过 60 字的中文客观描述这张视频关键帧的画面内容（主体、动作、图表、文字以外的东西）。");

            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", JSONArray.of(textPart, imagePart));

            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", "你是视频帧描述助手，只输出一句客观描述，不推测、不评价。");

            JSONObject requestJson = new JSONObject();
            requestJson.put("model", model);
            requestJson.put("messages", JSONArray.of(systemMessage, userMessage));
            requestJson.put("max_tokens", 120);
            requestJson.put("temperature", 0.0);

            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(requestJson.toString(), JSON_MEDIA_TYPE))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                JSONObject json = JSON.parseObject(response.body().string());
                JSONArray choices = json.getJSONArray("choices");
                if (choices == null || choices.isEmpty()) return null;
                JSONObject message = choices.getJSONObject(0).getJSONObject("message");
                String content = message == null ? null : message.getString("content");
                if (content == null) return null;
                String trimmed = content.trim();
                return trimmed.isEmpty() ? null : trimmed;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
