package vn.shop.crm.security;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/** Đếm đăng nhập sai/đúng để {@link LoginThrottle} khóa hoặc mở khóa. Không ghi tên đăng nhập hay mật khẩu vào log. */
@Component
class LoginThrottleListener {

    private final LoginThrottle throttle;

    LoginThrottleListener(LoginThrottle throttle) {
        this.throttle = throttle;
    }

    @EventListener
    void onFailure(AbstractAuthenticationFailureEvent event) {
        Authentication auth = event.getAuthentication();
        if (auth.getDetails() instanceof WebAuthenticationDetails details) {
            throttle.recordFailure(details.getRemoteAddress(), auth.getName());
        }
    }

    @EventListener
    void onSuccess(AuthenticationSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        if (auth.getDetails() instanceof WebAuthenticationDetails details) {
            throttle.recordSuccess(details.getRemoteAddress(), auth.getName());
        }
    }
}
