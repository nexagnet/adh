package vn.shop.crm.zalo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import vn.shop.crm.common.ImportException;

/**
 * Dùng mã tài khoản trong từng yêu cầu để các tab quản trị có thể làm việc với các cầu nối khác nhau.
 * Phiên đăng nhập chỉ ghi nhớ lựa chọn khi mở trang tổng quan Zalo.
 */
@Component
public class ZaloAccountInterceptor implements HandlerInterceptor {

    private static final String SESSION_KEY = "zaloAccount";

    private final ZaloAccountRegistry registry;

    public ZaloAccountInterceptor(ZaloAccountRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        boolean dashboard = "/zalo".equals(path) && HttpMethod.GET.matches(request.getMethod());
        String[] parameters = request.getParameterValues("account");
        Long selected = parseAccount(parameters);
        if (dashboard) {
            selected = dashboardAccount(request, selected);
        } else if ((parameters != null && selected == null)
                || (selected == null && registry.multipleConfigured() && requiresAccount(request, path))) {
            throw new ImportException("Hãy mở lại trang Zalo, chọn đúng tài khoản rồi thực hiện lại thao tác.");
        }
        ZaloAccountContext.bind(selected == null ? ZaloAccountContext.DEFAULT_ACCOUNT : selected);
        return true;
    }

    private Long parseAccount(String[] parameters) {
        if (parameters == null || parameters.length != 1) {
            return null;
        }
        try {
            long id = Long.parseLong(parameters[0].strip());
            return registry.selectable(id) ? id : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long dashboardAccount(HttpServletRequest request, Long selected) {
        if (selected != null) {
            request.getSession().setAttribute(SESSION_KEY, selected);
            return selected;
        }
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(SESSION_KEY) instanceof Long saved && registry.selectable(saved)
                ? saved : null;
    }

    private static boolean requiresAccount(HttpServletRequest request, String path) {
        // Đọc trạng thái QR có thể xác nhận hoàn tất đăng nhập. Các đường còn lại bên dưới dùng cấu hình chung hoặc đã có mã trong URL.
        return "/zalo/login".equals(path) || (HttpMethod.POST.matches(request.getMethod())
                && !path.startsWith("/zalo/reply/") && !path.equals("/zalo/accounts/{id}/enabled")
                && !path.equals("/zalo/comeback-mode") && !path.equals("/zalo/messages/total-limit")
                && !path.equals("/zalo/test-numbers") && !path.equals("/zalo/test-numbers/remove"));
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView result) {
        if (result != null && result.getViewName() != null
                && (!result.getViewName().startsWith("redirect:") || registry.multipleConfigured())) {
            result.getModelMap().putIfAbsent("account", ZaloAccountContext.current());
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        ZaloAccountContext.unbind();
    }
}
