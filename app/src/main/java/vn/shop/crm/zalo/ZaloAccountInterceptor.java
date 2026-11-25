package vn.shop.crm.zalo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Trang Zalo làm việc với MỘT tài khoản phụ tại một lúc (D23). Tài khoản chọn bằng {@code ?account=N}, nhớ trong phiên đăng nhập của người dùng
 * quản trị; mọi nút bấm trên trang (hạn mức, đăng nhập QR, dừng, mở lại...) sau đó áp dụng cho tài khoản đang chọn mà không phải sửa từng biểu mẫu.
 * Mã lạ hoặc tài khoản chưa khai báo cầu nối thì dùng tài khoản 1. Chỉ gắn trong luồng của yêu cầu và luôn gỡ khi xong.
 */
@Component
public class ZaloAccountInterceptor implements HandlerInterceptor {

    static final String SESSION_KEY = "zaloAccount";

    private final ZaloAccountRegistry registry;

    public ZaloAccountInterceptor(ZaloAccountRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        long account = ZaloAccountContext.DEFAULT_ACCOUNT;
        String param = request.getParameter("account");
        HttpSession session = param != null ? request.getSession() : request.getSession(false);
        if (param != null) {
            try {
                long want = Long.parseLong(param.strip());
                if (registry.selectable(want)) {
                    session.setAttribute(SESSION_KEY, want);
                }
            } catch (NumberFormatException ignored) {
                // mã lạ: giữ lựa chọn cũ
            }
        }
        if (session != null && session.getAttribute(SESSION_KEY) instanceof Long saved && registry.selectable(saved)) {
            account = saved;
        }
        ZaloAccountContext.bind(account);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        ZaloAccountContext.unbind();
    }
}
