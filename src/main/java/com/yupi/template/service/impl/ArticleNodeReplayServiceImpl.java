package com.yupi.template.service.impl;

import cn.hutool.core.util.IdUtil;
import com.google.gson.reflect.TypeToken;
import com.yupi.template.model.vo.NodeExecutionMetadata;
import com.yupi.template.model.vo.NodeReplaySnapshotVO;
import com.yupi.template.service.ArticleNodeReplayService;
import com.yupi.template.utils.GsonUtils;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed replay snapshot service for node-level debugging.
 */
@Service
public class ArticleNodeReplayServiceImpl implements ArticleNodeReplayService {

    private static final String REPLAY_IDS_KEY_PREFIX = "article:node:replay:ids:";
    private static final String REPLAY_DATA_KEY_PREFIX = "article:node:replay:data:";
    private static final String REPLAY_RUNNING_KEY_PREFIX = "article:node:replay:running:";
    private static final String SNAPSHOT_VERSION = "v1";
    private static final long REPLAY_TTL_HOURS = 24L * 7;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public void start(String taskId, String phase, String node, String message,
                      String inputSummary, NodeExecutionMetadata metadata) {
        if (isBlank(taskId) || isBlank(phase) || isBlank(node)) {
            return;
        }
        long now = System.currentTimeMillis();
        String snapshotId = IdUtil.fastSimpleUUID();
        NodeReplaySnapshotVO snapshot = NodeReplaySnapshotVO.builder()
                .snapshotId(snapshotId)
                .snapshotVersion(SNAPSHOT_VERSION)
                .taskId(taskId)
                .phase(phase)
                .node(node)
                .status("RUNNING")
                .message(message)
                .startedAt(now)
                .inputSummary(inputSummary)
                .retryCount(resolveRetryCount(taskId, node) + 1)
                .replayable(Boolean.FALSE)
                .build();
        applyMetadata(snapshot, metadata);

        redisTemplate.opsForList().rightPush(buildIdsKey(taskId), snapshotId);
        redisTemplate.opsForHash().put(buildDataKey(taskId), snapshotId, GsonUtils.toJson(snapshot));
        redisTemplate.opsForHash().put(buildRunningKey(taskId), buildNodeKey(phase, node), snapshotId);
        expireKeys(taskId);
    }

    @Override
    public void success(String taskId, String phase, String node, String message,
                        String outputSummary, Integer elapsedMs, NodeExecutionMetadata metadata) {
        finish(taskId, phase, node, "SUCCESS", message, null, outputSummary, elapsedMs, metadata);
    }

    @Override
    public void fail(String taskId, String phase, String node, String message,
                     String errorMessage, Integer elapsedMs, NodeExecutionMetadata metadata) {
        finish(taskId, phase, node, "FAILED", message, errorMessage, null, elapsedMs, metadata);
    }

