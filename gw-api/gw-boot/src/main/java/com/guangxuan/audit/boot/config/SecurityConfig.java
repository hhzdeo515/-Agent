package com.guangxuan.audit.boot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 安全配置。
 *
 * <p><b>重要：这里的放行规则不是安全边界</b>。真正的授权在三处：
 * <ol>
 *   <li>接口层 {@code @PreAuthorize}（{@link EnableMethodSecurity} 开启）；</li>
 *   <li>领域层守卫（{@code Actor.requirePermission / requireLegal}）——内部调用也走这里；</li>
 *   <li>数据库触发器（越权关闭、非法跃迁、非 LEGAL 签名一律拒绝）。</li>
 * </ol>
 *
 * <p>之所以三层都要有：前端按钮可以隐藏但会被绕过；后端接口校验挡不住内部调用与回调；
 * 数据库触发器是唯一能挡住"绕过应用层直接改库"的手段（AGENTS.md 第 8 条）。
 *
 * <p>当前为便于本地联调，除 actuator 外全部放行；接入 JWT 后应改为
 * {@code .anyRequest().authenticated()} 并装配过滤器。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/info").permitAll()
                        // TODO 接入 JWT 后收紧为 authenticated()
                        .anyRequest().permitAll()
                )
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        // 前端本地开发端口；生产应改为具体域名白名单
        cfg.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
