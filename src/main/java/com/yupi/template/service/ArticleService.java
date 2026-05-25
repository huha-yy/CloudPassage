package com.yupi.template.service;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;
import com.yupi.template.model.dto.article.ArticleQueryRequest;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.Article;
import com.yupi.template.model.entity.User;
import com.yupi.template.model.enums.ArticlePhaseEnum;
import com.yupi.template.model.enums.ArticleStatusEnum;
import com.yupi.template.model.vo.ArticleMemoryContextVO;
import com.yupi.template.model.vo.ArticleTaskMemoryVO;
import com.yupi.template.model.vo.ArticleTaskSnapshotVO;
import com.yupi.template.model.vo.ArticleVO;
import com.yupi.template.model.vo.NodeReplaySnapshotVO;
import com.yupi.template.model.vo.UserCreationPreferenceVO;

import java.util.List;

/**
 * Article service contract.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
public interface ArticleService extends IService<Article> {

    String createArticleTask(String topic, String style, List<String> enabledImageMethods, User loginUser);

    String createArticleTaskWithQuotaCheck(String topic, String style, List<String> enabledImageMethods, User loginUser);

    Article getByTaskId(String taskId);

    ArticleVO getArticleDetail(String taskId, User loginUser);

    ArticleTaskSnapshotVO getTaskSnapshot(String taskId, User loginUser);

    ArticleTaskMemoryVO getTaskMemory(String taskId, User loginUser);

    UserCreationPreferenceVO getUserCreationPreference(User loginUser);

    ArticleMemoryContextVO getCreationMemoryContext(String taskId, User loginUser);

    List<NodeReplaySnapshotVO> getNodeReplaySnapshots(String taskId, User loginUser);

    Page<ArticleVO> listArticleByPage(ArticleQueryRequest request, User loginUser);

    boolean deleteArticle(Long id, User loginUser);

    void updateArticleStatus(String taskId, ArticleStatusEnum status, String errorMessage);

    void saveArticleContent(String taskId, ArticleState state);

    void saveTaskSnapshot(ArticleState state, ArticleStatusEnum status, ArticlePhaseEnum phase, String errorMessage);

    void confirmTitle(String taskId, String mainTitle, String subTitle, String userDescription, User loginUser);

    void confirmOutline(String taskId, List<ArticleState.OutlineSection> outline, User loginUser);

    void updatePhase(String taskId, ArticlePhaseEnum phase);

    void saveTitleOptions(String taskId, List<ArticleState.TitleOption> titleOptions);

    List<ArticleState.OutlineSection> aiModifyOutline(String taskId, String modifySuggestion, User loginUser);

    ArticleTaskSnapshotVO resumeTask(String taskId, User loginUser);

    ArticleTaskSnapshotVO retryNode(String taskId, String node, User loginUser);
}
