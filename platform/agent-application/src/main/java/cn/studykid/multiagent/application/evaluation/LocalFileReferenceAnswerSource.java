package cn.studykid.multiagent.application.evaluation;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.springframework.core.io.Resource;

import cn.studykid.multiagent.ports.metrics.QuestionIdTag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 平台<b>本地题库文件源</b>（§13 第 3 项 · 不依赖外部接口）。
 *
 * <p>按已确认口径用 {@code questionId} <b>精确关联</b>标准解析：
 * <ul>
 *   <li>默认加载 classpath 的 {@code agent-eval/reference-answers.json}（<b>空题库</b>）；
 *       生产可用 {@code file:/...} 外部路径覆盖，无需改代码 / 改表；</li>
 *   <li>JSON 形状固定为
 *       {@code {"version":1,"questions":[{"questionId":"Q-...","standardAnalysis":"..."}]}}；</li>
 *   <li>加载期校验：版本、字段类型、题号白名单、标准解析非空、<b>重复 questionId</b>
 *       一律 fail-closed（启动即报错），不静默取最后一条 —— 题库里两条同号解析不同，
 *       L2 会随加载顺序给出不同结论，而看板毫无异常；</li>
 *   <li>只把 {@code questionId + standardAnalysis} 读进内存，<b>不保留题面原文 / 学生信息</b>；
 *       判分源只回传标准解析，机器结论仍只写 {@code attribution_correct}。</li>
 * </ul>
 *
 * <p>⚠️ 这是<b>可替换端口的一个骨架实现</b>，不是「题库已接入」：默认空题库 +
 * 两个开关默认 false ⇒ L2 不评级。后续换 DB / HTTP 题库只需换一个
 * {@link ReferenceAnswerSource} bean，L2 编排与判定器不用改。
 *
 * <p>⚠️ 文件是<b>启动期一次性加载</b>的：运行期改文件不会热生效（避免同一进程内
 * 同一 questionId 先后拿到两份解析）。需要更新题库就更新文件后重启 / 滚动发布。
 */
public final class LocalFileReferenceAnswerSource implements ReferenceAnswerSource {

    /** 题库文件上限：防止误把全量导出塞进启动期内存。 */
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    /** 只接受这一版题库结构；未知版本拒绝启动而不是「尽力解析」。 */
    private static final int EXPECTED_VERSION = 1;

    private final Map<String, ReferenceAnswer> byQuestionId;

    public LocalFileReferenceAnswerSource(@NonNull Resource resource, @NonNull ObjectMapper mapper) {
        this.byQuestionId = load(resource, mapper);
    }

    @Override
    public @NonNull Optional<ReferenceAnswer> resolve(@NonNull String questionId) {
        String normalized = QuestionIdTag.normalize(questionId);
        if (normalized == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byQuestionId.get(normalized));
    }

    /** 已加载题目数（供装配自检 / 守卫断言；空题库 = 0 是安全默认，不是缺陷）。 */
    public int size() {
        return byQuestionId.size();
    }

    private static Map<String, ReferenceAnswer> load(Resource resource, ObjectMapper mapper) {
        String location = resource.getDescription();
        try {
            long declared = resource.contentLength();
            if (declared > MAX_FILE_BYTES) {
                throw new IllegalArgumentException(
                        "题库文件超过 " + MAX_FILE_BYTES + " 字节上限: " + location);
            }
            byte[] bytes;
            try (InputStream in = resource.getInputStream()) {
                bytes = in.readAllBytes();
            }
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException(
                        "题库文件实际大小超过 " + MAX_FILE_BYTES + " 字节上限: " + location);
            }
            if (bytes.length == 0) {
                throw new IllegalArgumentException("题库文件为空: " + location);
            }
            JsonNode root = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("题库根节点必须是 JSON 对象: " + location);
            }
            JsonNode version = root.get("version");
            if (version == null || !version.isIntegralNumber()
                    || version.asInt() != EXPECTED_VERSION) {
                throw new IllegalArgumentException(
                        "题库 version 必须为 " + EXPECTED_VERSION + "（未知结构拒绝启动）: " + location);
            }
            JsonNode questions = root.get("questions");
            if (questions == null || !questions.isArray()) {
                throw new IllegalArgumentException("题库 questions 必须是数组: " + location);
            }
            Map<String, ReferenceAnswer> out = new LinkedHashMap<>();
            int index = 0;
            for (JsonNode item : questions) {
                index++;
                String at = location + " questions[" + (index - 1) + "]";
                if (item == null || !item.isObject()) {
                    throw new IllegalArgumentException("题库条目必须是对象: " + at);
                }
                JsonNode idNode = item.get("questionId");
                if (idNode == null || !idNode.isTextual()) {
                    throw new IllegalArgumentException("题库条目缺少字符串 questionId: " + at);
                }
                String id = QuestionIdTag.normalize(idNode.asText());
                if (id == null) {
                    throw new IllegalArgumentException("题库 questionId 非法（须为稳定标识，非题面）: " + at);
                }
                JsonNode analysisNode = item.get("standardAnalysis");
                if (analysisNode == null || !analysisNode.isTextual()) {
                    throw new IllegalArgumentException("题库条目缺少字符串 standardAnalysis: " + at);
                }
                String analysis = analysisNode.asText().trim();
                if (analysis.isEmpty()) {
                    throw new IllegalArgumentException("题库 standardAnalysis 不能为空: " + at);
                }
                ReferenceAnswer previous = out.putIfAbsent(id, new ReferenceAnswer(id, analysis));
                if (previous != null) {
                    throw new IllegalArgumentException("题库存在重复 questionId（拒绝启动）: " + id);
                }
            }
            return Map.copyOf(out);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("加载本地题库失败: " + location, e);
        }
    }
}
