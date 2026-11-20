package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import cn.studykid.multiagent.infrastructure.persistence.model.ReviewTaskDO;

public interface ReviewTaskMapper {

    /** INSERT RETURNING task_id */
    String create(@Param("traceId") String traceId,
                  @Param("tenantId") String tenantId,
                  @Param("runId") String runId,
                  @Param("source") String source,
                  @Param("priority") String priority,
                  @Param("note") String note);

    int assign(@Param("taskId") String taskId, @Param("assignee") String assignee);

    int resolve(@Param("taskId") String taskId,
                @Param("rootCause") String rootCause,
                @Param("note") String note);

    int dismiss(@Param("taskId") String taskId, @Param("note") String note);

    /** 检查是否已有待处理（PENDING/IN_REVIEW）任务 */
    boolean existsPendingByTraceId(@Param("traceId") String traceId);

    /** 动态条件 list（status / tenantId 可空，默认 PENDING+IN_REVIEW） */
    List<ReviewTaskDO> list(@Param("status") String status,
                            @Param("tenantId") String tenantId,
                            @Param("limit") int limit);
}
