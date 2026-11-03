package cn.studykid.rag.core.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 批量导入包的解析规则（V1.0.2 §4 Z10 / S3-5）。
 *
 * <p><b>⚠️ 本类处理的是「一个 ZIP 里装着一份 CSV 清单 + 若干文件」这种形态</b>：
 * 清单的每一行描述一个文件（文件名 + 元数据），ZIP 里放对应的载体。
 * 解析只负责「把包拆成结构」，<b>不做任何入库判断</b>——
 * 去重、格式校验、建 job 都由后续环节按单文件路径复用同一套逻辑。
 *
 * <p><b>⚠️ 为什么默认值是这样定的（T-3，方案建议默认）</b>：
 * <ul>
 *   <li><b>ZIP 上限 2GB</b>：单册教材数十 MB，一批 100 册量级在 1GB 附近；2GB 留出余量又能防误传巨型包。</li>
 *   <li><b>单批 100 个文件</b>：与既有 {@code POST /sources/batch} 的 {@code @Size(max=100)} 对齐，
 *       避免两套批量口径不一致。</li>
 *   <li><b>清单与 ZIP 不对齐时缺文件的行走失败明细</b>：与「逐条独立成败」的既有语义一致，
 *       整批拒收会让一个笔误毁掉整批。</li>
 *   <li><b>ZIP 内多出的文件（清单未列）忽略并计入告警</b>：不静默入库——
 *       清单是「要什么」的声明，没声明的文件入库等于绕过元数据校验。</li>
 * </ul>
 */
public final class BulkImportRules {

    private BulkImportRules() {
    }

    /** ZIP 字节上限（2GB，T-3 默认值）。 */
    public static final long MAX_ZIP_BYTES = 2L * 1024 * 1024 * 1024;

    /** 单批文件数上限（100，与既有 {sources/batch} 的 max 对齐）。 */
    public static final int MAX_BATCH_FILES = 100;

    /** 单文件字节上限（200MB）。 */
    public static final long MAX_SINGLE_FILE_BYTES = 200L * 1024 * 1024;

    /** 清单文件名（ZIP 内的固定名，大小写不敏感）。 */
    public static final String MANIFEST_NAME = "manifest.csv";

    /** 清单必填列。 */
    public static final List<String> REQUIRED_COLUMNS = List.of("filename", "title");

    /**
     * 清单的一行。
     *
     * @param lineNo   行号（从 1 起，便于回指 CSV 原文）
     * @param filename ZIP 内的文件名
     * @param title    载体标题
     * @param fields   其余元数据列（原样保留，供上层决定是否使用）
     */
    public record ManifestRow(int lineNo, String filename, String title,
                              Map<String, String> fields) {
    }

    /**
     * 解析结果。
     *
     * @param rows       成功的清单行
     * @param errors     逐行错误（行号 + 原因），不阻断其余行
     * @param extras     ZIP 内存在但清单未列的文件名（忽略并告警）
     * @param files      ZIP 内文件内容（文件名 → 字节）
     */
    public record ParsedBatch(List<ManifestRow> rows, List<RowError> errors,
                              List<String> extras, Map<String, byte[]> files) {
    }

    /**
     * 单行错误。
     *
     * @param lineNo 行号
     * @param reason 原因（可直接展示给采集员）
     */
    public record RowError(int lineNo, String reason) {
    }

    /**
     * 解析「ZIP + CSV 清单」。
     *
     * @param zipBytes ZIP 内容
     * @return 解析结果
     * @throws IllegalArgumentException 结构性错误（超限/缺清单），这类错误整批拒绝
     */
    public static ParsedBatch parse(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IllegalArgumentException("批量包为空");
        }
        if (zipBytes.length > MAX_ZIP_BYTES) {
            throw new IllegalArgumentException(
                    "批量包超过上限 " + (MAX_ZIP_BYTES / 1024 / 1024) + "MB");
        }

        Map<String, byte[]> files = new LinkedHashMap<>();
        String manifest = null;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                // ⚠️ 路径穿越防护：ZIP 里的条目名可能含 ../，必须拒绝。
                String name = normalizeEntryName(entry.getName());
                if (name == null) {
                    throw new IllegalArgumentException(
                            "批量包含非法条目（路径穿越）：" + entry.getName());
                }
                byte[] content = zis.readAllBytes();
                if (content.length > MAX_SINGLE_FILE_BYTES) {
                    throw new IllegalArgumentException(
                            "文件 " + name + " 超过单文件上限 "
                                    + (MAX_SINGLE_FILE_BYTES / 1024 / 1024) + "MB");
                }
                if (MANIFEST_NAME.equalsIgnoreCase(name)) {
                    manifest = new String(content, StandardCharsets.UTF_8);
                } else {
                    files.put(name, content);
                }
                if (files.size() > MAX_BATCH_FILES) {
                    throw new IllegalArgumentException(
                            "批量包内文件数超过上限 " + MAX_BATCH_FILES);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("读取批量包失败：" + e.getMessage(), e);
        }

