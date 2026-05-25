package com.yupi.template.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.yupi.template.mapper.AgentLogMapper;
import com.yupi.template.model.entity.AgentLog;
import com.yupi.template.model.vo.AgentExecutionStats;
import com.yupi.template.model.vo.NodeExecutionLogVO;
import com.yupi.template.service.AgentLogService;
import com.yupi.template.service.ArticleNodeLogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 智能体日志服务实现。
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@Service
@Slf4j
public class AgentLogServiceImpl extends ServiceImpl<AgentLogMapper, AgentLog> implements AgentLogService {

    @Resource
    private ArticleNodeLogService articleNodeLogService;

    @Override
    @Async
    public void saveLogAsync(AgentLog agentLog) {
        try {
            this.save(agentLog);
            log.info("智能体日志已保存, taskId={}, agentName={}, status={}, durationMs={}",
                    agentLog.getTaskId(), agentLog.getAgentName(), agentLog.getStatus(), agentLog.getDurationMs());
        } catch (Exception e) {
            log.error("保存智能体日志失败, taskId={}, agentName={}",
                    agentLog.getTaskId(), agentLog.getAgentName(), e);
        }
    }

    @Override
    public List<AgentLog> getLogsByTaskId(String taskId) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("taskId", taskId)
                .orderBy("createTime", true);
        return this.list(queryWrapper);
    }

    @Override
    public AgentExecutionStats getExecutionStats(String taskId) {
        List<AgentLog> logs = getLogsByTaskId(taskId);
        List<NodeExecutionLogVO> nodeLogs = articleNodeLogService.getLogs(taskId);

        if ((logs == null || logs.isEmpty()) && (nodeLogs == null || nodeLogs.isEmpty())) {
            return AgentExecutionStats.builder()
                    .taskId(taskId)
                    .agentCount(0)
                    .nodeCount(0)
                    .totalDurationMs(0)
                    .agentDurations(new HashMap<>())
                    .nodeDurations(new HashMap<>())
                    .overallStatus("NOT_FOUND")
                    .logs(List.of())
                    .nodeLogs(List.of())
                    .build();
        }

        int totalDuration = 0;
        Map<String, Integer> agentDurations = new HashMap<>();
        Map<String, Integer> nodeDurations = new HashMap<>();
        String overallStatus = "SUCCESS";

        if (logs != null) {
            for (AgentLog log : logs) {
                if (log.getDurationMs() != null) {
                    totalDuration += log.getDurationMs();
                    agentDurations.put(log.getAgentName(), log.getDurationMs());
                }
                overallStatus = mergeStatus(overallStatus, log.getStatus());
            }
        }

        if (nodeLogs != null) {
            for (NodeExecutionLogVO nodeLog : nodeLogs) {
                if (nodeLog.getElapsedMs() != null && nodeLog.getNode() != null) {
                    nodeDurations.put(nodeLog.getNode(), nodeLog.getElapsedMs());
                }
                overallStatus = mergeStatus(overallStatus, nodeLog.getStatus());
            }
        }

        if (totalDuration == 0 && !nodeDurations.isEmpty()) {
            totalDuration = nodeDurations.values().stream()
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .sum();
        }

        int nodeCount = nodeLogs == null ? 0 : (int) nodeLogs.stream()
                .map(NodeExecutionLogVO::getNode)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        return AgentExecutionStats.builder()
                .taskId(taskId)
                .totalDurationMs(totalDuration)
                .agentCount(logs == null ? 0 : logs.size())
                .agentDurations(agentDurations)
                .nodeCount(nodeCount)
                .nodeDurations(nodeDurations)
                .overallStatus(overallStatus)
                .logs(logs == null ? List.of() : logs)
                .nodeLogs(nodeLogs == null ? List.of() : nodeLogs)
                .build();
    }

    private String mergeStatus(String currentStatus, String candidateStatus) {
        if ("FAILED".equals(candidateStatus)) {
            return "FAILED";
        }
        if ("RUNNING".equals(candidateStatus) || "INFO".equals(candidateStatus)) {
            if (!"FAILED".equals(currentStatus)) {
                return "RUNNING";
            }
        }
        return currentStatus;
    }
}