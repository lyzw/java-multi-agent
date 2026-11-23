package cn.studykid.rag.eval.cases;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 可执行 case 加载器（RAG-D08 FR-RAG-002 / §3.1「由测试代码加载并断言」）。
 *
 * <p>⚠️ 设计要点：
 * <ol>
 *   <li><b>fail-closed</b>：开启 {@code FAIL_ON_UNKNOWN_PROPERTIES}——case 里出现拼错字段
 *       （如 {@code ratoinale}）立即报错，避免「rationale 静默丢失、纪律断言假绿」；</li>
 *   <li><b>确定性顺序</b>：文件名排序后加载，孤儿检测的错误信息可复现；</li>
 *   <li><b>零 Spring 依赖</b>：纯 Jackson + NIO，可在任何测试上下文调用，
 *       不引入应用上下文（评测基础设施不是生产 bean）。</li>
 * </ol>
 *
 * <p>⚠️ 诚实边界：加载器只保证「case 可加载且结构合法」；
 * case 的<b>期望值是否正确</b>由人工标注与非实现方评审保证（D-A/D-B），加载器不判定。
 */
public final class CaseLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    private CaseLoader() {
    }

    /** 加载目录下所有 {@code *.json}（不递归），按文件名排序。 */
    public static List<CaseSet> loadAll(Path casesDirectory) {
        if (casesDirectory == null || !Files.isDirectory(casesDirectory)) {
            throw new IllegalArgumentException(
                    "case 目录不存在或不是目录: " + casesDirectory);
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(casesDirectory)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 case 目录失败: " + casesDirectory, e);
        }
        List<CaseSet> result = new ArrayList<>();
        for (Path file : files) {
            result.add(loadOne(file));
        }
        return result;
    }

    /** 加载单个 case 文件；JSON 非法或字段未知即抛异常。 */
    public static CaseSet loadOne(Path file) {
        try {
            return MAPPER.readValue(Files.readAllBytes(file), CaseSet.class);
        } catch (IOException e) {
            throw new UncheckedIOException("加载 case 文件失败（JSON 非法或含未知字段）: " + file, e);
        }
    }
}
