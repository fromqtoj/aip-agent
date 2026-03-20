package com.aip.skill;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.argument.StructuredArguments;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.Set;
import java.util.HashMap;
import java.util.stream.Stream;

@Service
public class AgentSkillService {

    private static final Logger log = LoggerFactory.getLogger(AgentSkillService.class);

    private static final String FRONT_MATTER_DELIMITER = "---";
    private static final String SKILL_SELECTOR_SYSTEM_PROMPT = """
            你是一个 Skill 路由器。你的职责是：根据用户问题，从给定 skill 列表中选出最相关的 skill。
            你必须只输出 JSON，不要输出解释文字，不要输出 markdown。
            JSON 格式固定为：
            {"selectedSkills":[{"fileName":"...","reason":"..."}]}
            要求：
            1) 最多选择 maxMatched 个。
            2) fileName 必须来自候选 skill 列表，不允许编造。
            3) reason 必须简短说明命中原因。
            4) 如果确实都不相关，可以返回空数组。
            """;

    private final ChatClient skillSelectorClient;

    private final ObjectMapper objectMapper;

    @Value("${aip.agent.skill.enabled:true}")
    private boolean skillEnabled;

    @Value("${aip.agent.skill.path:./skills}")
    private String skillPath;

    @Value("${aip.agent.skill.max-matched:3}")
    private int maxMatchedSkills;

    private static final long CACHE_TTL_MS = 30_000;

    private volatile List<SkillDefinition> cachedSkills;

    private volatile long cacheTimestamp;

    public AgentSkillService(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.skillSelectorClient = chatClientBuilder
                .defaultSystem(SKILL_SELECTOR_SYSTEM_PROMPT)
                .defaultOptions(DashScopeChatOptions.builder().withTopP(0.1).build())
                .build();
        this.objectMapper = objectMapper;
    }

    public SkillMatchResult resolveSkillContext(String question) {
        if (!skillEnabled) {
            return SkillMatchResult.empty();
        }

        List<SkillDefinition> skillDefinitions = loadSkillDefinitions();
        if (skillDefinitions.isEmpty()) {
            return SkillMatchResult.empty();
        }

        List<MatchedSkill> matchedSkillViews = matchByAgent(question, skillDefinitions);
        String matchSource = "agent";
        if (matchedSkillViews.isEmpty()) {
            matchedSkillViews = matchByRules(question, skillDefinitions);
            matchSource = "rule-fallback";
        }

        if (matchedSkillViews.isEmpty()) {
            return SkillMatchResult.empty();
        }

        StringBuilder skillContext = new StringBuilder();
        for (MatchedSkill matchedSkillView : matchedSkillViews) {
            SkillDefinition skill = skillDefinitions.stream()
                    .filter(definition -> definition.file().getFileName().toString().equals(matchedSkillView.fileName()))
                    .findFirst()
                    .orElse(null);
            if (skill == null) {
                continue;
            }
            skillContext.append("技能文件：")
                    .append(skill.file().getFileName())
                    .append("\n")
                    .append("技能名称：")
                    .append(skill.name())
                    .append("\n")
                    .append("技能说明：\n")
                    .append(skill.content().strip())
                    .append("\n\n");
        }

        List<String> plannedMcpTools = matchedSkillViews.stream()
                .flatMap(skill -> skill.plannedTools().stream())
                .distinct()
                .toList();

        SkillMatchResult result = new SkillMatchResult(skillContext.toString().strip(), matchedSkillViews, plannedMcpTools);
        log.info("SKILL_TRACE {} {} {} {}",
                StructuredArguments.kv("question", abbreviate(question)),
                StructuredArguments.kv("matchSource", matchSource),
                StructuredArguments.kv("matchedSkills", matchedSkillViews),
                StructuredArguments.kv("plannedMcpTools", plannedMcpTools));
        return result;
    }

    public List<SkillCatalogEntry> listSkillCatalog() {
        if (!skillEnabled) {
            return List.of();
        }

        return loadSkillDefinitions().stream()
                .map(skill -> new SkillCatalogEntry(
                        skill.file().getFileName().toString(),
                        skill.name(),
                        skill.plannedTools()
                ))
                .toList();
    }