    @Override
    public List<NodeReplaySnapshotVO> getSnapshots(String taskId) {
        if (isBlank(taskId)) {
            return new ArrayList<>();
        }
        List<Object> snapshotIds = redisTemplate.opsForList().range(buildIdsKey(taskId), 0, -1);
        if (snapshotIds == null || snapshotIds.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, NodeReplaySnapshotVO> snapshotMap = readSnapshotMap(taskId);
        List<NodeReplaySnapshotVO> snapshots = new ArrayList<>(snapshotIds.size());
        for (Object snapshotIdObj : snapshotIds) {
            if (snapshotIdObj == null) {
                continue;
            }
            NodeReplaySnapshotVO snapshot = snapshotMap.get(snapshotIdObj.toString());
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        snapshots.sort(Comparator.comparing(NodeReplaySnapshotVO::getStartedAt,
                Comparator.nullsLast(Long::compareTo)));
        return snapshots;
    }

    @Override
    public NodeReplaySnapshotVO getLatestSnapshot(String taskId, String node) {
        if (isBlank(taskId) || isBlank(node)) {
            return null;
        }
        List<NodeReplaySnapshotVO> snapshots = getSnapshots(taskId);
        NodeReplaySnapshotVO latest = null;
        for (NodeReplaySnapshotVO snapshot : snapshots) {
            if (!Objects.equals(node, snapshot.getNode())) {
                continue;
            }
            if (latest == null) {
                latest = snapshot;
                continue;
            }
            Long currentStartedAt = snapshot.getStartedAt();
            Long latestStartedAt = latest.getStartedAt();
            if (currentStartedAt != null && (latestStartedAt == null || currentStartedAt >= latestStartedAt)) {
                latest = snapshot;
            }
        }
        return latest;
    }

    @Override
    public void clear(String taskId) {
        redisTemplate.delete(buildIdsKey(taskId));
        redisTemplate.delete(buildDataKey(taskId));
        redisTemplate.delete(buildRunningKey(taskId));
    }

    private void finish(String taskId, String phase, String node, String status, String message,
                        String errorMessage, String outputSummary, Integer elapsedMs,
                        NodeExecutionMetadata metadata) {
        if (isBlank(taskId) || isBlank(phase) || isBlank(node)) {
            return;
        }
        String runningKey = buildNodeKey(phase, node);
        Object snapshotIdValue = redisTemplate.opsForHash().get(buildRunningKey(taskId), runningKey);
        NodeReplaySnapshotVO snapshot = null;
        String snapshotId = snapshotIdValue == null ? null : snapshotIdValue.toString();
        if (!isBlank(snapshotId)) {
            snapshot = readSnapshot(taskId, snapshotId);
        }
        if (snapshot == null) {
            snapshot = NodeReplaySnapshotVO.builder()
                    .snapshotId(IdUtil.fastSimpleUUID())
                    .snapshotVersion(SNAPSHOT_VERSION)
                    .taskId(taskId)
                    .phase(phase)
                    .node(node)
                    .startedAt(System.currentTimeMillis())
                    .retryCount(resolveRetryCount(taskId, node) + 1)
                    .build();
            redisTemplate.opsForList().rightPush(buildIdsKey(taskId), snapshot.getSnapshotId());
        }

        snapshot.setStatus(status);
        snapshot.setMessage(message);
        snapshot.setFinishedAt(System.currentTimeMillis());
        snapshot.setElapsedMs(elapsedMs);
        snapshot.setOutputSummary(outputSummary);
        snapshot.setErrorMessage(errorMessage);
        snapshot.setReplayable(Boolean.TRUE);
        applyMetadata(snapshot, metadata);

        redisTemplate.opsForHash().put(buildDataKey(taskId), snapshot.getSnapshotId(), GsonUtils.toJson(snapshot));
        redisTemplate.opsForHash().delete(buildRunningKey(taskId), runningKey);
        expireKeys(taskId);
    }

    private Map<String, NodeReplaySnapshotVO> readSnapshotMap(String taskId) {
        Map<Object, Object> rawMap = redisTemplate.opsForHash().entries(buildDataKey(taskId));
        if (rawMap == null || rawMap.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, NodeReplaySnapshotVO> snapshotMap = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : rawMap.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            NodeReplaySnapshotVO snapshot = decodeSnapshot(entry.getValue());
            if (snapshot != null) {
                snapshotMap.put(entry.getKey().toString(), snapshot);
            }
        }
        return snapshotMap;
    }

    private NodeReplaySnapshotVO readSnapshot(String taskId, String snapshotId) {
        Object cached = redisTemplate.opsForHash().get(buildDataKey(taskId), snapshotId);
        if (cached == null) {
            return null;
        }
        return decodeSnapshot(cached);
    }

    private NodeReplaySnapshotVO decodeSnapshot(Object rawValue) {
        if (rawValue instanceof NodeReplaySnapshotVO snapshotVO) {
            return snapshotVO;
        }
        String json = rawValue instanceof String
                ? (String) rawValue
                : GsonUtils.toJson(rawValue);
        return GsonUtils.fromJsonSafe(json, new TypeToken<NodeReplaySnapshotVO>() {
        });
    }

    private int resolveRetryCount(String taskId, String node) {
        int count = 0;
        for (NodeReplaySnapshotVO snapshot : getSnapshots(taskId)) {
            if (Objects.equals(node, snapshot.getNode())) {
                count = Math.max(count, snapshot.getRetryCount() == null ? 0 : snapshot.getRetryCount());
            }
        }
        return count;
    }

    private void applyMetadata(NodeReplaySnapshotVO snapshot, NodeExecutionMetadata metadata) {
        if (snapshot == null || metadata == null) {
            return;
        }
        snapshot.setPromptKey(metadata.getPromptKey());
        snapshot.setPromptVersion(metadata.getPromptVersion());
        snapshot.setModel(metadata.getModel());
        snapshot.setTemperature(metadata.getTemperature());
        snapshot.setMaxTokens(metadata.getMaxTokens());
        snapshot.setTopP(metadata.getTopP());
        snapshot.setDecisionSource(metadata.getDecisionSource());
        snapshot.setDecisionReason(metadata.getDecisionReason());
        snapshot.setDecisionSummary(metadata.getDecisionSummary());
    }

    private void expireKeys(String taskId) {
        redisTemplate.expire(buildIdsKey(taskId), REPLAY_TTL_HOURS, TimeUnit.HOURS);
        redisTemplate.expire(buildDataKey(taskId), REPLAY_TTL_HOURS, TimeUnit.HOURS);
        redisTemplate.expire(buildRunningKey(taskId), REPLAY_TTL_HOURS, TimeUnit.HOURS);
    }

    private String buildIdsKey(String taskId) {
        return REPLAY_IDS_KEY_PREFIX + taskId;
    }

    private String buildDataKey(String taskId) {
        return REPLAY_DATA_KEY_PREFIX + taskId;
    }

    private String buildRunningKey(String taskId) {
        return REPLAY_RUNNING_KEY_PREFIX + taskId;
    }

    private String buildNodeKey(String phase, String node) {
        return phase + ":" + node;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
