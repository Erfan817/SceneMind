package com.scenemind.server.utils;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class EmbeddingUtils {

    private final String apiKey;
    private final String baseUrl;
    private final String model;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public EmbeddingUtils(@Value("${ai.deepseek.api-key}") String apiKey,
                          @Value("${ai.deepseek.base-url}") String baseUrl,
                          @Value("${ai.embedding.model:BAAI/bge-m3}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
    }

    public List<Double> embed(String text) {
        if (text == null || text.isBlank()) return List.of();
        try {
            JSONObject requestJson = new JSONObject();
            requestJson.put("model", model);
            requestJson.put("input", text);
            return parseEmbeddings(execute(requestJson), 1).get(0);
        } catch (Exception e) {
            throw new IllegalStateException("Embedding 生成失败", e);
        }
    }

    /**
     * 批量生成 embedding。分段级语义索引需要一次嵌入整段视频的几十个片段，
     * 逐条调用会把网络往返放大到不可接受；供应商不支持数组输入时回退到逐条。
     */
    public List<List<Double>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        List<String> safe = texts.stream()
                .map(text -> text == null ? "" : text)
                .toList();
        try {
            JSONObject requestJson = new JSONObject();
            requestJson.put("model", model);
            requestJson.put("input", safe);
            return parseEmbeddings(execute(requestJson), safe.size());
        } catch (Exception batchFailure) {
            List<List<Double>> fallback = new ArrayList<>(safe.size());
            for (String text : safe) {
                fallback.add(text.isBlank() ? List.of() : embed(text));
            }
            return fallback;
        }
    }

    private String execute(JSONObject requestJson) throws Exception {
        Request request = new Request.Builder()
                .url(baseUrl + "/embeddings")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(
                        requestJson.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                ))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("Embedding API failed: " + response.code());
            }
            return response.body().string();
        }
    }

    private List<List<Double>> parseEmbeddings(String responseBody, int expectedSize) {
        JSONObject json = JSON.parseObject(responseBody);
        JSONArray data = json.getJSONArray("data");
        if (data == null || data.isEmpty()) throw new IllegalStateException("Embedding data is empty");

        List<List<Double>> embeddings = new ArrayList<>(expectedSize);
        for (int i = 0; i < expectedSize; i++) embeddings.add(List.of());
        for (int i = 0; i < data.size(); i++) {
            JSONObject item = data.getJSONObject(i);
            JSONArray values = item.getJSONArray("embedding");
            if (values == null || values.isEmpty()) continue;
            int index = item.containsKey("index") ? item.getIntValue("index") : i;
            if (index < 0 || index >= expectedSize) continue;
            List<Double> embedding = new ArrayList<>(values.size());
            for (Object value : values) {
                embedding.add(((Number) value).doubleValue());
            }
            embeddings.set(index, List.copyOf(embedding));
        }
        if (embeddings.stream().allMatch(List::isEmpty)) {
            throw new IllegalStateException("Embedding vector is empty");
        }
        return embeddings;
    }
}
