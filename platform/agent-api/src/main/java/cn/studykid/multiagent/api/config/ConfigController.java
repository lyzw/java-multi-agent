package cn.studykid.multiagent.api.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配置只读端点（P4-D07 / C1）。
 *
 * <p><b>⚠️ 端点默认受 {@code anyRequest().authenticated()} 保护</b>
 * （见 {@code SecurityConfiguration}）：新增业务端点无需改
 * {@code PUBLIC_ENDPOINTS}，漏配置即拒绝而非放行。
 * 后端授权由 Security 兜底；前端 {@code RequireRole} 因 G1（登录响应不含 roles）
 * 未强制，属已知体验缺口，非安全缺口。
 *
 * <p><b>⚠️ 只读、零副作用、不暴露密钥</b>：
 * 仅返回 yml 声明值 + 生效标志 + 零保留运行态。绝不返回任何密钥 / 口令 / 私钥。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigReadService readService;

    public ConfigController(ConfigReadService readService) {
        this.readService = readService;
    }

    /**
     * 返回当前配置快照（声明值 vs 运行时实际生效值）。
     *
     * <p>⚠️ 响应结构见 {@link ConfigResponse}。每条配置均带 {@code effective}，
     * 未接线的键显式为 {@code false}——防止前端把 yml 摆设值误判为已生效。
     */
    @GetMapping
    public ResponseEntity<ConfigResponse> config() {
        return ResponseEntity.ok(readService.snapshot());
    }
}
