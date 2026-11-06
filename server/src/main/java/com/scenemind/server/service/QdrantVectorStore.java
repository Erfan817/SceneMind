package com.scenemind.server.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.scenemind.server.dto.VideoChunk;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class QdrantVectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String collection;
    private final AtomicBoolean collectionReady = new AtomicBoolean();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public QdrantVectorStore(@Value("${vector.qdrant.enabled:true}") boolean enabled,
                             @Value("${vector.qdrant.url:http://localhost:6333}") String baseUrl,
                             @Value("${vector.qdrant.api-key:}") String apiKey,
                             @Value("${vector.qdrant.collection:video_chunks}") String collection) {
        if (!collection.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Qdrant collection name is invalid");
        }
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.collection = collection;
    }

    public void upsert(Long mediaId, List<VideoChunk> chunks) {
        if (!enabled) return;
        List<VideoChunk> vectorized = chunks.stream().filter(chunk -> !chunk.embedding().isEmpty()).toList();
        if (vectorized.isEmpty()) return;
        try {
            ensureCollection(vectorized.get(0).embedding().size());
            JSONArray points = new JSONArray();
            for (VideoChunk chunk : vectorized) {
                JSONObject payload = new JSONObject();
                payload.put("mediaId", mediaId);
                payload.put("startMs", chunk.startTime());
                payload.put("endMs", chunk.endTime());
                payload.put("granularity", "chunk");

                JSONObject point = new JSONObject();
                point.put("id", pointId(mediaId, chunk));
                point.put("vector", chunk.embedding());
                point.put("payload", payload);
                points.add(point);
            }
            JSONObject body = new JSONObject();
            body.put("points", points);
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points?wait=true")
                    .put(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
        } catch (RuntimeException e) {
            collectionReady.set(false);
            throw new IllegalStateException("Qdrant 分段向量写入失败", e);
        }
    }

    public List<VectorHit> search(Long mediaId, List<Double> queryEmbedding, int limit) {
        if (!enabled || queryEmbedding.isEmpty()) return List.of();
        try {
            ensureCollection(queryEmbedding.size());
            JSONObject filter = new JSONObject();
            filter.put("must", List.of(matchCondition("mediaId", mediaId)));
            // 分段点没有 granularity 字段（历史数据）或为 chunk；用 must_not 排除 segment，
            // 这样旧 collection 不需要重建也能继续按 chunk 召回。
            filter.put("must_not", List.of(matchCondition("granularity", "segment")));

            JSONObject body = new JSONObject();
            body.put("query", queryEmbedding);
            body.put("filter", filter);
            body.put("limit", limit);
            body.put("with_payload", true);
            String response = execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/query")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));

            JSONObject result = JSON.parseObject(response).getJSONObject("result");
            JSONArray points = result == null ? null : result.getJSONArray("points");
            if (points == null) return List.of();
            List<VectorHit> hits = new ArrayList<>(points.size());
            for (int i = 0; i < points.size(); i++) {
                JSONObject point = points.getJSONObject(i);
                JSONObject payload = point.getJSONObject("payload");
                if (payload == null) continue;
                hits.add(new VectorHit(
                        payload.getLongValue("startMs"),
                        payload.getLongValue("endMs"),
                        point.getDoubleValue("score")));
            }
            return hits;
        } catch (RuntimeException e) {
            collectionReady.set(false);
            throw new IllegalStateException("Qdrant 语义检索失败", e);
        }
    }

    /**
     * 写入分段级向量。与 chunk 向量共用同一个 collection，用 payload 的
     * {@code granularity=segment} 区分；这样粗排 chunk、精排 segment 可以走同一套索引。
     */
    public void upsertSegments(Long mediaId, List<SegmentVector> segments) {
        if (!enabled || mediaId == null || segments == null || segments.isEmpty()) return;
        List<SegmentVector> vectorized = segments.stream()
                .filter(segment -> segment != null && !segment.embedding().isEmpty())
                .toList();
        if (vectorized.isEmpty()) return;
        try {
            ensureCollection(vectorized.get(0).embedding().size());
            JSONArray points = new JSONArray();
            for (SegmentVector segment : vectorized) {
                JSONObject payload = new JSONObject();
                payload.put("mediaId", mediaId);
                payload.put("startMs", segment.startMs());
                payload.put("endMs", segment.endMs());
                payload.put("granularity", "segment");

                JSONObject point = new JSONObject();
                point.put("id", segmentPointId(mediaId, segment));
                point.put("vector", segment.embedding());
                point.put("payload", payload);
                points.add(point);
            }
            JSONObject body = new JSONObject();
            body.put("points", points);
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points?wait=true")
                    .put(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
        } catch (RuntimeException e) {
            collectionReady.set(false);
            throw new IllegalStateException("Qdrant 分段向量写入失败", e);
        }
    }

    /** 只召回 segment 粒度，避免 chunk 点稀释分段语义。 */
    public List<SegmentHit> searchSegments(Long mediaId, List<Double> queryEmbedding, int limit) {
        if (!enabled || mediaId == null || queryEmbedding == null || queryEmbedding.isEmpty()) return List.of();
        try {
            ensureCollection(queryEmbedding.size());
            JSONObject filter = new JSONObject();
            filter.put("must", List.of(
                    matchCondition("mediaId", mediaId),
                    matchCondition("granularity", "segment")));

            JSONObject body = new JSONObject();
            body.put("query", queryEmbedding);
            body.put("filter", filter);
            body.put("limit", limit);
            body.put("with_payload", true);
            String response = execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/query")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));

            JSONObject result = JSON.parseObject(response).getJSONObject("result");
            JSONArray points = result == null ? null : result.getJSONArray("points");
            if (points == null) return List.of();
            List<SegmentHit> hits = new ArrayList<>(points.size());
            for (int i = 0; i < points.size(); i++) {
                JSONObject point = points.getJSONObject(i);
                JSONObject payload = point.getJSONObject("payload");
                if (payload == null) continue;
                hits.add(new SegmentHit(
                        payload.getLongValue("startMs"),
                        payload.getLongValue("endMs"),
                        point.getDoubleValue("score")));
            }
            return hits;
        } catch (RuntimeException e) {
            collectionReady.set(false);
            throw new IllegalStateException("Qdrant 分段语义检索失败", e);
        }
    }

    public void deleteMedia(Long mediaId) {
        if (!enabled) return;
        try {
            JSONObject match = new JSONObject();
            match.put("value", mediaId);
            JSONObject condition = new JSONObject();
            condition.put("key", "mediaId");
            condition.put("match", match);
            JSONObject filter = new JSONObject();
            filter.put("must", List.of(condition));
            JSONObject body = new JSONObject();
            body.put("filter", filter);
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/delete?wait=true")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
        } catch (RuntimeException e) {
            log.warn("qdrant_media_cleanup_failed mediaId={}", mediaId, e);
        }
    }

    private void ensureCollection(int vectorSize) {
        if (collectionReady.get()) return;
        synchronized (collectionReady) {
            if (collectionReady.get()) return;
            Request.Builder lookup = request(baseUrl + "/collections/" + collection).get();
            try (Response response = client.newCall(lookup.build()).execute()) {
                if (response.isSuccessful()) {
                    collectionReady.set(true);
                    return;
                }
                if (response.code() != 404) {
                    throw new IllegalStateException("Qdrant collection lookup failed: " + response.code());
                }
            } catch (Exception e) {
                throw new IllegalStateException("Qdrant collection lookup failed", e);
            }

            JSONObject vectors = new JSONObject();
            vectors.put("size", vectorSize);
            vectors.put("distance", "Cosine");
            JSONObject body = new JSONObject();
            body.put("vectors", vectors);
            execute(request(baseUrl + "/collections/" + collection)
                    .put(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
            collectionReady.set(true);
        }
    }

    private String execute(Request.Builder request) {
        try (Response response = client.newCall(withApiKey(request).build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Qdrant API failed: " + response.code() + " " + body);
            }
            return body;
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant request failed", e);
        }
    }

    private Request.Builder request(String url) {
        return withApiKey(new Request.Builder().url(url));
    }

    private Request.Builder withApiKey(Request.Builder request) {
        if (!apiKey.isBlank()) request.header("api-key", apiKey);
        return request;
    }

    private JSONObject matchCondition(String key, Object value) {
        JSONObject match = new JSONObject();
        match.put("value", value);
        JSONObject condition = new JSONObject();
        condition.put("key", key);
        condition.put("match", match);
        return condition;
    }

    private String pointId(Long mediaId, VideoChunk chunk) {
        String source = mediaId + ":" + chunk.startTime() + ":" + chunk.endTime();
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String segmentPointId(Long mediaId, SegmentVector segment) {
        String source = mediaId + ":segment:" + segment.startMs() + ":" + segment.endMs();
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record VectorHit(long startMs, long endMs, double score) {
    }

    public record SegmentVector(long startMs, long endMs, List<Double> embedding) {
        public SegmentVector {
            if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid segment range");
            embedding = embedding == null ? List.of() : List.copyOf(embedding);
        }
    }

    public record SegmentHit(long startMs, long endMs, double score) {
    }
}
