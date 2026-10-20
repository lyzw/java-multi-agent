package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 学习画像查询端口（P4-D09 / G6）。
 *
 * <p><b>用途</b>：为「学习画像查询端点」（{@code GET /api/profiles/{studentId}}）提供数据源。
 *
 * <p>⚠️ <b>与 {@code get_student_profile} 工具的分工</b>：
 * 工具是 <b>Worker 侧</b>的分析流程输入（给模型看），
 * 本端口是 <b>API 侧</b>的页面数据源（给家长/教师看）。
 * 两者读同一批表，但**授权路径不同**（工具走 {@code tool:invoke}，本端点走 {@code profile:read}）。
 *
 * <p>⚠️ <b>白名单纪律（DEC-6 / PIPL 未成年人）</b>：
 * 只允许返回四类信息——随机 {@code studentId}、年级、学科、薄弱点。
 * <b>不得</b>返回姓名、学校、班级、身份证、手机、定位、生物识别。
 * DB 层亦<b>结构上无法采集</b>（V2 迁移刻意不建此类列）。
 *
 * <p>⚠️ <b>「不存在」不返回 null</b>：
 * 若不存在报错、存在返回数据，攻击者可据此<b>枚举有效 studentId</b>。
 * 故不存在时返回<b>空画像</b>（{@link ProfileView#empty}），由上层转成 {@code notFound:true}。
 */
public interface ProfileQueryPort {

    /**
     * 读取学习画像。
     *
     * @param tenantId 租户标识；<b>必须来自认证上下文</b>
     * @param studentId 随机学生标识（{@code ^stu_[a-z0-9]{16}$}）
     * @return 画像快照；<b>学生不存在时返回空画像而非 null</b>（防枚举）
     */
    @NonNull ProfileView loadProfile(@NonNull String tenantId, @NonNull String studentId);

    /**
     * 画像快照。
     *
     * @param studentId  随机学生标识
     * @param grade      年级；学生不存在时为 {@code null}
     * @param subject    学科；学生不存在时为 {@code null}
     * @param weaknesses 薄弱点列表（可能为空）
     * @param updatedAt 画像更新时间；学生不存在时为 {@code null}
     */
    record ProfileView(
            @NonNull String studentId,
            @Nullable String grade,
            @Nullable String subject,
            @NonNull List<WeaknessView> weaknesses,
            @Nullable Instant updatedAt
    ) {
        public ProfileView {
            weaknesses = weaknesses == null ? List.of() : List.copyOf(weaknesses);
        }

        /**
         * 空画像：学生不存在时返回（<b>不抛异常</b>，避免枚举）。
         *
         * <p>⚠️ 与「存在但没有薄弱点」的区别：存在的学生 {@code grade} 非空，
         * 不存在的学生 {@code grade} 为 {@code null}——上层据此判定 {@code notFound}。
         */
        public static @NonNull ProfileView empty(@NonNull String studentId) {
            return new ProfileView(studentId, null, null, List.of(), null);
        }

        /** @return 该学生是否存在（{@code grade} 非空即存在）。 */
        public boolean exists() {
            return grade != null;
        }
    }

    /**
     * 单条薄弱点。
     *
     * @param knowledgePoint 知识点
     * @param errorType      错因分类（6 值枚举：概念不清/公式记错/符号错误/计算失误/审题偏差/步骤缺失）
     * @param confidence     置信度 {@code [0,1]}；可为空
     * @param createdAt      记录时刻
     */
    record WeaknessView(
            @NonNull String knowledgePoint,
            @NonNull String errorType,
            @Nullable Double confidence,
            @NonNull Instant createdAt
    ) {
    }
}
