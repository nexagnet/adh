package vn.shop.crm.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import vn.shop.crm.common.ImportException;

/** Hiển thị lỗi nghiệp vụ bằng tiếng Việt thay vì trang lỗi kỹ thuật. */
@ControllerAdvice
public class ErrorAdvice {

    @ExceptionHandler(ImportException.class)
    public String importError(ImportException e, Model model, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        model.addAttribute("message", e.getMessage());
        return "error-message";
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(Model model, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        model.addAttribute("message", "File quá lớn (tối đa 10 MB).");
        return "error-message";
    }
}
