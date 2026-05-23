package com.yupi.template.service.impl;

import com.google.gson.reflect.TypeToken;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.Article;
import com.yupi.template.model.enums.ArticlePhaseEnum;
import com.yupi.template.model.enums.ArticleStatusEnum;
import com.yupi.template.model.enums.SseMessageTypeEnum;
import com.yupi.template.model.vo.ArticleSseMessageVO;
import com.yupi.template.model.vo.ArticleTaskSnapshotVO;
import com.yupi.template.service.ArticleTaskSnapshotService;
import com.yupi.template.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed article task snapshots.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@Service
@Slf4j
public class ArticleTaskSnapshotServiceImpl implements ArticleTaskSnapshotService {

    private static final String SNAPSHOT_KEY_PREFIX = "article:task:snapshot:";
    private static final long SNAPSHOT_TTL_HOURS = 24L;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public void saveSnapshot(ArticleState state, ArticleStatusEnum status, ArticlePhaseEnum phase, String errorMessage) {
        if (state == null || state.getTaskId() == null || status == null || phase == null) {
            return;
        }
        ArticleTaskSnapshotVO snapshot = readSnapshot(state.getTaskId());
        if (snapshot == null) {
            snapshot = ArticleTaskSnapshotVO.builder().taskId(state.getTaskId()).build();
        }
        mergeState(snapshot, state);
        snapshot.setStatus(status.getValue());
        snapshot.setPhase(phase.getValue());
        snapshot.setProgress(resolveProgress(phase.getValue(), status.getValue(), state.getProgress(), snapshot.getProgress()));
        snapshot.setErrorMessage(errorMessage);
        snapshot.setUpdatedAt(System.currentTimeMillis());
        persist(snapshot);
    }

    @Override
    public void applyEvent(String taskId, ArticleSseMessageVO event, ArticleState state) {
        if (taskId == null || event == null) {
            return;
        }
        ArticleTaskSnapshotVO snapshot = readSnapshot(taskId);
        if (snapshot == null) {
            snapshot = ArticleTaskSnapshotVO.builder().taskId(taskId).build();
        }
        mergeState(snapshot, state);
        snapshot.setPhase(firstNonBlank(event.getPhase(), snapshot.getPhase()));
        snapshot.setProgress(resolveProgress(snapshot.getPhase(), snapshot.getStatus(), event.getProgress(), snapshot.getProgress()));
        snapshot.setUpdatedAt(event.getTimestamp() != null ? event.getTimestamp() : System.currentTimeMillis());

        String type = event.getType();
        if (SseMessageTypeEnum.TITLES_GENERATED.getValue().equals(type)) {
            snapshot.setTitleOptions(readPayloadField(event, "titleOptions", new TypeToken<List<ArticleState.TitleOption>>() {
            }));
            snapshot.setStatus(ArticleStatusEnum.PROCESSING.getValue());
        } else if (SseMessageTypeEnum.AGENT2_STREAMING.getValue().equals(type)) {
            String chunk = readPayloadString(event, "content");
            if (chunk != null) {
                snapshot.setOutlineRaw(defaultString(snapshot.getOutlineRaw()) + chunk);
            }
        } else if (SseMessageTypeEnum.OUTLINE_GENERATED.getValue().equals(type)
                || SseMessageTypeEnum.AGENT2_COMPLETE.getValue().equals(type)) {
            snapshot.setOutline(readPayloadField(event, "outline", new TypeToken<List<ArticleState.OutlineSection>>() {
            }));
            snapshot.setStatus(ArticleStatusEnum.PROCESSING.getValue());
        } else if (SseMessageTypeEnum.AGENT3_STREAMING.getValue().equals(type)) {
            String chunk = readPayloadString(event, "content");
            if (chunk != null) {
                snapshot.setContent(defaultString(snapshot.getContent()) + chunk);
            }
        } else if (SseMessageTypeEnum.AGENT4_COMPLETE.getValue().equals(type)) {
            snapshot.setImageRequirements(readPayloadField(event, "imageRequirements", new TypeToken<List<ArticleState.ImageRequirement>>() {
            }));
        } else if (SseMessageTypeEnum.IMAGE_COMPLETE.getValue().equals(type)) {
            ArticleState.ImageResult image = readPayloadField(event, "image", ArticleState.ImageResult.class);
            if (image != null) {
                List<ArticleState.ImageResult> images = snapshot.getImages();
                if (images == null) {
                    images = new ArrayList<>();
                    snapshot.setImages(images);
                }
                images.add(image);
            }
        } else if (SseMessageTypeEnum.AGENT5_COMPLETE.getValue().equals(type)) {
            snapshot.setImages(readPayloadField(event, "images", new TypeToken<List<ArticleState.ImageResult>>() {
            }));
        } else if (SseMessageTypeEnum.MERGE_COMPLETE.getValue().equals(type)) {
            snapshot.setFullContent(readPayloadString(event, "fullContent"));
        } else if (SseMessageTypeEnum.ERROR.getValue().equals(type)) {
            snapshot.setStatus(ArticleStatusEnum.FAILED.getValue());
            snapshot.setErrorMessage(readPayloadString(event, "message"));
        } else if (SseMessageTypeEnum.ALL_COMPLETE.getValue().equals(type)) {
            snapshot.setStatus(ArticleStatusEnum.COMPLETED.getValue());
            snapshot.setProgress(6);
            if (state != null && state.getFullContent() != null) {
                snapshot.setFullContent(state.getFullContent());
            }
        }
        persist(snapshot);
    }

