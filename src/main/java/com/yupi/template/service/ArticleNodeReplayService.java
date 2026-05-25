package com.yupi.template.service;

import com.yupi.template.model.vo.NodeExecutionMetadata;
import com.yupi.template.model.vo.NodeReplaySnapshotVO;

import java.util.List;

/**
 * Node replay snapshot service.
 */
public interface ArticleNodeReplayService {

    void start(String taskId, String phase, String node, String message,
               String inputSummary, NodeExecutionMetadata metadata);

    void success(String taskId, String phase, String node, String message,
                 String outputSummary, Integer elapsedMs, NodeExecutionMetadata metadata);

    void fail(String taskId, String phase, String node, String message,
              String errorMessage, Integer elapsedMs, NodeExecutionMetadata metadata);

    List<NodeReplaySnapshotVO> getSnapshots(String taskId);

    NodeReplaySnapshotVO getLatestSnapshot(String taskId, String node);

    void clear(String taskId);
}