    private List<MatchedSkill> matchByAgent(String question, List<SkillDefinition> skillDefinitions) {
        if (!StringUtils.hasText(question)) {
            return List.of();
        }
        try {
            String response = skillSelectorClient.prompt(buildSkillSelectionPrompt(question, skillDefinitions)).call().content();
            List<AgentSelectedSkill> selectedSkills = parseAgentSelectedSkills(response);
            if (selectedSkills.isEmpty()) {
                return List.of();
            }

            Map<String, SkillDefinition> byFileName = new HashMap<>();
            Map<String, SkillDefinition> byName = new HashMap<>();
            for (SkillDefinition skillDefinition : skillDefinitions) {
                byFileName.put(skillDefinition.file().getFileName().toString().toLowerCase(Locale.ROOT), skillDefinition);
                byName.put(skillDefinition.name().toLowerCase(Locale.ROOT), skillDefinition);
            }

            List<MatchedSkill> matchedSkills = new ArrayList<>();
            Set<String> addedFiles = new LinkedHashSet<>();
            String normalizedQuestion = question.toLowerCase(Locale.ROOT);

            for (AgentSelectedSkill selectedSkill : selectedSkills) {
                SkillDefinition skillDefinition = null;
                if (StringUtils.hasText(selectedSkill.fileName())) {
                    skillDefinition = byFileName.get(selectedSkill.fileName().toLowerCase(Locale.ROOT));
                }
                if (skillDefinition == null && StringUtils.hasText(selectedSkill.name())) {
                    skillDefinition = byName.get(selectedSkill.name().toLowerCase(Locale.ROOT));
                }
                if (skillDefinition == null) {
                    continue;
                }

                String fileName = skillDefinition.file().getFileName().toString();
                if (addedFiles.contains(fileName)) {
                    continue;
                }

                MatchDetail matchDetail = calculateMatchDetail(skillDefinition, normalizedQuestion);
                String reason = StringUtils.hasText(selectedSkill.reason())
                        ? "agent: " + selectedSkill.reason().strip()
                        : buildMatchReason(skillDefinition.withMatchDetail(matchDetail));

                matchedSkills.add(new MatchedSkill(
                        fileName,
                        skillDefinition.name(),
                        skillDefinition.priority(),
                        Math.max(1, matchDetail.score()),
                        skillDefinition.always(),
                        matchDetail.matchedKeywords(),
                        reason,
                        skillDefinition.plannedTools()
                ));
                addedFiles.add(fileName);
                if (matchedSkills.size() >= Math.max(1, maxMatchedSkills)) {
                    break;
                }
            }
            return matchedSkills;
        }
        catch (Exception e) {
            log.warn("SKILL_AGENT_MATCH_FAILED {} {}",
                    StructuredArguments.kv("question", abbreviate(question)),
                    StructuredArguments.kv("error", abbreviate(e.getMessage())));
            return List.of();
        }
    }

    private List<MatchedSkill> matchByRules(String question, List<SkillDefinition> skillDefinitions) {
        String normalizedQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return skillDefinitions.stream()
                .map(skill -> skill.withMatchDetail(calculateMatchDetail(skill, normalizedQuestion)))
                .filter(skill -> skill.always() || skill.matchScore() > 0)
                .sorted(Comparator
                        .comparingInt(SkillDefinition::matchScore).reversed()
                        .thenComparing(Comparator.comparingInt(SkillDefinition::priority).reversed())
                        .thenComparing(skill -> skill.file().getFileName().toString()))
                .limit(Math.max(1, maxMatchedSkills))
                .map(skill -> new MatchedSkill(
                        skill.file().getFileName().toString(),
                        skill.name(),
                        skill.priority(),
                        skill.matchScore(),
                        skill.always(),
                        skill.matchedKeywords(),
                        buildMatchReason(skill),
                        skill.plannedTools()
                ))
                .toList();
    }

    private String buildSkillSelectionPrompt(String question, List<SkillDefinition> skillDefinitions) {
        int maxMatched = Math.max(1, maxMatchedSkills);
        StringBuilder skillCatalog = new StringBuilder();
        for (SkillDefinition skill : skillDefinitions) {
            skillCatalog.append("- fileName: ").append(skill.file().getFileName()).append("\n")
                    .append("  name: ").append(skill.name()).append("\n")
                    .append("  keywords: ").append(String.join(", ", skill.keywords())).append("\n")
                    .append("  plannedTools: ").append(String.join(", ", skill.plannedTools())).append("\n")
                    .append("  always: ").append(skill.always()).append("\n\n");
        }

        return """
                用户问题：
                %s

                maxMatched：
                %d

                候选 skills：
                %s

                现在请输出 JSON。
                """.formatted(question == null ? "" : question.strip(), maxMatched, skillCatalog.toString().strip());
    }

