package vn.shop.crm.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import vn.shop.crm.map.MapSettings;

/**
 * Chủ cửa hàng dùng cấu hình triển khai. Nhân viên chỉ vào hàng việc của nhóm được giao.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, LoginThrottle throttle, StaffMembers staff,
                                            @Value("${APP_ADH_STAGE2_ENABLED:false}") boolean stage2,
                                            @Value("${app.map.tile-url:" + MapSettings.DEFAULT_TILE_URL + "}") String tileUrl)
            throws Exception {
        http
                // Trang mời công khai: chỉ POST "vào nhóm"/"ngừng nhận tin", idempotent và chỉ dùng token ngẫu nhiên,
                // không có phiên đăng nhập nên miễn CSRF để khách không phải mang cookie.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/i/**", "/moi/**"))
                // Giới hạn đăng nhập sai (mốc 1F). Không đăng ký thành @Bean để khỏi bị nạp hai lần.
                .addFilterBefore(new LoginThrottleFilter(throttle),
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/readyz").permitAll()
                        .requestMatchers("/login", "/app.css", "/error", "/i/**", "/n", "/moi", "/moi/**").permitAll()
                        // Trang Zalo tài khoản phụ (D16): có mã QR đăng nhập tài khoản, chỉ admin (vai trò OWNER).
                        .requestMatchers("/zalo", "/zalo/**").hasRole("OWNER")
                        .requestMatchers("/privacy", "/privacy/**", "/customers/*/privacy/**").hasRole("OWNER")
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/staff/work").access((authentication, request) -> {
                            var user = authentication.get();
                            boolean permitted = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_OWNER"))
                                    || employeeActive(user, staff);
                            return new org.springframework.security.authorization.AuthorizationDecision(stage2 && permitted);
                        })
                        .requestMatchers("/staff/work", "/staff/work/**").access((authentication, request) -> {
                            return new org.springframework.security.authorization.AuthorizationDecision(stage2 && employeeActive(authentication.get(), staff));
                        })
                        .anyRequest().access(stage2
                                ? org.springframework.security.authorization.AuthorityAuthorizationManager.hasRole("OWNER")
                                : org.springframework.security.authorization.AuthenticatedAuthorizationManager.authenticated()))
                .formLogin(form -> form.loginPage("/login").successHandler((request, response, authentication) -> {
                    boolean employee = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_TEAM_MEMBER"));
                    response.sendRedirect(request.getContextPath() + (employee ? "/staff/work" : "/"));
                }).permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .headers(headers -> headers
                        // Token nằm trong URL: không gửi Referer sang trang khác (ví dụ zalo.me).
                        .referrerPolicy(r -> r.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; style-src 'self'; img-src 'self' data:" + tileOrigin(tileUrl)
                                        + "; form-action 'self'; frame-ancestors 'none'")));
        return http.build();
    }

    private static boolean employeeActive(org.springframework.security.core.Authentication user, StaffMembers staff) {
        return user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_TEAM_MEMBER"))
                && staff.active(user.getName());
    }

    /** Ô bản đồ là ảnh tải từ máy chủ bản đồ: chỉ mở đúng địa chỉ đó cho ảnh, không mở thêm gì khác. */
    public static String tileOrigin(String tileUrl) {
        try {
            java.net.URI uri = java.net.URI.create(tileUrl.replace("{", "").replace("}", ""));
            if (uri.getScheme() != null && uri.getScheme().equals("https") && uri.getHost() != null) {
                return " " + uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            }
        } catch (IllegalArgumentException e) {
            // địa chỉ lạ: không mở thêm gì
        }
        return "";
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(@Value("${app.admin.username}") String username,
                                          @Value("${app.admin.password:}") String password,
                                          PasswordEncoder encoder, StaffMembers staff,
                                          @Value("${APP_ADH_STAGE2_ENABLED:false}") boolean stage2) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Chưa đặt mật khẩu quản trị. Hãy đặt biến môi trường APP_ADMIN_PASSWORD trước khi chạy.");
        }
        var owner = new InMemoryUserDetailsManager(
                User.withUsername(username).password(encoder.encode(password)).roles("OWNER").build());
        return name -> !stage2 || username.equalsIgnoreCase(name) ? owner.loadUserByUsername(name) : staff.login(name);
    }
}
