package vn.shop.crm.customers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tính lại khách khi cách tính đổi (khách còn ở phiên bản cũ) và khi thời gian trôi làm đơn thành "kẹt".
 * Chạy lúc khởi động và mỗi ngày một lần, vì đơn kẹt đổi trạng thái theo ngày dù không có file mới.
 */
@Component
public class CustomerRefresh implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CustomerRefresh.class);

    private final CustomerService customers;

    public CustomerRefresh(CustomerService customers) {
        this.customers = customers;
    }

    @Override
    public void run(ApplicationArguments args) {
        refresh();
    }

    /** 03:15 sáng giờ Việt Nam hằng ngày. */
    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Ho_Chi_Minh")
    public void daily() {
        refresh();
    }

    public void refresh() {
        int n = customers.refreshStale();
        if (n > 0) {
            log.info("Đã tính lại phân loại cho {} khách (đổi cách tính hoặc có đơn kẹt)", n);
        }
    }
}
