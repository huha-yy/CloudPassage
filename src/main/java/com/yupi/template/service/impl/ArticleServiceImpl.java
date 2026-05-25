package com.yupi.template.service.impl;

import cn.hutool.core.util.IdUtil;
import com.google.gson.reflect.TypeToken;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.yupi.template.exception.BusinessException;
import com.yupi.template.exception.ErrorCode;
import com.yupi.template.exception.ThrowUtils;
import com.yupi.template.mapper.ArticleMapper;
import com.yupi.template.model.dto.article.ArticleQueryRequest;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.Article;
import com.yupi.template.model.entity.User;
import com.yupi.template.model.enums.ArticlePhaseEnum;
import com.yupi.template.model.enums.ArticleStatusEnum;
import com.yupi.template.model.enums.ImageMethodEnum;
import com.yupi.template.model.vo.ArticleMemoryContextVO;
import com.yupi.template.model.vo.ArticleTaskMemoryVO;
import com.yupi.template.model.vo.ArticleTaskSnapshotVO;
import com.yupi.template.model.vo.ArticleVO;
import com.yupi.template.model.vo.NodeReplaySnapshotVO;
import com.yupi.template.model.vo.UserCreationPreferenceVO;
import com.yupi.template.service.ArticleAgentService;
import com.yupi.template.service.ArticleMemoryService;
import com.yupi.template.service.ArticleNodeReplayService;
import com.yupi.template.service.ArticleService;
import com.yupi.template.service.ArticleTaskSnapshotService;
import com.yupi.template.service.QuotaService;
import com.yupi.template.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.yupi.template.constant.UserConstant.ADMIN_ROLE;
import static com.yupi.template.constant.UserConstant.VIP_ROLE;

