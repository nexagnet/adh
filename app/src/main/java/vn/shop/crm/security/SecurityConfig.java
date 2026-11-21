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

/**
 * Một tài khoản quản trị (bố mẹ/chủ cửa hàng). Mật khẩu bắt buộc đặt qua biến môi trường.
 * Giai đoạn 2 mới thêm tài khoản nhân viên.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, LoginThrottle throttle) throws Exception {
        http
                // Trang mời công khai: chỉ POST "vào nhóm"/"ngừng nhận tin", idempotent và chỉ dùng token ngẫu nhiên,
                // không có phiên đăng nhập nên miễn CSRF để khách không phải mang cookie.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/i/**", "/moi/**"))
                // Giới hạn đăng nhập sai (mốc 1F). Không đăng ký thành @Bean để khỏi bị nạp hai lần.
                .addFilterBefore(new LoginThrottleFilter(throttle),
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/app.css", "/error", "/i/**", "/moi", "/moi/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .headers(headers -> headers
                        // Token nằm trong URL: không gửi Referer sang trang khác (ví dụ zalo.me).
                        .referrerPolicy(r -> r.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; style-src 'self'; form-action 'self'; frame-ancestors 'none'")));
        return http.build();
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
