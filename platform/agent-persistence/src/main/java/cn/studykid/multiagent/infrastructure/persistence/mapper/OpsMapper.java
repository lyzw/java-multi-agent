package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「只能由 opsSqlSessionFactory 绑定」的 Mapper 接口。
 *
 * <p>这些接口的 statement 只存在于 {@code resources/mapper/ops/**}：
 * platform 域表（sys_dictionary / review_task / model_catalog）不走租户通道，
 * 应用角色连接没有读写权限。租户通道的 {@code @MapperScan} 必须排除本注解，
 * 否则接口会被绑到 tenantSqlSessionFactory，运行期抛
 * {@code BindingException: Invalid bound statement}。
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface OpsMapper {
}
