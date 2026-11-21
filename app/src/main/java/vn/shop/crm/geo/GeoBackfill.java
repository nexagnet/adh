package vn.shop.crm.geo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import vn.shop.crm.customers.CustomerService;

/** Khi khởi động: tính tỉnh cho khách có phiên bản cách đoán cũ (khách đã có trước khi thêm tính năng này). */
@Component
public class GeoBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GeoBackfill.class);

    private final CustomerService customers;

    public GeoBackfill(CustomerService customers) {
        this.customers = customers;
    }

    @Override
    public void run(ApplicationArguments args) {
        int n = customers.refreshProvinces(false);
        if (n > 0) {
            log.info("Đã tính lại khu vực cho {} khách", n);
        }
    }
}
