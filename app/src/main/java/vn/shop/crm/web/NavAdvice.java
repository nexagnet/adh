package vn.shop.crm.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Thymeleaf 3.1 không còn {@code #request}, nên đường dẫn hiện tại được đưa vào mô hình ở đây
 * để thanh bên biết mục nào đang mở.
 */
@ControllerAdvice
public class NavAdvice {

    @ModelAttribute("navKey")
    public String navKey(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return NavMenu.keyOf(path, request.getParameter("segment"));
    }
}
