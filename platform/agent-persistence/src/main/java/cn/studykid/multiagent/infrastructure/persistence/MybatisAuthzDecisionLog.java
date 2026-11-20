package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.AuthzDecisionLogMapper;
import cn.studykid.multiagent.infrastructure.security.AuthzDecisionRecorder;

/** AuthzDecisionRecorder MyBatis 实现（fail-soft：审计写入失败不阻断授权）。 */
@Repository
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisAuthzDecisionLog implements AuthzDecisionRecorder {

    private static final Logger log = LoggerFactory.getLogger(MybatisAuthzDecisionLog.class);

    private final AuthzDecisionLogMapper mapper;

    public MybatisAuthzDecisionLog(@NonNull AuthzDecisionLogMapper mapper,
                                    @Value("${spring.datasource.url:}") String ignored) {
        this.mapper = mapper;
    }

    @Override
    public void record(@NonNull SubjectContext subject, @NonNull Action action,
                        @NonNull ResourceRef resource, boolean permitted, @Nullable String reasonCode) {
        try {
            mapper.insert(Instant.now(), subject.tenantId(), subject.principalId(),
                    subject.scope().name(), action.name(), resource.type(), permitted, reasonCode);
        } catch (RuntimeException e) {
            // ⚠️ 授权日志是旁路观测，写失败不得影响授权判定本身
            log.warn("授权决策日志写入失败: action={}, resource={}, permitted={}, cause={}",
                    action, resource.type(), permitted, e.toString());
        }
    }
}
