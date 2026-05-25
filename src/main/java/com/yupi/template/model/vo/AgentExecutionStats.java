package com.yupi.template.model.vo;

import com.yupi.template.model.entity.AgentLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 智能体与节点执行统计 VO。
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionStats implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;

    private Integer totalDurationMs;

    private Integer agentCount;

    private Map<String, Integer> agentDurations;

    private Integer nodeCount;

    private Map<String, Integer> nodeDurations;

    private String overallStatus;

    private List<AgentLog> logs;

    private List<NodeExecutionLogVO> nodeLogs;
}