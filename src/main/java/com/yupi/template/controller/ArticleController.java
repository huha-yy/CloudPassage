package com.yupi.template.controller;

import com.mybatisflex.core.paginate.Page;
import com.yupi.template.common.BaseResponse;
import com.yupi.template.common.DeleteRequest;
import com.yupi.template.common.ResultUtils;
import com.yupi.template.exception.ErrorCode;
import com.yupi.template.exception.ThrowUtils;
import com.yupi.template.manager.SseEmitterManager;
import com.yupi.template.model.dto.article.ArticleAiModifyOutlineRequest;
import com.yupi.template.model.dto.article.ArticleConfirmOutlineRequest;
import com.yupi.template.model.dto.article.ArticleConfirmTitleRequest;
import com.yupi.template.model.dto.article.ArticleCreateRequest;
import com.yupi.template.model.dto.article.ArticleQueryRequest;
import com.yupi.template.model.dto.article.ArticleRetryNodeRequest;
import com.yupi.template.model.dto.article.ArticleResumeRequest;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.User;
import com.yupi.template.model.enums.ArticleStyleEnum;
import com.yupi.template.model.vo.AgentExecutionStats;
import com.yupi.template.model.vo.ArticleTaskMemoryVO;
import com.yupi.template.model.vo.ArticleTaskSnapshotVO;
import com.yupi.template.model.vo.ArticleVO;
import com.yupi.template.service.AgentLogService;
import com.yupi.template.service.ArticleAsyncService;
import com.yupi.template.service.ArticleService;
import com.yupi.template.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Article endpoints.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@RestController
@RequestMapping("/article")
@Slf4j
public class ArticleController {

    @Resource
    private ArticleService articleService;

    @Resource
    private ArticleAsyncService articleAsyncService;

    @Resource
    private SseEmitterManager sseEmitterManager;

    @Resource
    private UserService userService;

    @Resource
    private AgentLogService agentLogService;

    @PostMapping("/create")
    @Operation(summary = "Create article task")
    public BaseResponse<String> createArticle(@RequestBody ArticleCreateRequest request,
                                              HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getTopic() == null || request.getTopic().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Topic cannot be empty");
        ThrowUtils.throwIf(!ArticleStyleEnum.isValid(request.getStyle()),
                ErrorCode.PARAMS_ERROR, "Invalid article style");

        User loginUser = userService.getLoginUser(httpServletRequest);
        String taskId = articleService.createArticleTaskWithQuotaCheck(
                request.getTopic(),
                request.getStyle(),
                request.getEnabledImageMethods(),
                loginUser
        );
        articleAsyncService.executePhase1(taskId, request.getTopic(), request.getStyle());
        return ResultUtils.success(taskId);
    }