    @Override
    public ArticleTaskSnapshotVO getSnapshot(String taskId, Article article) {
        ArticleTaskSnapshotVO snapshot = readSnapshot(taskId);
        if (snapshot != null) {
            return snapshot;
        }
        if (article == null) {
            return null;
        }
        return buildFromArticle(article);
    }

    @Override
    public void clearSnapshot(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            redisTemplate.delete(buildKey(taskId));
        }
    }

    private void mergeState(ArticleTaskSnapshotVO snapshot, ArticleState state) {
        if (snapshot == null || state == null) {
            return;
        }
        snapshot.setTaskId(firstNonBlank(state.getTaskId(), snapshot.getTaskId()));
        snapshot.setTopic(firstNonBlank(state.getTopic(), snapshot.getTopic()));
        snapshot.setStyle(firstNonBlank(state.getStyle(), snapshot.getStyle()));
        snapshot.setUserDescription(firstNonBlank(state.getUserDescription(), snapshot.getUserDescription()));
        snapshot.setPhase(firstNonBlank(state.getPhase(), snapshot.getPhase()));
        snapshot.setProgress(state.getProgress() != null ? state.getProgress() : snapshot.getProgress());
        snapshot.setOutlineRaw(firstNonBlank(state.getOutlineRaw(), snapshot.getOutlineRaw()));
        snapshot.setErrorMessage(firstNonBlank(state.getErrorMessage(), snapshot.getErrorMessage()));
        if (state.getTitleOptions() != null) {
            snapshot.setTitleOptions(state.getTitleOptions());
        }
        if (state.getTitle() != null) {
            snapshot.setTitle(state.getTitle());
        }
        if (state.getOutline() != null) {
            snapshot.setOutline(state.getOutline().getSections());
        }
        if (state.getContent() != null) {
            snapshot.setContent(state.getContent());
        }
        if (state.getFullContent() != null) {
            snapshot.setFullContent(state.getFullContent());
        }
        if (state.getEnabledImageMethods() != null) {
            snapshot.setEnabledImageMethods(state.getEnabledImageMethods());
        }
        if (state.getImageRequirements() != null) {
            snapshot.setImageRequirements(state.getImageRequirements());
        }
        if (state.getImages() != null) {
            snapshot.setImages(state.getImages());
        }
    }

    private ArticleTaskSnapshotVO readSnapshot(String taskId) {
        Object cached = redisTemplate.opsForValue().get(buildKey(taskId));
        if (cached == null) {
            return null;
        }
        if (cached instanceof ArticleTaskSnapshotVO snapshotVO) {
            return snapshotVO;
        }
        if (cached instanceof String json) {
            return GsonUtils.fromJsonSafe(json, ArticleTaskSnapshotVO.class);
        }
        return GsonUtils.fromJsonSafe(GsonUtils.toJson(cached), ArticleTaskSnapshotVO.class);
    }

    private void persist(ArticleTaskSnapshotVO snapshot) {
        redisTemplate.opsForValue().set(
                buildKey(snapshot.getTaskId()),
                GsonUtils.toJson(snapshot),
                SNAPSHOT_TTL_HOURS,
                TimeUnit.HOURS
        );
    }

    private String buildKey(String taskId) {
        return SNAPSHOT_KEY_PREFIX + taskId;
    }

    private ArticleTaskSnapshotVO buildFromArticle(Article article) {
        ArticleState.TitleResult title = null;
        if (article.getMainTitle() != null || article.getSubTitle() != null) {
            title = new ArticleState.TitleResult();
            title.setMainTitle(article.getMainTitle());
            title.setSubTitle(article.getSubTitle());
        }
        String status = article.getStatus();
        String phase = article.getPhase();
        Integer progress = resolveProgress(phase, status, null, null);
        return ArticleTaskSnapshotVO.builder()
                .taskId(article.getTaskId())
                .topic(article.getTopic())
                .style(article.getStyle())
                .userDescription(article.getUserDescription())
                .status(status)
                .phase(phase)
                .progress(progress)
                .errorMessage(article.getErrorMessage())
                .titleOptions(parseJson(article.getTitleOptions(), new TypeToken<List<ArticleState.TitleOption>>() {
                }))
                .title(title)
                .outline(parseJson(article.getOutline(), new TypeToken<List<ArticleState.OutlineSection>>() {
                }))
                .content(article.getContent())
                .fullContent(article.getFullContent())
                .enabledImageMethods(parseJson(article.getEnabledImageMethods(), new TypeToken<List<String>>() {
                }))
                .images(parseJson(article.getImages(), new TypeToken<List<ArticleState.ImageResult>>() {
                }))
                .updatedAt(article.getUpdateTime() != null
                        ? article.getUpdateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        : System.currentTimeMillis())
                .build();
    }

    private <T> T parseJson(String json, TypeToken<T> typeToken) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return GsonUtils.fromJsonSafe(json, typeToken);
    }

    private <T> T readPayloadField(ArticleSseMessageVO event, String key, Class<T> clazz) {
        Object value = readPayloadValue(event, key);
        if (value == null) {
            return null;
        }
        if (clazz.isInstance(value)) {
            return clazz.cast(value);
        }
        return GsonUtils.fromJsonSafe(GsonUtils.toJson(value), clazz);
    }

    private <T> T readPayloadField(ArticleSseMessageVO event, String key, TypeToken<T> typeToken) {
        Object value = readPayloadValue(event, key);
        if (value == null) {
            return null;
        }
        return GsonUtils.fromJsonSafe(GsonUtils.toJson(value), typeToken);
    }

    private Object readPayloadValue(ArticleSseMessageVO event, String key) {
        Object payload = event.getPayload();
        if (payload instanceof Map<?, ?> payloadMap) {
            return payloadMap.get(key);
        }
        return null;
    }

    private String readPayloadString(ArticleSseMessageVO event, String key) {
        Object value = readPayloadValue(event, key);
        return value == null ? null : String.valueOf(value);
    }

    private Integer resolveProgress(String phase, String status, Integer preferredProgress, Integer fallbackProgress) {
        if (ArticleStatusEnum.COMPLETED.getValue().equals(status)) {
            return 6;
        }
        if (preferredProgress != null && preferredProgress > 0) {
            return preferredProgress;
        }
        if (phase == null) {
            return fallbackProgress != null ? fallbackProgress : 0;
        }
        return switch (phase) {
            case "TITLE_GENERATING", "TITLE_SELECTING" -> 1;
            case "OUTLINE_GENERATING", "OUTLINE_EDITING" -> 2;
            case "CONTENT_GENERATING" -> fallbackProgress != null && fallbackProgress > 2 ? fallbackProgress : 3;
            default -> fallbackProgress != null ? fallbackProgress : 0;
        };
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