        if (manifest == null) {
            throw new IllegalArgumentException(
                    "批量包内缺少 " + MANIFEST_NAME + "（清单文件）");
        }

        List<ManifestRow> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        parseManifest(manifest, rows, errors);

        // 清单未列的文件：忽略并计入 extras（不静默入库）
        List<String> extras = new ArrayList<>();
        for (String name : files.keySet()) {
            boolean listed = rows.stream().anyMatch(r -> r.filename().equals(name));
            boolean errored = errors.stream().anyMatch(e -> e.reason().contains(name));
            if (!listed && !errored) {
                extras.add(name);
            }
        }
        return new ParsedBatch(rows, errors, extras, files);
    }

    /**
     * 解析 CSV 清单（支持带引号字段与转义双引号）。
     *
     * @param csv    清单文本
     * @param rows   成功行收集
     * @param errors 错误收集
     */
    private static void parseManifest(String csv, List<ManifestRow> rows, List<RowError> errors) {
        List<List<String>> table = parseCsv(csv);
        if (table.isEmpty()) {
            errors.add(new RowError(0, "清单为空"));
            return;
        }
        // 表头
        List<String> header = table.get(0).stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .toList();
        for (String required : REQUIRED_COLUMNS) {
            if (!header.contains(required)) {
                errors.add(new RowError(1, "清单缺少必填列：" + required));
                return;
            }
        }
        int fnIdx = header.indexOf("filename");
        int titleIdx = header.indexOf("title");

        for (int i = 1; i < table.size(); i++) {
            List<String> line = table.get(i);
            int lineNo = i + 1;
            // ⚠️ 只跳过「真正没有任何字段」的空行（如表格末尾的空行）。
            //    像 `,` 这种「有分隔符但字段皆空」的行**不能跳过**：
            //    用户写了这一行却没给文件名，应当明确报错，
            //    否则他会以为这行被导入成功了（静默忽略是最坏的失败形态）。
            if (line.isEmpty() || line.stream().allMatch(c -> c == null)) {
                continue;
            }
            String filename = cell(line, fnIdx);
            String title = cell(line, titleIdx);
            if (filename == null || filename.isBlank()) {
                errors.add(new RowError(lineNo, "filename 为空"));
                continue;
            }
            if (title == null || title.isBlank()) {
                errors.add(new RowError(lineNo, "title 为空（filename=" + filename + "）"));
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                if (c == fnIdx || c == titleIdx) {
                    continue;
                }
                fields.put(header.get(c), cell(line, c));
            }
            rows.add(new ManifestRow(lineNo, filename.trim(), title.trim(), fields));
        }
    }

    private static String cell(List<String> line, int idx) {
        if (idx < 0 || idx >= line.size()) {
            return null;
        }
        String v = line.get(idx);
        return v == null ? null : v.trim();
    }

    /**
     * 极简 CSV 解析（RFC 4180 兼容：双引号包裹、引号内逗号、双引号转义）。
     *
     * <p>⚠️ 不引入第三方 CSV 库：清单只有几列，且要避免为一件小事
     * 增加依赖面（部署/安全审计的负担）。
     *
     * @param csv CSV 文本
     * @return 行 × 列
     */
    static List<List<String>> parseCsv(String csv) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        String text = csv.replace("\r\n", "\n").replace('\r', '\n');

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(ch);
                }
                continue;
            }
            switch (ch) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                }
                case '\n' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                    out.add(row);
                    row = new ArrayList<>();
                }
                default -> cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            out.add(row);
        }
        return out;
    }

    /**
     * 规范化 ZIP 条目名并做路径穿越检查。
     *
     * @param raw 原始条目名
     * @return 规范化后的文件名；非法返回 null
     */
    private static String normalizeEntryName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.replace('\\', '/');
        if (name.contains("..") || name.startsWith("/")) {
            return null;
        }
        // 只取文件名部分（允许 ZIP 内有子目录，但入库时按文件名匹配清单）
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
}