    @GetMapping("/progress/{taskId}")
    @Operation(summary = "Subscribe article progress by SSE")
    public SseEmitter getProgress(@PathVariable String taskId, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(taskId == null || taskId.trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Task id cannot be empty");
        User loginUser = userService.getLoginUser(httpServletRequest);
        articleService.getArticleDetail(taskId, loginUser);
        SseEmitter emitter = sseEmitterManager.createEmitter(taskId);
        log.info("SSE connected, taskId={}", taskId);
        return emitter;
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "Get article detail")
    public BaseResponse<ArticleVO> getArticle(@PathVariable String taskId, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(taskId == null || taskId.trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Task id cannot be empty");
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(articleService.getArticleDetail(taskId, loginUser));
    }

    @GetMapping("/snapshot/{taskId}")
    @Operation(summary = "Get recoverable task snapshot")
    public BaseResponse<ArticleTaskSnapshotVO> getTaskSnapshot(@PathVariable String taskId,
                                                               HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(taskId == null || taskId.trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Task id cannot be empty");
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(articleService.getTaskSnapshot(taskId, loginUser));
    }

    @GetMapping("/task-memory/{taskId}")
    @Operation(summary = "Get task-level memory")
    public BaseResponse<ArticleTaskMemoryVO> getTaskMemory(@PathVariable String taskId,
                                                           HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(taskId == null || taskId.trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Task id cannot be empty");
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(articleService.getTaskMemory(taskId, loginUser));
    }

    @PostMapping("/list")
    @Operation(summary = "List articles by page")
    public BaseResponse<Page<ArticleVO>> listArticle(@RequestBody ArticleQueryRequest request,
                                                     HttpServletRequest httpServletRequest) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(articleService.listArticleByPage(request, loginUser));
    }

    @PostMapping("/delete")
    @Operation(summary = "Delete article")
    public BaseResponse<Boolean> deleteArticle(@RequestBody DeleteRequest deleteRequest,
                                               HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(articleService.deleteArticle(deleteRequest.getId(), loginUser));
    }

    @PostMapping("/confirm-title")
    @Operation(summary = "Confirm title")
    public BaseResponse<Void> confirmTitle(@RequestBody ArticleConfirmTitleRequest request,
                                           HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getTaskId() == null || request.getTaskId().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Task id cannot be empty");
        ThrowUtils.throwIf(request.getSelectedMainTitle() == null || request.getSelectedMainTitle().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Main title cannot be empty");
        ThrowUtils.throwIf(request.getSelectedSubTitle() == null || request.getSelectedSubTitle().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Sub title cannot be empty");

        User loginUser = userService.getLoginUser(httpServletRequest);
        articleService.confirmTitle(
                request.getTaskId(),
                request.getSelectedMainTitle(),
                request.getSelectedSubTitle(),
                request.getUserDescription(),
                loginUser
        );
        articleAsyncService.executePhase2(request.getTaskId());
        return ResultUtils.success(null);
    }

    @PostMapping("/confirm-outline")
    @Operation(summary = "Confirm outline")
    public BaseResponse<Void> confirmOutline(@RequestBody ArticleConfirmOutlineRequest request,
                                             HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getTaskId() == null || request.getTaskId().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Task id cannot be empty");
        ThrowUtils.throwIf(request.getOutline() == null || request.getOutline().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Outline cannot be empty");

        User loginUser = userService.getLoginUser(httpServletRequest);
        articleService.confirmOutline(request.getTaskId(), request.getOutline(), loginUser);
        articleAsyncService.executePhase3(request.getTaskId());
        return ResultUtils.success(null);
    }

    @PostMapping("/ai-modify-outline")
    @Operation(summary = "AI modify outline")
    public BaseResponse<List<ArticleState.OutlineSection>> aiModifyOutline(
            @RequestBody ArticleAiModifyOutlineRequest request,
            HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getTaskId() == null || request.getTaskId().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Task id cannot be empty");
        ThrowUtils.throwIf(request.getModifySuggestion() == null || request.getModifySuggestion().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Modify suggestion cannot be empty");

        User loginUser = userService.getLoginUser(httpServletRequest);
        return ResultUtils.success(articleService.aiModifyOutline(
                request.getTaskId(),
                request.getModifySuggestion(),
                loginUser
        ));
    }

    @GetMapping("/execution-logs/{taskId}")
    @Operation(summary = "Get task execution logs")
    public BaseResponse<AgentExecutionStats> getExecutionLogs(@PathVariable String taskId) {
        ThrowUtils.throwIf(taskId == null || taskId.trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Task id cannot be empty");
        return ResultUtils.success(agentLogService.getExecutionStats(taskId));
    }

    @PostMapping("/resume")
    @Operation(summary = "Resume or retry an existing task")
    public BaseResponse<ArticleTaskSnapshotVO> resumeTask(@RequestBody ArticleResumeRequest request,
                                                          HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getTaskId() == null || request.getTaskId().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Task id cannot be empty");

        User loginUser = userService.getLoginUser(httpServletRequest);
        ArticleTaskSnapshotVO snapshot = articleService.resumeTask(request.getTaskId(), loginUser);
        articleAsyncService.resumeTask(request.getTaskId());
        return ResultUtils.success(snapshot);
    }

    @PostMapping("/retry-node")
    @Operation(summary = "Retry a failed node from its mapped phase")
    public BaseResponse<ArticleTaskSnapshotVO> retryNode(@RequestBody ArticleRetryNodeRequest request,
                                                         HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getTaskId() == null || request.getTaskId().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Task id cannot be empty");
        ThrowUtils.throwIf(request.getNode() == null || request.getNode().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "Node cannot be empty");

        User loginUser = userService.getLoginUser(httpServletRequest);
        ArticleTaskSnapshotVO snapshot = articleService.retryNode(request.getTaskId(), request.getNode(), loginUser);
        articleAsyncService.resumeTask(request.getTaskId());
        return ResultUtils.success(snapshot);
    }
}
