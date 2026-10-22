package cn.studykid.multiagent.ports.persistence;

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
}
