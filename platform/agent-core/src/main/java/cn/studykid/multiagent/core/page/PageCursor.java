package cn.studykid.multiagent.core.page;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

import org.jspecify.annotations.NonNull;

/**
 * 游标分页游标（P4-D13 / G7~G10）。
 *
 * <p><b>为什么用 keyset 而非 offset</b>：
 * <ul>
 *   <li><b>性能</b>：{@code OFFSET n} 要求数据库扫描并丢弃前 n 行，
 *       翻到第 50 页就是白扫 50 页的数据；keyset 无论第几页都是索引区间定位。</li>
 *   <li><b>正确性</b>：{@code OFFSET} 在并发插入下会让行<b>重复或漏读</b>——
 *       第 1 页读完后新插入 3 行，第 2 页的前 3 行就是刚看过的。
 *       keyset 以「已读到的最后一行的位置」为界，天然不受后续插入影响。</li>
 * </ul>
 *
 * <p>⚠️ <b>排序键必须是全序</b>：只按 {@code created_at} 排序时，
 * 同一毫秒内的多行顺序<b>不确定</b>，会让同一页出现重复或遗漏。
 * 故游标 = <b>(created_at, 主键)</b> 二元组，比较时两个分量都要参与。
 *
 * <p>⚠️ <b>游标对前端不透明</b>：编码格式是服务端实现细节，
 * 前端只应原样回传，不得解析其内容。本类不承诺编码格式的稳定性。
 */
public record PageCursor(@NonNull Instant createdAt, @NonNull String id) {

    public PageCursor {
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt 不能为空");
        }
        if (id == null || id.isBlank()) {
            // ⚠️ 主键为空会让排序退化成「只按时间排」→ 全序失效 → 重复/漏行
            throw new IllegalArgumentException("id 不能为空（否则排序不再是全序）");
        }
    }

    /** 编码为 URL 安全的 Base64 字符串。 */
    @NonNull
    public String encode() {
        String raw = createdAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码游标。
     *
     * @throws IllegalArgumentException 游标损坏时——调用方须转 <b>400</b>，
     *         <b>不得</b>转 500（是客户端给了坏参数），也不得忽略（会把结果静默变成第一页）
     */
    @NonNull
    public static PageCursor decode(@NonNull String raw) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("游标不是合法 Base64", e);
        }
        int sep = decoded.indexOf('|');
        if (sep <= 0 || sep == decoded.length() - 1) {
            throw new IllegalArgumentException("游标格式非法（缺少分隔符）");
        }
        Instant at;
        try {
            at = Instant.parse(decoded.substring(0, sep));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("游标时间部分非法", e);
        }
        return new PageCursor(at, decoded.substring(sep + 1));
    }
}