package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

/** student_memory 记忆巩固（DISTINCT ON / upsert）。 */
public interface MemoryMapper {

    List<MemoryRow> listMemories(@Param("tenantId") String tenantId,
                                  @Param("studentId") String studentId);
    List<WeaknessSeedRow> latestWeaknesses(@Param("tenantId") String tenantId,
                                            @Param("studentId") String studentId,
                                            @Param("limit") int limit);
    MemoryRow findMemory(@Param("tenantId") String tenantId,
                          @Param("studentId") String studentId,
                          @Param("knowledgePoint") String knowledgePoint);
    int insertMemory(@Param("tenantId") String tenantId, @Param("studentId") String studentId,
                      @Param("knowledgePoint") String knowledgePoint, @Param("errorType") String errorType,
                      @Param("strength") double strength, @Param("evidenceCount") int evidenceCount,
                      @Param("firstSeenAt") Instant firstSeenAt, @Param("lastSeenAt") Instant lastSeenAt,
                      @Param("lastRunId") String lastRunId);
    int updateMemory(@Param("tenantId") String tenantId, @Param("studentId") String studentId,
                      @Param("knowledgePoint") String knowledgePoint, @Param("errorType") String errorType,
                      @Param("strength") double strength, @Param("evidenceCount") int evidenceCount,
                      @Param("lastSeenAt") Instant lastSeenAt, @Param("lastRunId") String lastRunId);
    int deleteMemories(@Param("tenantId") String tenantId, @Param("studentId") String studentId);
    long countMemories(@Param("tenantId") String tenantId, @Param("studentId") String studentId);

    record MemoryRow(String studentId, String knowledgePoint, String errorType, double strength,
                     int evidenceCount, Instant firstSeenAt, Instant lastSeenAt, String lastRunId) {}
    record WeaknessSeedRow(String knowledgePoint, String errorType, Instant createdAt) {}
}
