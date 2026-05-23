package com.yupi.template.service;

import com.yupi.template.model.enums.NodeExecutionStatusEnum;
import com.yupi.template.model.vo.NodeExecutionLogVO;
import com.yupi.template.model.vo.NodeExecutionMetadata;

import java.util.List;

/**
 * Node-level workflow observability service.
 */
public interface ArticleNodeLogService {

    void start(String taskId, String phase, String node, String message);

    void start(String taskId, String phase, String node, String message, NodeExecutionMetadata metadata);

    void success(String taskId, String phase, String node, String message);

    void success(String taskId, String phase, String node, String message, NodeExecutionMetadata metadata);

    void fail(String taskId, String phase, String node, String message);

    void fail(String taskId, String phase, String node, String message, NodeExecutionMetadata metadata);

    void info(String taskId, String phase, String node, String message);

    void info(String taskId, String phase, String node, String message, NodeExecutionMetadata metadata);

    void record(String taskId, String phase, String node, NodeExecutionStatusEnum status,
                String message, Integer elapsedMs);

    void record(String taskId, String phase, String node, NodeExecutionStatusEnum status,
                String message, Integer elapsedMs, NodeExecutionMetadata metadata);

    List<NodeExecutionLogVO> getLogs(String taskId);

    void clear(String taskId);
}
