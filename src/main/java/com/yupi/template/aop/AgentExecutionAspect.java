package com.yupi.template.aop;

import com.yupi.template.annotation.AgentExecution;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.AgentLog;
import com.yupi.template.model.enums.ArticlePhaseEnum;
import com.yupi.template.model.vo.NodeExecutionMetadata;
import com.yupi.template.service.AgentLogService;
import com.yupi.template.service.ArticleNodeLogService;
import com.yupi.template.service.ArticleNodeReplayService;
import com.yupi.template.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 智能体执行日志与节点观测切面。
 */
@Aspect
@Component
@Slf4j
public class AgentExecutionAspect {

    @Resource
    private AgentLogService agentLogService;

    @Resource
    private ArticleNodeLogService articleNodeLogService;

    @Resource
    private ArticleNodeReplayService articleNodeReplayService;

    @Around("@annotation(agentExecution)")
    public Object aroundAgentExecution(ProceedingJoinPoint pjp, AgentExecution agentExecution) throws Throwable {
        long startTime = System.currentTimeMillis();
        LocalDateTime startDateTime = LocalDateTime.now();

        String taskId = extractTaskId(pjp);
        String inputData = extractInputData(pjp);
        String prompt = extractPrompt(pjp);
        String phase = resolvePhase(pjp, agentExecution);
        String node = agentExecution.value();
        NodeExecutionMetadata metadata = extractNodeExecutionMetadata(pjp);

        AgentLog agentLog = AgentLog.builder()
                .taskId(taskId)
                .agentName(agentExecution.value())
                .startTime(startDateTime)
                .status("RUNNING")
                .prompt(prompt)
                .inputData(inputData)
                .build();

        if (shouldRecordNodeLog(taskId, phase)) {
            articleNodeLogService.start(taskId, phase, node, buildNodeMessage(agentExecution, "started"), metadata);
            articleNodeReplayService.start(taskId, phase, node,
                    buildNodeMessage(agentExecution, "started"), inputData, metadata);
        }

        Object result;
        try {
            result = pjp.proceed();

            agentLog.setStatus("SUCCESS");
            agentLog.setEndTime(LocalDateTime.now());
            agentLog.setDurationMs((int) (System.currentTimeMillis() - startTime));
            agentLog.setOutputData(extractOutputData(result));

            log.info("智能体执行成功, agent={}, taskId={}, duration={}ms",
                    agentExecution.value(), taskId, agentLog.getDurationMs());
            if (shouldRecordNodeLog(taskId, phase)) {
                articleNodeLogService.success(taskId, phase, node,
                        buildNodeMessage(agentExecution, "finished"), metadata);
                articleNodeReplayService.success(taskId, phase, node,
                        buildNodeMessage(agentExecution, "finished"),
                        agentLog.getOutputData(), agentLog.getDurationMs(), metadata);
            }
            return result;
        } catch (Throwable e) {
            agentLog.setStatus("FAILED");
            agentLog.setEndTime(LocalDateTime.now());
            agentLog.setDurationMs((int) (System.currentTimeMillis() - startTime));
            agentLog.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getName());

            log.error("智能体执行失败, agent={}, taskId={}, error={}",
                    agentExecution.value(), taskId, e.getMessage(), e);
            if (shouldRecordNodeLog(taskId, phase)) {
                articleNodeLogService.fail(taskId, phase, node,
                        buildNodeErrorMessage(agentExecution, e), metadata);
                articleNodeReplayService.fail(taskId, phase, node,
                        buildNodeErrorMessage(agentExecution, e),
                        agentLog.getErrorMessage(), agentLog.getDurationMs(), metadata);
            }
            throw e;
        } finally {
            agentLogService.saveLogAsync(agentLog);
        }
    }

    private String extractTaskId(ProceedingJoinPoint pjp) {
        Object[] args = pjp.getArgs();
        if (args == null || args.length == 0) {
            return "unknown";
        }

        for (Object arg : args) {
            if (arg instanceof ArticleState articleState) {
                return articleState.getTaskId();
            }
        }

        for (Object arg : args) {
            if (arg instanceof String str) {
                return str;
            }
        }

        return "unknown";
    }

    private String extractInputData(ProceedingJoinPoint pjp) {
        try {
            Object[] args = pjp.getArgs();
            if (args == null || args.length == 0) {
                return null;
            }

            Map<String, Object> inputMap = new HashMap<>();
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            String[] paramNames = signature.getParameterNames();

            for (int i = 0; i < args.length && i < paramNames.length; i++) {
                Object arg = args[i];
                if (arg instanceof String || arg instanceof Number || arg instanceof Boolean) {
                    inputMap.put(paramNames[i], arg);
                } else if (arg instanceof ArticleState state) {
                    inputMap.put("taskId", state.getTaskId());
                    if (state.getTitle() != null) {
                        inputMap.put("mainTitle", state.getTitle().getMainTitle());
                    }
                }
            }

            return inputMap.isEmpty() ? null : GsonUtils.toJson(inputMap);
        } catch (Exception e) {
            log.warn("提取输入数据失败", e);
            return null;
        }
    }

    private String extractOutputData(Object result) {
        try {
            if (result == null) {
                return null;
            }
            if (result instanceof String || result instanceof Number || result instanceof Boolean) {
                return String.valueOf(result);
            }
            if (result instanceof java.util.List<?> list) {
                return "{\"listSize\": " + list.size() + "}";
            }
            return "{\"type\": \"" + result.getClass().getSimpleName() + "\"}";
        } catch (Exception e) {
            log.warn("提取输出数据失败", e);
            return null;
        }
    }

    private String extractPrompt(ProceedingJoinPoint pjp) {
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            Method method = signature.getMethod();
            return method.getDeclaringClass().getSimpleName() + "." + method.getName();
        } catch (Exception e) {
            return null;
        }
    }

    private NodeExecutionMetadata extractNodeExecutionMetadata(ProceedingJoinPoint pjp) {
        Object[] args = pjp.getArgs();
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof NodeExecutionMetadata metadata) {
                return metadata;
            }
        }
        return null;
    }

    private String resolvePhase(ProceedingJoinPoint pjp, AgentExecution agentExecution) {
        Object[] args = pjp.getArgs();
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof ArticleState articleState && articleState.getPhase() != null) {
                    return articleState.getPhase();
                }
            }
        }
        return switch (agentExecution.value()) {
            case "agent1_generate_titles" -> ArticlePhaseEnum.TITLE_GENERATING.getValue();
            case "agent2_generate_outline" -> ArticlePhaseEnum.OUTLINE_GENERATING.getValue();
            case "agent3_generate_content",
                 "agent4_analyze_image_requirements",
                 "agent5_generate_images",
                 "agent6_merge_content" -> ArticlePhaseEnum.CONTENT_GENERATING.getValue();
            case "ai_modify_outline" -> ArticlePhaseEnum.OUTLINE_EDITING.getValue();
            default -> null;
        };
    }

    private boolean shouldRecordNodeLog(String taskId, String phase) {
        return taskId != null && !"unknown".equals(taskId) && phase != null;
    }

    private String buildNodeMessage(AgentExecution agentExecution, String action) {
        String description = agentExecution.description();
        if (description == null || description.isBlank()) {
            return agentExecution.value() + " " + action;
        }
        return description + " " + action;
    }

    private String buildNodeErrorMessage(AgentExecution agentExecution, Throwable error) {
        String prefix = buildNodeMessage(agentExecution, "failed");
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return prefix;
        }
        return prefix + ": " + error.getMessage();
    }
}
