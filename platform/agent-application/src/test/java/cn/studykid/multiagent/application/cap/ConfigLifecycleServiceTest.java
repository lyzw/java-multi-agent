package cn.studykid.multiagent.application.cap;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.ports.persistence.ConfigLifecyclePort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生命周期迁移服务单测（P5-T4 · §6.6 / 06 §3）。
 *
 * <p>⚠️ <b>锁住的是「两类失败的区分」</b>：
 * 非法迁移（操作错误）必须<b>抛异常</b>，CAS 前置条件不符（并发竞争）必须<b>返回 false</b>。
 * 两者混淆的后果是相反的：把并发当错误会让正常的并发重试变成 500；
 * 把非法迁移当竞争会让「停用对象复活」被当作「重试一下说不定就成了」。
 */
class ConfigLifecycleServiceTest {

    private final InMemoryPort port = new InMemoryPort();
    private final ConfigLifecycleService service =
            new ConfigLifecycleService(port, new ConfigLifecycleStateMachine());

    @Test
    @DisplayName("✅ 合法迁移生效，且状态被更新")
    void legalTransitionApplies() {
        assertThat(service.transition("tool", null, "tool-x", 1,
                ConfigLifecycleState.DRAFT, ConfigLifecycleState.VALIDATED)).isTrue();
        assertThat(service.stateOf("tool", null, "tool-x", 1))
                .contains(ConfigLifecycleState.VALIDATED);
    }

    @Test
    @DisplayName("✅ CAS 前置条件不符 → 返回 false（并发竞争，可重试）")
    void casMismatchReturnsFalse() {
        // 行当前是 DRAFT，却声称期望 VALIDATED
        assertThat(service.transition("tool", null, "tool-x", 1,
                ConfigLifecycleState.VALIDATED, ConfigLifecycleState.DRAFT))
                .as("⛔ CAS 前置条件失效：并发迁移被静默覆盖")
                .isFalse();
    }

    @Test
    @DisplayName("⛔ 非法迁移（DEPRECATED 是终态）→ 抛可读拒绝，不落到 DB")
    void illegalTransitionThrows() {
        assertThatThrownBy(() -> service.transition("tool", null, "tool-x", 1,
                ConfigLifecycleState.DEPRECATED, ConfigLifecycleState.PUBLISHED))
                .as("⛔ 非法迁移未被拦在领域层：终态被破坏")
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("非法的生命周期迁移")
                .hasMessageContaining("tool-x");
    }

    @Test
    @DisplayName("⛔ 跳过校验直接发布（DRAFT → PUBLISHED）被拒")
    void draftCannotJumpToPublished() {
        assertThatThrownBy(() -> service.transition("tool", null, "tool-x", 1,
                ConfigLifecycleState.DRAFT, ConfigLifecycleState.PUBLISHED))
                .isInstanceOf(ConfigPublishRejectedException.class);
    }

    @Test
    @DisplayName("⛔ PUBLISHED 不能靠改状态列获得（发布 = 发新版本行）")
    void publishedCannotBeReachedByColumnUpdate() {
        assertThatThrownBy(() -> service.transition("tool", null, "tool-x", 1,
                ConfigLifecycleState.VALIDATED, ConfigLifecycleState.PUBLISHED))
                .as("⛔ 允许改状态列自称已发布：未发布对象可绕过「未发布不可启用」")
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("PUBLISHED 不能通过修改状态列获得");
    }

    @Test
    @DisplayName("✅ 已发布可停用（PUBLISHED → DEPRECATED）")
    void publishedCanBeDeprecated() {
        port.states.put("tool-x@1", ConfigLifecycleState.PUBLISHED);
        assertThat(service.transition("tool", null, "tool-x", 1,
                ConfigLifecycleState.PUBLISHED, ConfigLifecycleState.DEPRECATED)).isTrue();
        assertThat(service.stateOf("tool", null, "tool-x", 1))
                .contains(ConfigLifecycleState.DEPRECATED);
    }

    /**
     * 内存端口替身（只实现 CAS 语义）。
     */
    private static final class InMemoryPort implements ConfigLifecyclePort {

        private final Map<String, ConfigLifecycleState> states = new HashMap<>();

        private static String key(String objectId, int version) {
            return objectId + "@" + version;
        }

        @Override
        public boolean transition(String type, String tenantId, String objectId, int version,
                                  ConfigLifecycleState expectedFrom, ConfigLifecycleState to) {
            String k = key(objectId, version);
            ConfigLifecycleState current = states.get(k);
            if (current == null) {
                current = ConfigLifecycleState.DRAFT;
            }
            if (current != expectedFrom) {
                return false;
            }
            states.put(k, to);
            return true;
        }

        @Override
        public Optional<ConfigLifecycleState> stateOf(String type, String tenantId,
                                                       String objectId, int version) {
            return Optional.ofNullable(states.get(key(objectId, version)));
        }
    }
}
