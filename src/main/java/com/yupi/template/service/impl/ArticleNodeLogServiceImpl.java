package com.yupi.template.service.impl;

import com.google.gson.reflect.TypeToken;
import com.yupi.template.model.enums.NodeExecutionStatusEnum;
import com.yupi.template.model.vo.NodeExecutionLogVO;
import com.yupi.template.model.vo.NodeExecutionMetadata;
import com.yupi.template.service.ArticleNodeLogService;
import com.yupi.template.utils.GsonUtils;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed node execution log service.
 */
@Service
public class ArticleNodeLogServiceImpl implements ArticleNodeLogService {

    private static final String NODE_LOG_KEY_PREFIX = "article:node:logs:";
    private static final String NODE_START_KEY_PREFIX = "article:node:starts:";
    private static final long LOG_TTL_HOURS = 72L;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public void start(String taskId, String phase, String node, String message) {
        start(taskId, phase, node, message, null);
    }

    @Override
    public void start(String taskId, String phase, String node, String message, NodeExecutionMetadata metadata) {
        long now = System.currentTimeMillis();
        redisTemplate.opsForHash().put(buildStartKey(taskId), nodeKey(phase, node), now);
        record(taskId, phase, node, NodeExecutionStatusEnum.RUNNING, message, null, metadata);
    }

    @Override
    public void success(String taskId, String phase, String node, String message) {
        success(taskId, phase, node, message, null);
    }

    @Override
    public void success(String taskId, String phase, String node, String message, NodeExecutionMetadata metadata) {
        record(taskId, phase, node, NodeExecutionStatusEnum.SUCCESS, message,
                resolveElapsedMs(taskId, phase, node, true), metadata);
    }

    @Override
    public void fail(String taskId, String phase, String node, String message) {
        fail(taskId, phase, node, message, null);
    }

    @Override
    public void fail(String taskId, String phase, String node, String message, NodeExecutionMetadata metadata) {
        record(taskId, phase, node, NodeExecutionStatusEnum.FAILED, message,
                resolveElapsedMs(taskId, phase, node, true), metadata);
    }

    @Override
    public void info(String taskId, String phase, String node, String message) {
        info(taskId, phase, node, message, null);
    }

    @Override
    public void info(String taskId, String phase, String node, String message, NodeExecutionMetadata metadata) {
        record(taskId, phase, node, NodeExecutionStatusEnum.INFO, message,
                resolveElapsedMs(taskId, phase, node, false), metadata);
    }

    @Override
    public void record(String taskId, String phase, String node, NodeExecutionStatusEnum status,
                       String message, Integer elapsedMs) {
        record(taskId, phase, node, status, message, elapsedMs, null);
    }

    @Override
    public void record(String taskId, String phase, String node, NodeExecutionStatusEnum status,
                       String message, Integer elapsedMs, NodeExecutionMetadata metadata) {
        if (taskId == null || phase == null || node == null || status == null) {
            return;
        }
        NodeExecutionLogVO logVO = NodeExecutionLogVO.builder()
                .taskId(taskId)
                .phase(phase)
                .node(node)
                .status(status.getValue())
                .message(message)
                .elapsedMs(elapsedMs)
                .timestamp(System.currentTimeMillis())
                .promptKey(metadata == null ? null : metadata.getPromptKey())
                .promptVersion(metadata == null ? null : metadata.getPromptVersion())
                .model(metadata == null ? null : metadata.getModel())
                .temperature(metadata == null ? null : metadata.getTemperature())
                .maxTokens(metadata == null ? null : metadata.getMaxTokens())
                .topP(metadata == null ? null : metadata.getTopP())
                .decisionSource(metadata == null ? null : metadata.getDecisionSource())
                .decisionReason(metadata == null ? null : metadata.getDecisionReason())
                .decisionSummary(metadata == null ? null : metadata.getDecisionSummary())
                .fallbackSource(metadata == null ? null : metadata.getFallbackSource())
                .fallbackReason(metadata == null ? null : metadata.getFallbackReason())
                .fallbackSummary(metadata == null ? null : metadata.getFallbackSummary())
                .build();
        redisTemplate.opsForList().rightPush(buildLogKey(taskId), GsonUtils.toJson(logVO));
        redisTemplate.expire(buildLogKey(taskId), LOG_TTL_HOURS, TimeUnit.HOURS);
        redisTemplate.expire(buildStartKey(taskId), LOG_TTL_HOURS, TimeUnit.HOURS);
    }

    @Override
    public List<NodeExecutionLogVO> getLogs(String taskId) {
        List<Object> values = redisTemplate.opsForList().range(buildLogKey(taskId), 0, -1);
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        List<NodeExecutionLogVO> logs = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof NodeExecutionLogVO logVO) {
                logs.add(logVO);
                continue;
            }
            String json = value instanceof String
                    ? (String) value
                    : GsonUtils.toJson(value);
            NodeExecutionLogVO logVO = GsonUtils.fromJsonSafe(
                    json,
                    new TypeToken<NodeExecutionLogVO>() {
                    }
            );
            if (logVO != null) {
                logs.add(logVO);
            }
        }
        return logs;
    }

    @Override
    public void clear(String taskId) {
        redisTemplate.delete(buildLogKey(taskId));
        redisTemplate.delete(buildStartKey(taskId));
    }

    private Integer resolveElapsedMs(String taskId, String phase, String node, boolean clearStart) {
        Object startValue = redisTemplate.opsForHash().get(buildStartKey(taskId), nodeKey(phase, node));
        if (startValue == null) {
            return null;
        }
        long start = Long.parseLong(startValue.toString());
        if (clearStart) {
            redisTemplate.opsForHash().delete(buildStartKey(taskId), nodeKey(phase, node));
        }
        return (int) Math.max(0L, System.currentTimeMillis() - start);
    }

    private String buildLogKey(String taskId) {
        return NODE_LOG_KEY_PREFIX + taskId;
    }

    private String buildStartKey(String taskId) {
        return NODE_START_KEY_PREFIX + taskId;
    }

    private String nodeKey(String phase, String node) {
        return phase + ":" + node;
    }
}
