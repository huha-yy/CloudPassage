package com.yupi.template.utils;

import cn.hutool.core.util.StrUtil;
import com.yupi.template.model.dto.article.ArticleState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds stable placeholder ids and injects them into markdown locally so the
 * image analysis node does not need to echo the full article body.
 */
public final class ImagePlaceholderUtils {

    private ImagePlaceholderUtils() {
    }

    public static String applyPlaceholders(String content, List<ArticleState.ImageRequirement> requirements) {
        if (StrUtil.isBlank(content) || requirements == null || requirements.isEmpty()) {
            return content;
        }

        assignPlaceholderIds(requirements);

        String normalizedContent = content.replace("\r\n", "\n");
        String[] lines = normalizedContent.split("\n", -1);
        List<String> resultLines = new ArrayList<>();
        Map<String, List<String>> placeholdersBySection = groupSectionPlaceholders(requirements);
        List<String> unplaced = collectUnplacedPlaceholders(requirements);

        for (String line : lines) {
            resultLines.add(line);
            String headingTitle = extractHeadingTitle(line);
            if (headingTitle == null) {
                continue;
            }
            List<String> placeholders = placeholdersBySection.remove(headingTitle);
            if (placeholders == null || placeholders.isEmpty()) {
                continue;
            }
            appendPlaceholders(resultLines, placeholders);
        }

        placeholdersBySection.values().forEach(unplaced::addAll);
        if (!unplaced.isEmpty()) {
            if (!resultLines.isEmpty() && !resultLines.get(resultLines.size() - 1).isBlank()) {
                resultLines.add("");
            }
            appendPlaceholders(resultLines, unplaced);
        }
        return String.join("\n", resultLines);
    }

    public static void assignPlaceholderIds(List<ArticleState.ImageRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return;
        }
        int imageIndex = 1;
        int iconIndex = 1;
        for (ArticleState.ImageRequirement requirement : requirements) {
            if (requirement == null || isCover(requirement)) {
                if (requirement != null && StrUtil.isBlank(requirement.getPlaceholderId())) {
                    requirement.setPlaceholderId("");
                }
                continue;
            }
            if (StrUtil.isNotBlank(requirement.getPlaceholderId())) {
                continue;
            }
            if (isIcon(requirement)) {
                requirement.setPlaceholderId("{{ICON_PLACEHOLDER_" + iconIndex++ + "}}");
            } else {
                requirement.setPlaceholderId("{{IMAGE_PLACEHOLDER_" + imageIndex++ + "}}");
            }
        }
    }

    private static Map<String, List<String>> groupSectionPlaceholders(List<ArticleState.ImageRequirement> requirements) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (ArticleState.ImageRequirement requirement : requirements) {
            if (requirement == null || isCover(requirement) || StrUtil.isBlank(requirement.getPlaceholderId())) {
                continue;
            }
            String sectionTitle = normalizeTitle(requirement.getSectionTitle());
            if (sectionTitle == null) {
                continue;
            }
            result.computeIfAbsent(sectionTitle, key -> new ArrayList<>()).add(requirement.getPlaceholderId());
        }
        return result;
    }

    private static List<String> collectUnplacedPlaceholders(List<ArticleState.ImageRequirement> requirements) {
        List<String> result = new ArrayList<>();
        for (ArticleState.ImageRequirement requirement : requirements) {
            if (requirement == null || isCover(requirement) || StrUtil.isBlank(requirement.getPlaceholderId())) {
                continue;
            }
            if (normalizeTitle(requirement.getSectionTitle()) == null) {
                result.add(requirement.getPlaceholderId());
            }
        }
        return result;
    }

    private static void appendPlaceholders(List<String> lines, List<String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return;
        }
        if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
            lines.add("");
        }
        for (String placeholder : placeholders) {
            lines.add(placeholder);
        }
        lines.add("");
    }

    private static String extractHeadingTitle(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("#")) {
            return null;
        }
        return normalizeTitle(trimmed.replaceFirst("^#+\\s*", ""));
    }

    private static String normalizeTitle(String title) {
        if (StrUtil.isBlank(title)) {
            return null;
        }
        return title.trim().replace('\u3000', ' ');
    }

    private static boolean isCover(ArticleState.ImageRequirement requirement) {
        return requirement.getPosition() != null && requirement.getPosition() == 1
                || "cover".equalsIgnoreCase(requirement.getType());
    }

    private static boolean isIcon(ArticleState.ImageRequirement requirement) {
        return "ICONIFY".equalsIgnoreCase(requirement.getImageSource());
    }
}