/**
 * Article service implementation.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@Service
@Slf4j
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements ArticleService {

    @Resource
    private QuotaService quotaService;

    @Resource
    private ArticleAgentService articleAgentService;

    @Resource
    private ArticleTaskSnapshotService articleTaskSnapshotService;

    @Resource
    private ArticleMemoryService articleMemoryService;

    @Resource
    private ArticleNodeReplayService articleNodeReplayService;

    @Override
    public String createArticleTask(String topic, String style, List<String> enabledImageMethods, User loginUser) {
        UserCreationPreferenceVO preference = articleMemoryService.getUserPreference(loginUser.getId());
        String finalStyle = resolvePreferredStyle(style, preference);
        List<String> finalImageMethods = processImageMethods(
                resolvePreferredImageMethods(enabledImageMethods, preference),
                loginUser
        );
        validateImageMethods(finalImageMethods, loginUser);

        String taskId = IdUtil.simpleUUID();
        Article article = new Article();
        article.setTaskId(taskId);
        article.setUserId(loginUser.getId());
        article.setTopic(topic);
        article.setStyle(finalStyle);
        article.setEnabledImageMethods(finalImageMethods != null && !finalImageMethods.isEmpty()
                ? GsonUtils.toJson(finalImageMethods) : null);
        article.setStatus(ArticleStatusEnum.PENDING.getValue());
        article.setPhase(ArticlePhaseEnum.PENDING.getValue());
        article.setCreateTime(LocalDateTime.now());
        this.save(article);
        articleMemoryService.initializeTaskMemory(article);

        log.info("Article task created, taskId={}, userId={}, style={}", taskId, loginUser.getId(), finalStyle);
        return taskId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createArticleTaskWithQuotaCheck(String topic, String style, List<String> enabledImageMethods, User loginUser) {
        quotaService.checkAndConsumeQuota(loginUser);
        return createArticleTask(topic, style, enabledImageMethods, loginUser);
    }

    @Override
    public Article getByTaskId(String taskId) {
        return this.getOne(QueryWrapper.create().eq("taskId", taskId));
    }

    @Override
    public ArticleVO getArticleDetail(String taskId, User loginUser) {
        Article article = getArticleWithPermission(taskId, loginUser);
        return ArticleVO.objToVo(article);
    }

    @Override
    public ArticleTaskSnapshotVO getTaskSnapshot(String taskId, User loginUser) {
        Article article = getArticleWithPermission(taskId, loginUser);
        return articleTaskSnapshotService.getSnapshot(taskId, article);
    }

    @Override
    public ArticleTaskMemoryVO getTaskMemory(String taskId, User loginUser) {
        getArticleWithPermission(taskId, loginUser);
        return articleMemoryService.getTaskMemory(taskId);
    }

    @Override
    public UserCreationPreferenceVO getUserCreationPreference(User loginUser) {
        return articleMemoryService.getUserPreference(loginUser.getId());
    }

    @Override
    public ArticleMemoryContextVO getCreationMemoryContext(String taskId, User loginUser) {
        Article article = getArticleWithPermission(taskId, loginUser);
        return articleMemoryService.buildCreationMemoryContext(taskId, article.getUserId());
    }

    @Override
    public List<NodeReplaySnapshotVO> getNodeReplaySnapshots(String taskId, User loginUser) {
        getArticleWithPermission(taskId, loginUser);
        return articleNodeReplayService.getSnapshots(taskId);
    }

    @Override
    public Page<ArticleVO> listArticleByPage(ArticleQueryRequest request, User loginUser) {
        long current = request.getPageNum();
        long size = request.getPageSize();

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("isDelete", 0)
                .orderBy("createTime", false);

        if (!ADMIN_ROLE.equals(loginUser.getUserRole())) {
            queryWrapper.eq("userId", loginUser.getId());
        } else if (request.getUserId() != null) {
            queryWrapper.eq("userId", request.getUserId());
        }

        if (request.getStatus() != null && !request.getStatus().trim().isEmpty()) {
            queryWrapper.eq("status", request.getStatus());
        }

        Page<Article> articlePage = this.page(new Page<>(current, size), queryWrapper);
        return convertToVOPage(articlePage);
    }

    @Override
    public boolean deleteArticle(Long id, User loginUser) {
        Article article = this.getById(id);
        ThrowUtils.throwIf(article == null, ErrorCode.NOT_FOUND_ERROR);
        checkArticlePermission(article, loginUser);
        if (article.getTaskId() != null) {
            articleTaskSnapshotService.clearSnapshot(article.getTaskId());
            articleNodeReplayService.clear(article.getTaskId());
        }
        return this.removeById(id);
    }

    @Override
    public void updateArticleStatus(String taskId, ArticleStatusEnum status, String errorMessage) {
        Article article = getByTaskId(taskId);
        if (article == null) {
            log.error("Article record not found, taskId={}", taskId);
            return;
        }
        article.setStatus(status.getValue());
        article.setErrorMessage(errorMessage);
        this.updateById(article);
        log.info("Article status updated, taskId={}, status={}", taskId, status.getValue());
    }

    @Override
    public void saveArticleContent(String taskId, ArticleState state) {
        Article article = getByTaskId(taskId);
        if (article == null) {
            log.error("Article record not found, taskId={}", taskId);
            return;
        }

        if (state.getTitle() != null) {
            article.setMainTitle(state.getTitle().getMainTitle());
            article.setSubTitle(state.getTitle().getSubTitle());
        }
        if (state.getOutline() != null) {
            article.setOutline(GsonUtils.toJson(state.getOutline().getSections()));
        }
        article.setContent(state.getContent());
        article.setFullContent(state.getFullContent());
        if (state.getImages() != null && !state.getImages().isEmpty()) {
            ArticleState.ImageResult cover = state.getImages().stream()
                    .filter(img -> img.getPosition() != null && img.getPosition() == 1)
                    .findFirst()
                    .orElse(null);
            if (cover != null && cover.getUrl() != null) {
                article.setCoverImage(cover.getUrl());
            }
        }
        article.setImages(GsonUtils.toJson(state.getImages()));
        article.setCompletedTime(LocalDateTime.now());
        this.updateById(article);
        log.info("Article content saved, taskId={}", taskId);
    }

    @Override
    public void saveTaskSnapshot(ArticleState state, ArticleStatusEnum status, ArticlePhaseEnum phase, String errorMessage) {
        articleTaskSnapshotService.saveSnapshot(state, status, phase, errorMessage);
    }

    @Override
    public void confirmTitle(String taskId, String mainTitle, String subTitle, String userDescription, User loginUser) {
        Article article = getArticleWithPermission(taskId, loginUser);
        ArticlePhaseEnum currentPhase = ArticlePhaseEnum.getByValue(article.getPhase());
        ThrowUtils.throwIf(currentPhase != ArticlePhaseEnum.TITLE_SELECTING,
                ErrorCode.OPERATION_ERROR, "Current phase does not allow title confirmation");

        article.setMainTitle(mainTitle);
        article.setSubTitle(subTitle);
        article.setUserDescription(userDescription);
        article.setPhase(ArticlePhaseEnum.OUTLINE_GENERATING.getValue());
        this.updateById(article);

        ArticleState snapshotState = new ArticleState();
        snapshotState.setTaskId(taskId);
        snapshotState.setTopic(article.getTopic());
        snapshotState.setStyle(article.getStyle());
        snapshotState.setUserDescription(userDescription);
        snapshotState.setPhase(ArticlePhaseEnum.OUTLINE_GENERATING.getValue());
        snapshotState.setProgress(2);
        ArticleState.TitleResult title = new ArticleState.TitleResult();
        title.setMainTitle(mainTitle);
        title.setSubTitle(subTitle);
        snapshotState.setTitle(title);
        articleTaskSnapshotService.saveSnapshot(snapshotState, ArticleStatusEnum.PROCESSING,
                ArticlePhaseEnum.OUTLINE_GENERATING, null);
        articleMemoryService.recordTitleConfirmed(article);

        log.info("Title confirmed, taskId={}, mainTitle={}", taskId, mainTitle);
    }

    @Override
    public void confirmOutline(String taskId, List<ArticleState.OutlineSection> outline, User loginUser) {
        Article article = getArticleWithPermission(taskId, loginUser);
        ArticlePhaseEnum currentPhase = ArticlePhaseEnum.getByValue(article.getPhase());
        ThrowUtils.throwIf(currentPhase != ArticlePhaseEnum.OUTLINE_EDITING,
                ErrorCode.OPERATION_ERROR, "Current phase does not allow outline confirmation");

        article.setOutline(GsonUtils.toJson(outline));
        article.setPhase(ArticlePhaseEnum.CONTENT_GENERATING.getValue());
        this.updateById(article);

        ArticleState snapshotState = new ArticleState();
        snapshotState.setTaskId(taskId);
        snapshotState.setTopic(article.getTopic());
        snapshotState.setStyle(article.getStyle());
        snapshotState.setUserDescription(article.getUserDescription());
        snapshotState.setPhase(ArticlePhaseEnum.CONTENT_GENERATING.getValue());
        snapshotState.setProgress(3);
        if (article.getMainTitle() != null || article.getSubTitle() != null) {
            ArticleState.TitleResult title = new ArticleState.TitleResult();
            title.setMainTitle(article.getMainTitle());
            title.setSubTitle(article.getSubTitle());
            snapshotState.setTitle(title);
        }
        ArticleState.OutlineResult outlineResult = new ArticleState.OutlineResult();
        outlineResult.setSections(outline);
        snapshotState.setOutline(outlineResult);
        snapshotState.setOutlineRaw(GsonUtils.toJson(Map.of("sections", outline)));
        articleTaskSnapshotService.saveSnapshot(snapshotState, ArticleStatusEnum.PROCESSING,
                ArticlePhaseEnum.CONTENT_GENERATING, null);
        articleMemoryService.recordOutlineConfirmed(taskId, outline);

        log.info("Outline confirmed, taskId={}, sectionsCount={}", taskId, outline.size());
    }

    @Override
    public void updatePhase(String taskId, ArticlePhaseEnum phase) {
        Article article = getByTaskId(taskId);
        if (article == null) {
            log.error("Article record not found, taskId={}", taskId);
            return;
        }
        article.setPhase(phase.getValue());
        this.updateById(article);
        log.info("Article phase updated, taskId={}, phase={}", taskId, phase.getValue());
    }

    @Override
    public void saveTitleOptions(String taskId, List<ArticleState.TitleOption> titleOptions) {
        Article article = getByTaskId(taskId);
        if (article == null) {
            log.error("Article record not found, taskId={}", taskId);
            return;
        }
        article.setTitleOptions(GsonUtils.toJson(titleOptions));
        this.updateById(article);
        log.info("Title options saved, taskId={}, optionsCount={}", taskId, titleOptions.size());
    }

    @Override
    public List<ArticleState.OutlineSection> aiModifyOutline(String taskId, String modifySuggestion, User loginUser) {
        Article article = getArticleWithPermission(taskId, loginUser);
        ThrowUtils.throwIf(!isVipOrAdmin(loginUser), ErrorCode.NO_AUTH_ERROR,
                "AI outline modification is only available for VIP users");

        ArticlePhaseEnum currentPhase = ArticlePhaseEnum.getByValue(article.getPhase());
        ThrowUtils.throwIf(currentPhase != ArticlePhaseEnum.OUTLINE_EDITING,
                ErrorCode.OPERATION_ERROR, "Current phase does not allow outline modification");

        List<ArticleState.OutlineSection> currentOutline = GsonUtils.fromJson(
                article.getOutline(),
                new TypeToken<List<ArticleState.OutlineSection>>() {
                }
        );

        List<ArticleState.OutlineSection> modifiedOutline = articleAgentService.aiModifyOutline(
                article.getMainTitle(),
                article.getSubTitle(),
                currentOutline,
                modifySuggestion
        );

        article.setOutline(GsonUtils.toJson(modifiedOutline));
        this.updateById(article);

        log.info("AI outline modification finished, taskId={}, sectionsCount={}", taskId, modifiedOutline.size());
        return modifiedOutline;
    }

    @Override
    public ArticleTaskSnapshotVO resumeTask(String taskId, User loginUser) {
        Article article = getArticleWithPermission(taskId, loginUser);
        ThrowUtils.throwIf(article == null, ErrorCode.NOT_FOUND_ERROR, "Article not found");

        ArticleTaskSnapshotVO snapshot = articleTaskSnapshotService.getSnapshot(taskId, article);
        ThrowUtils.throwIf(snapshot == null, ErrorCode.NOT_FOUND_ERROR, "Task snapshot not found");

        String phaseValue = snapshot.getPhase();
        ArticlePhaseEnum phase = ArticlePhaseEnum.getByValue(phaseValue);
        ThrowUtils.throwIf(phase == null, ErrorCode.OPERATION_ERROR, "Current task phase cannot be resumed");

        String statusValue = snapshot.getStatus();
        ArticleStatusEnum status = ArticleStatusEnum.getByValue(statusValue);
        ThrowUtils.throwIf(status == ArticleStatusEnum.COMPLETED,
                ErrorCode.OPERATION_ERROR, "Completed task does not need to resume");

        ThrowUtils.throwIf(status != ArticleStatusEnum.FAILED && status != ArticleStatusEnum.PROCESSING
                        && status != ArticleStatusEnum.PENDING,
                ErrorCode.OPERATION_ERROR, "Current task status does not support resume");

        article.setStatus(ArticleStatusEnum.PROCESSING.getValue());
        article.setErrorMessage(null);
        article.setPhase(phase.getValue());
        this.updateById(article);

        snapshot.setStatus(ArticleStatusEnum.PROCESSING.getValue());
        snapshot.setErrorMessage(null);

        ArticleState resumeState = new ArticleState();
        resumeState.setTaskId(taskId);
        resumeState.setTopic(snapshot.getTopic());
        resumeState.setStyle(snapshot.getStyle());
        resumeState.setUserDescription(snapshot.getUserDescription());
        resumeState.setPhase(phase.getValue());
        resumeState.setProgress(snapshot.getProgress());
        if (snapshot.getTitle() != null) {
            resumeState.setTitle(snapshot.getTitle());
        }
        if (snapshot.getTitleOptions() != null) {
            resumeState.setTitleOptions(snapshot.getTitleOptions());
        }
        if (snapshot.getOutline() != null) {
            ArticleState.OutlineResult outlineResult = new ArticleState.OutlineResult();
            outlineResult.setSections(snapshot.getOutline());
            resumeState.setOutline(outlineResult);
        }
        resumeState.setOutlineRaw(snapshot.getOutlineRaw());
        resumeState.setContent(snapshot.getContent());
        resumeState.setFullContent(snapshot.getFullContent());
        resumeState.setEnabledImageMethods(snapshot.getEnabledImageMethods());
        if (snapshot.getImageRequirements() != null) {
            resumeState.setImageRequirements(snapshot.getImageRequirements());
        }
        if (snapshot.getImages() != null) {
            resumeState.setImages(snapshot.getImages());
        }
        articleTaskSnapshotService.saveSnapshot(resumeState, ArticleStatusEnum.PROCESSING, phase, null);
        articleMemoryService.recordTaskResume(taskId, phase.getValue(), "phase_resume", null);

        log.info("Task marked resumable, taskId={}, phase={}, status={}", taskId, phaseValue, statusValue);
        return articleTaskSnapshotService.getSnapshot(taskId, article);
    }

    @Override
    public ArticleTaskSnapshotVO retryNode(String taskId, String node, User loginUser) {
        ThrowUtils.throwIf(node == null || node.isBlank(), ErrorCode.PARAMS_ERROR, "Node cannot be empty");

        Article article = getArticleWithPermission(taskId, loginUser);
        ThrowUtils.throwIf(article == null, ErrorCode.NOT_FOUND_ERROR, "Article not found");

        ArticleTaskSnapshotVO snapshot = articleTaskSnapshotService.getSnapshot(taskId, article);
        ThrowUtils.throwIf(snapshot == null, ErrorCode.NOT_FOUND_ERROR, "Task snapshot not found");

        NodeReplaySnapshotVO latestReplaySnapshot = articleNodeReplayService.getLatestSnapshot(taskId, node);
        ThrowUtils.throwIf(latestReplaySnapshot == null, ErrorCode.OPERATION_ERROR,
                "Current node has no replay snapshot");
        ThrowUtils.throwIf(Boolean.FALSE.equals(latestReplaySnapshot.getReplayable()), ErrorCode.OPERATION_ERROR,
                "Current node is still running and cannot be retried");

        String mappedPhase = firstNonBlank(latestReplaySnapshot.getPhase(), mapRetryPhase(node));
        ThrowUtils.throwIf(mappedPhase == null, ErrorCode.OPERATION_ERROR, "Current node does not support retry");

        String snapshotPhase = snapshot.getPhase();
        ThrowUtils.throwIf(snapshotPhase == null, ErrorCode.OPERATION_ERROR, "Current task phase cannot be resolved");

        boolean samePhase = mappedPhase.equals(snapshotPhase);
        boolean phaseEscalation = ArticlePhaseEnum.CONTENT_GENERATING.getValue().equals(snapshotPhase)
                && ArticlePhaseEnum.OUTLINE_GENERATING.getValue().equals(mappedPhase);
        ThrowUtils.throwIf(!samePhase && !phaseEscalation,
                ErrorCode.OPERATION_ERROR, "Retry node does not belong to current recoverable phase");

        log.info("Retry node requested, taskId={}, node={}, mappedPhase={}, currentPhase={}",
                taskId, node, mappedPhase, snapshotPhase);
        articleMemoryService.recordTaskResume(taskId, snapshotPhase, "node_retry", node);
        return resumeTask(taskId, loginUser);
    }

    private Article getArticleWithPermission(String taskId, User loginUser) {
        Article article = getByTaskId(taskId);
        ThrowUtils.throwIf(article == null, ErrorCode.NOT_FOUND_ERROR, "Article not found");
        checkArticlePermission(article, loginUser);
        return article;
    }

    private void checkArticlePermission(Article article, User loginUser) {
        if (!article.getUserId().equals(loginUser.getId()) && !ADMIN_ROLE.equals(loginUser.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }

    private Page<ArticleVO> convertToVOPage(Page<Article> articlePage) {
        Page<ArticleVO> articleVOPage = new Page<>();
        articleVOPage.setPageNumber(articlePage.getPageNumber());
        articleVOPage.setPageSize(articlePage.getPageSize());
        articleVOPage.setTotalRow(articlePage.getTotalRow());
        List<ArticleVO> articleVOList = articlePage.getRecords().stream()
                .map(ArticleVO::objToVo)
                .collect(Collectors.toList());
        articleVOPage.setRecords(articleVOList);
        return articleVOPage;
    }

    private List<String> processImageMethods(List<String> enabledImageMethods, User loginUser) {
        if (enabledImageMethods != null && !enabledImageMethods.isEmpty()) {
            return enabledImageMethods;
        }
        if (isVipOrAdmin(loginUser)) {
            return null;
        }
        return List.of(
                ImageMethodEnum.PEXELS.getValue(),
                ImageMethodEnum.MERMAID.getValue(),
                ImageMethodEnum.ICONIFY.getValue(),
                ImageMethodEnum.EMOJI_PACK.getValue()
        );
    }

    private void validateImageMethods(List<String> enabledImageMethods, User loginUser) {
        if (enabledImageMethods == null || enabledImageMethods.isEmpty() || isVipOrAdmin(loginUser)) {
            return;
        }
        for (String method : enabledImageMethods) {
            if (ImageMethodEnum.NANO_BANANA.getValue().equals(method)
                    || ImageMethodEnum.SVG_DIAGRAM.getValue().equals(method)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                        "Advanced image methods are only available for VIP users");
            }
        }
    }

    private boolean isVipOrAdmin(User user) {
        return ADMIN_ROLE.equals(user.getUserRole()) || VIP_ROLE.equals(user.getUserRole());
    }

    private String resolvePreferredStyle(String style, UserCreationPreferenceVO preference) {
        if (style != null && !style.isBlank()) {
            return style;
        }
        if (preference == null || preference.getPreferredStyle() == null || preference.getPreferredStyle().isBlank()) {
            return style;
        }
        return preference.getPreferredStyle();
    }

    private List<String> resolvePreferredImageMethods(List<String> enabledImageMethods,
                                                      UserCreationPreferenceVO preference) {
        if (enabledImageMethods != null && !enabledImageMethods.isEmpty()) {
            return enabledImageMethods;
        }
        if (preference == null || preference.getPreferredImageMethods() == null
                || preference.getPreferredImageMethods().isEmpty()) {
            return enabledImageMethods;
        }
        return preference.getPreferredImageMethods();
    }

    private String mapRetryPhase(String node) {
        return switch (node) {
            case "workflow_phase_1", "agent1_generate_titles" -> ArticlePhaseEnum.TITLE_GENERATING.getValue();
            case "workflow_phase_2", "agent2_generate_outline", "ai_modify_outline" ->
                    ArticlePhaseEnum.OUTLINE_GENERATING.getValue();
            case "workflow_phase_3", "agent3_generate_content", "agent3_review_content", "agent4_analyze_image_requirements",
                    "agent5_generate_images", "agent6_merge_content" ->
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue();
            default -> null;
        };
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }
}
