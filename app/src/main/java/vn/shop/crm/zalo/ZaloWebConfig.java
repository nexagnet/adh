package vn.shop.crm.zalo;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Gắn bộ chọn tài khoản cho các trang quản trị Zalo (D23). */
@Configuration
public class ZaloWebConfig implements WebMvcConfigurer {

    private final ZaloAccountInterceptor interceptor;

    public ZaloWebConfig(ZaloAccountInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/zalo", "/zalo/**");
    }
}
