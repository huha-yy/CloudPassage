package com.yupi.template.service;

import com.yupi.template.model.enums.NodeExecutionStatusEnum;
import com.yupi.template.model.vo.NodeExecutionLogVO;

import java.util.List;

/**
 * Node-level workflow observability service.
 */
public interface ArticleNodeLogService {

    void start(String taskId, String phase, String node, String message);

    void success(String taskId, String phase, String node, String message);

    void fail(String taskId, String phase, String node, String message);

    void info(String taskId, String phase, String node, String message);

    void record(String taskId, String phase, String node, NodeExecutionStatusEnum status,
                String message, Integer elapsedMs);

    List<NodeExecutionLogVO> getLogs(String taskId);

    void clear(String taskId);
}
