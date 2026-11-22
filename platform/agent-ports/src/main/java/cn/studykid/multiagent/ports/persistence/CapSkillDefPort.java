package cn.studykid.multiagent.ports.persistence;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.CapSkillDef;

/**
 * Skill 定义版本行读取端口（§6.1 {@code cap_skill_def}）。
 *
 * <p>platform 级对象（无 RLS）。
 */
public interface CapSkillDefPort {

    /**
     * 插入一条版本行（§6.3 第 1 条 · W-PERM-P5）。
     *
     * <p>⚠️ <b>本方法在发布事务内被调用</b>：版本行插入、release 记录、
     * 指针 CAS 必须同事务提交（§6.3 第 1 条）。
     * 实现<b>不得</b>自行提交，否则外层事务失败时无法回滚本次插入，
     * 会留下「版本行已存在、指针未移动」的半成品当前发布（PUB-1）。
     *
     * <p>幂等：同一 {@code (id, version)} 已存在时返回 {@code false}，
     * <b>不覆盖</b>既有行（不可变版本行，06 §1.1）。
     *
     * @param def 版本行
     * @return {@code true} 表示本次插入；{@code false} 表示已存在
     */
    boolean insert(@NonNull CapSkillDef def);

    /**
     * 按固定版本读取。
     *
     * @param skillId Skill 逻辑 id
     * @param version 版本号
     * @return 版本行；不存在时为 {@link Optional#empty()}
     */
    @NonNull Optional<CapSkillDef> find(@NonNull String skillId, int version);

    /**
     * 读取该 Skill 的<b>最新已发布</b>版本行。
     *
     * <p>⚠️ 同 {@link CapToolDefPort#findLatestPublished}：该口径不等价于
     * 「当前发布所指版本」，仅用于诊断与迁移，不得用于授权判定。
     *
     * @param skillId Skill 逻辑 id
     * @return 最新已发布版本行；无已发布版本时为 {@link Optional#empty()}
     */
    @NonNull Optional<CapSkillDef> findLatestPublished(@NonNull String skillId);

    /**
     * 读取该 Skill 的<b>最高版本行</b>（<b>不区分发布状态</b> · 版本管理守卫）。
     *
     * <p>⚠️ 与 {@link #findLatestPublished} 的「已发布口径」不同：本方法取该逻辑 id 的
     * <b>绝对最高版本</b>—— 可能是已发布版本、也可能是尚未发布的草稿版本。
     * 服务端据此算「下一个版本号 = 最高版本 + 1」，避免「已有未发布草稿却撞号」的假成功。
     *
     * <p>⚠️ 管理台的「编辑」入口<b>以本方法为准</b>：拿最高版本作为编辑基线 ——
     * 已发布版本不可覆盖（DB 触发器封禁 UPDATE），编辑在最高版本之上自动产出新版本行。
     *
     * @param skillId Skill 逻辑 id
     * @return 最高版本行；无任何版本时为 {@link Optional#empty()}
     */
    @NonNull Optional<CapSkillDef> findLatest(@NonNull String skillId);

    /**
     * 列出全部 Skill 定义（各逻辑 id 的<b>最新</b>版本行）。
     *
     * <p>⚠️ <b>只读用途</b>：供配置台的 Skill 目录页展示「平台上有哪些技能、
     * 各自声明的工具 / MCP / 风险上限 / 必需能力」。
     * <b>不</b>用于授权判定中的版本解析（那必须走 {@link PublishedVersionPort}）。
     *
     * <p>⚠️ <b>不按租户过滤</b>：Skill 定义是 platform 级对象
     * （{@code cap_skill_def} 不启用 RLS），全平台唯一。
     *
     * <p>⚠️ <b>本端口只读，不提供写入入口</b>：平台级对象的变更走带外运维通道，
     * 不挂在租户身份上（理由见 {@code CapConfigPublishController} 的 403 作用域闸门）。
     *
     * @return 全部 Skill 定义（每个逻辑 id 取其最高版本行），按 id 升序
     */
    @NonNull List<CapSkillDef> listAll();

    /**
     * 列出该 Skill 的<b>全部版本行</b>（不可变历史，按版本倒序）。
     *
     * <p>⚠️ 与 {@link #listAll()} 的「每 id 最高版本」口径不同：本方法是<b>版本维度</b>
     * 的全量回溯（能力目录版本管理 · 历史版本留存），供管理台展示版本历史与变更轨迹。
     * <b>不</b>用于授权判定中的版本解析（那必须走 {@link PublishedVersionPort}）。
     *
     * @param skillId Skill 逻辑 id
     * @return 全部版本行，按 version 倒序；无任何版本时为空列表
     */
    @NonNull List<CapSkillDef> listVersions(@NonNull String skillId);
}
