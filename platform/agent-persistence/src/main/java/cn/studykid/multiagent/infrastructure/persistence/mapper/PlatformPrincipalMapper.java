package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import cn.studykid.multiagent.infrastructure.persistence.mapper.OpsMapper;

/** platform.platform_user / platform_user_role 平台身份（opsSqlSessionFactory）。 */
@OpsMapper
public interface PlatformPrincipalMapper {

    PlatformUserRow findByUsername(@Param("username") String username);
    PlatformUserRow findById(@Param("userId") String userId);
    List<PlatformUserRow> listUsers();
    List<String> findRoles(@Param("userId") String userId);
    List<RoleAssignmentRow> rolesByUser();
    PlatformUserRow insertUser(@Param("username") String username, @Param("email") String email,
                                @Param("displayName") String displayName, @Param("passwordHash") String passwordHash,
                                @Param("authSource") String authSource, @Param("mfaRequired") boolean mfaRequired);
    int updateProfile(@Param("userId") String userId, @Param("email") String email,
                       @Param("displayName") String displayName, @Param("mfaRequired") Boolean mfaRequired);
    int setStatus(@Param("userId") String userId, @Param("status") String status);
    int updatePasswordHash(@Param("userId") String userId, @Param("passwordHash") String passwordHash);
    int assignRole(@Param("userId") String userId, @Param("roleName") String roleName,
                    @Param("grantedBy") String grantedBy);
    int revokeRole(@Param("userId") String userId, @Param("roleName") String roleName);
    int bumpAuthzVersion(@Param("userId") String userId);

    record PlatformUserRow(String id, String username, String email, String displayName,
                           String status, String authSource, boolean mfaRequired, long authzVersion,
                           Instant lastLoginAt, String passwordHash, Instant createdAt, Instant updatedAt) {}
    record RoleAssignmentRow(String platformUserId, String roleName) {}
}
