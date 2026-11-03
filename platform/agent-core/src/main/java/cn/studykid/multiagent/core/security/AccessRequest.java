package cn.studykid.multiagent.core.security;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.tenant.SubjectContext;

/**
 * 一次鉴权请求：谁（subject）对什么（resource）做什么（action）。
 */
public record AccessRequest(
        @NonNull SubjectContext subject,
        @NonNull Action action,
        @NonNull ResourceRef resource) {
}