    private List<AgentSelectedSkill> parseAgentSelectedSkills(String response) {
        if (!StringUtils.hasText(response)) {
            return List.of();
        }
        try {
            JsonNode root = readJsonNode(response);
            if (root == null) {
                return List.of();
            }
            JsonNode selectedSkillsNode = root.path("selectedSkills");
            if (!selectedSkillsNode.isArray()) {
                return List.of();
            }
            List<AgentSelectedSkill> selectedSkills = new ArrayList<>();
            for (JsonNode node : selectedSkillsNode) {
                String fileName = node.path("fileName").asText("");
                String name = node.path("name").asText("");
                String reason = node.path("reason").asText("");
                if (!StringUtils.hasText(fileName) && !StringUtils.hasText(name)) {
                    continue;
                }
                selectedSkills.add(new AgentSelectedSkill(fileName.strip(), name.strip(), reason.strip()));
            }
            return selectedSkills;
        }
        catch (Exception e) {
            log.warn("SKILL_AGENT_PARSE_FAILED {}",
                    StructuredArguments.kv("error", abbreviate(e.getMessage())));
            return List.of();
        }
    }

    private JsonNode readJsonNode(String response) throws IOException {
        try {
            return objectMapper.readTree(response);
        }
        catch (IOException firstException) {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String maybeJson = response.substring(start, end + 1);
                return objectMapper.readTree(maybeJson);
            }
            throw firstException;
        }
    }

    private List<SkillDefinition> loadSkillDefinitions() {
        List<SkillDefinition> cached = cachedSkills;
        if (cached != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS) {
            return cached;
        }

        Path root = resolveSkillRoot();
        if (root == null) {
            log.warn("SKILL_PATH_NOT_FOUND {}",
                    StructuredArguments.kv("configuredSkillPath", skillPath));
            return List.of();
        }

        try (Stream<Path> stream = Files.walk(root, 2)) {
            List<SkillDefinition> loaded = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isSkillFile)
                    .map(this::parseSkillDefinition)
                    .filter(Objects::nonNull)
                    .filter(skill -> StringUtils.hasText(skill.content()))
                    .toList();
            cachedSkills = loaded;
            cacheTimestamp = System.currentTimeMillis();
            return loaded;
        }
        catch (IOException e) {
            throw new IllegalStateException("读取 skill 目录失败: " + root, e);
        }
    }

    private Path resolveSkillRoot() {
        List<Path> candidates = new ArrayList<>();
        addPathCandidate(candidates, skillPath);

        Path userDir = Paths.get(System.getProperty("user.dir", "."));
        if (StringUtils.hasText(skillPath)) {
            addPathCandidate(candidates, userDir.resolve(skillPath).toString());
        }

        // 本地开发时的常见目录结构兜底：仓库根目录启动 aip-core。
        addPathCandidate(candidates, userDir.resolve("aip-core/skills").toString());
        addPathCandidate(candidates, userDir.resolve("skills").toString());

        Set<Path> uniqueCandidates = new LinkedHashSet<>();
        for (Path candidate : candidates) {
            uniqueCandidates.add(candidate.toAbsolutePath().normalize());
        }

        for (Path candidate : uniqueCandidates) {
            if (Files.exists(candidate) && Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void addPathCandidate(List<Path> candidates, String pathValue) {
        if (StringUtils.hasText(pathValue)) {
            candidates.add(Paths.get(pathValue.trim()));
        }
    }

    private boolean isSkillFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".skill.md") || fileName.endsWith(".skill.txt") || fileName.endsWith(".md");
    }

    private SkillDefinition parseSkillDefinition(Path skillFile) {
        String rawContent = readSkillContent(skillFile);
        if (!StringUtils.hasText(rawContent)) {
            return null;
        }

        String trimmed = rawContent.strip();
        if (!trimmed.startsWith(FRONT_MATTER_DELIMITER)) {
            return SkillDefinition.defaultSkill(skillFile, rawContent);
        }

        int secondDelimiterIndex = trimmed.indexOf("\n" + FRONT_MATTER_DELIMITER);
        if (secondDelimiterIndex < 0) {
            return SkillDefinition.defaultSkill(skillFile, rawContent);
        }

        String metadataBlock = trimmed.substring(FRONT_MATTER_DELIMITER.length(), secondDelimiterIndex).strip();
        String content = trimmed.substring(secondDelimiterIndex + ("\n" + FRONT_MATTER_DELIMITER).length()).strip();
        Map<String, String> metadata = parseMetadata(metadataBlock);

        String name = metadata.getOrDefault("name", skillFile.getFileName().toString());
        int priority = parseInteger(metadata.get("priority"), 0);
        boolean always = Boolean.parseBoolean(metadata.getOrDefault("always", "false"));
        Set<String> keywords = parseKeywords(metadata.get("keywords"));
        List<String> plannedTools = parsePlannedTools(metadata.get("planned-tools"));

        return new SkillDefinition(skillFile, name, content, keywords, plannedTools, priority, always, 0, List.of());
    }

    private Map<String, String> parseMetadata(String metadataBlock) {
        return metadataBlock.lines()
                .map(String::strip)
                .filter(StringUtils::hasText)
                .filter(line -> line.contains(":"))
                .map(line -> line.split(":", 2))
                .collect(java.util.stream.Collectors.toMap(
                        parts -> parts[0].trim().toLowerCase(Locale.ROOT),
                        parts -> parts[1].trim(),
                        (left, right) -> right
                ));
    }

    private MatchDetail calculateMatchDetail(SkillDefinition skill, String normalizedQuestion) {
        if (skill.always()) {
            // always skill 只作为兜底，不应压过真实关键词命中的 skill。
            return new MatchDetail(0, List.of("always"));
        }

        List<String> matchedKeywords = new ArrayList<>();
        for (String keyword : skill.keywords()) {
            if (normalizedQuestion.contains(keyword)) {
                matchedKeywords.add(keyword);
            }
        }

        if (matchedKeywords.isEmpty() && skill.file().getFileName().toString().toLowerCase(Locale.ROOT).contains("word")) {
            List<String> fallbackKeywords = List.of("word", "doc", "docx", "文档", "导出", "保存到", "指定路径");
            for (String keyword : fallbackKeywords) {
                if (normalizedQuestion.contains(keyword) && !matchedKeywords.contains(keyword)) {
                    matchedKeywords.add(keyword);
                }
            }
        }

        return new MatchDetail(matchedKeywords.size(), matchedKeywords);
    }

    private Set<String> parseKeywords(String rawKeywords) {
        if (!StringUtils.hasText(rawKeywords)) {
            return Set.of();
        }

        String[] parts = rawKeywords.split(",");
        Set<String> keywords = new LinkedHashSet<>();
        for (String part : parts) {
            String keyword = part.trim().toLowerCase(Locale.ROOT);
            if (StringUtils.hasText(keyword)) {
                keywords.add(keyword);
            }
        }
        return keywords;
    }

    private List<String> parsePlannedTools(String rawPlannedTools) {
        if (!StringUtils.hasText(rawPlannedTools)) {
            return List.of();
        }

        String[] parts = rawPlannedTools.split(",");
        List<String> tools = new ArrayList<>();
        for (String part : parts) {
            String tool = part.trim();
            if (StringUtils.hasText(tool) && !tools.contains(tool)) {
                tools.add(tool);
            }
        }
        return Collections.unmodifiableList(tools);
    }

    private String buildMatchReason(SkillDefinition skill) {
        if (skill.always()) {
            return "always=true";
        }
        if (!skill.matchedKeywords().isEmpty()) {
            return "matched keywords: " + String.join(", ", skill.matchedKeywords());
        }
        return "matchScore=" + skill.matchScore();
    }

    private int parseInteger(String value, int defaultValue) {
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String readSkillContent(Path skillFile) {
        try {
            return Files.readString(skillFile, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new IllegalStateException("读取 skill 文件失败: " + skillFile, e);
        }
    }

    private record SkillDefinition(Path file,
                                   String name,
                                   String content,
                                   Set<String> keywords,
                                   List<String> plannedTools,
                                   int priority,
                                   boolean always,
                                   int matchScore,
                                   List<String> matchedKeywords) {

        private SkillDefinition withMatchDetail(MatchDetail matchDetail) {
            return new SkillDefinition(file, name, content, keywords, plannedTools, priority, always,
                    matchDetail.score(), matchDetail.matchedKeywords());
        }

        private static SkillDefinition defaultSkill(Path file, String content) {
            List<String> keywordCandidates = new ArrayList<>();
            String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (fileName.contains("word")) {
                keywordCandidates = List.of("word", "doc", "docx", "文档", "导出", "保存到", "指定路径");
            }

            return new SkillDefinition(
                    file,
                    file.getFileName().toString(),
                    content,
                    new LinkedHashSet<>(keywordCandidates),
                    List.of(),
                    0,
                    false,
                    0,
                    List.of()
            );
        }
    }

    public record MatchedSkill(String fileName,
                               String name,
                               int priority,
                               int matchScore,
                               boolean always,
                               List<String> matchedKeywords,
                               String matchReason,
                               List<String> plannedTools) {
    }

    public record SkillMatchResult(String skillContext,
                                   List<MatchedSkill> matchedSkills,
                                   List<String> plannedMcpTools) {

        public static SkillMatchResult empty() {
            return new SkillMatchResult("", List.of(), List.of());
        }
    }

    public record SkillCatalogEntry(String fileName,
                                    String name,
                                    List<String> plannedTools) {
    }

    private record MatchDetail(int score, List<String> matchedKeywords) {
    }

    private record AgentSelectedSkill(String fileName, String name, String reason) {
    }

    private String abbreviate(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 160) + "...";
    }
}
