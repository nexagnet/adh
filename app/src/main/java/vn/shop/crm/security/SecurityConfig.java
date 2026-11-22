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
 * Một tài khoản quản trị (bố mẹ/chủ cửa hàng). Mật khẩu bắt buộc đặt qua biến môi trường.
 * Giai đoạn 2 mới thêm tài khoản nhân viên.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, LoginThrottle throttle,
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
                        .requestMatchers("/login", "/app.css", "/error", "/i/**", "/n", "/moi", "/moi/**").permitAll()
                        // Trang Zalo tài khoản phụ (D16): có mã QR đăng nhập tài khoản, chỉ admin (vai trò OWNER).
                        .requestMatchers("/zalo", "/zalo/**").hasRole("OWNER")
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
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
                                          PasswordEncoder encoder) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Chưa đặt mật khẩu quản trị. Hãy đặt biến môi trường APP_ADMIN_PASSWORD trước khi chạy.");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password(encoder.encode(password)).roles("OWNER").build());
    }
}
