package vn.shop.crm.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Đăng nhập bắt buộc, và đi hết luồng nhập file qua giao diện để bắt lỗi template. */
class WebSecurityTest extends AbstractPostgresTest {

    private static final String CSV = """
            Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái
            VTP1,DH1,05/01/2026 09:00:00,Nguyen Van A,0912345678,"12 Duong A, Quan 1",Giao thành công
            VTP2,DH2,06/01/2026 09:00:00,Tran Thi B,0987654321,34 Duong B,Chờ xử lý
            VTP3,DH3,07/01/2026 09:00:00,Le Van C,,56 Duong C,Giao thành công
            """;

    @Autowired
    WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void chuaDangNhapBiChuyenSangTrangDangNhap() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/customers")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/customers/export.csv")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/imports/new")).andExpect(status().is3xxRedirection());
    }

    @Test
    void trangDangNhapMoDuocKhongCanDangNhap() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Đăng nhập")));
    }

    @Test
    void postKhongCoCsrfBiTuChoi() throws Exception {
        mvc.perform(post("/customers/recalculate").with(user("admin").roles("OWNER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void cacTrangChinhRenderDuocKhiDangNhap() throws Exception {
        var owner = user("admin").roles("OWNER");
        for (String path : new String[]{"/", "/imports", "/imports/new", "/customers", "/status-mappings"}) {
            mvc.perform(get(path).with(owner)).andExpect(status().isOk());
        }
    }

    @Test
    void luongNhapFileQuaGiaoDienTuDauDenCuoi() throws Exception {
        var owner = user("admin").roles("OWNER");

        // 1. tải file lên
        MvcResult upload = mvc.perform(multipart("/imports")
                        .file(new MockMultipartFile("file", "don.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8)))
                        .param("carrier", "VIETTEL_POST").with(csrf()).with(owner))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/imports/*/mapping")).andReturn();
        String mappingUrl = upload.getResponse().getRedirectedUrl();
        String id = mappingUrl.split("/")[2];

        // 2. trang chọn cột có đoán sẵn đúng cột
        String mappingHtml = mvc.perform(get(mappingUrl).with(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(mappingHtml).contains("Chọn cột dữ liệu").contains("SĐT người nhận");
        assertThat(mappingHtml).containsPattern("<option value=\"SĐT người nhận\"[^>]*selected");

        // 3. xem trước
        String previewHtml = mvc.perform(post("/imports/" + id + "/preview").with(csrf()).with(owner)
                        .param("headerRow", "1")
                        .param("col_TRACKING_NO", "Mã vận đơn").param("col_ORDER_REF", "Mã đơn hàng")
                        .param("col_RECEIVER_PHONE", "SĐT người nhận").param("col_RECEIVER_NAME", "Tên người nhận")
                        .param("col_RECEIVER_ADDRESS", "Địa chỉ người nhận").param("col_STATUS", "Trạng thái")
                        .param("col_CREATED_ON", "Ngày tạo"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(previewHtml).contains("Xem trước kết quả nhập").contains("Chờ xử lý").contains("Thiếu số điện thoại");

        // 4. xác nhận
        mvc.perform(post("/imports/" + id + "/commit").with(csrf()).with(owner)
                        .param("headerRow", "1")
                        .param("col_TRACKING_NO", "Mã vận đơn").param("col_ORDER_REF", "Mã đơn hàng")
                        .param("col_RECEIVER_PHONE", "SĐT người nhận").param("col_RECEIVER_NAME", "Tên người nhận")
                        .param("col_RECEIVER_ADDRESS", "Địa chỉ người nhận").param("col_STATUS", "Trạng thái")
                        .param("col_CREATED_ON", "Ngày tạo"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/imports/" + id));

        // 5. các trang kết quả
        String detail = mvc.perform(get("/imports/" + id).with(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail).contains("Kết quả nhập").contains("Dòng lỗi");
        String oneTime = mvc.perform(get("/customers?segment=ONE_TIME").with(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(oneTime).contains("0912 345 678").contains("Nguyen Van A");
        String status = mvc.perform(get("/status-mappings").with(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(status).contains("Chờ xử lý");
        mvc.perform(get("/imports/" + id + "/errors.csv").with(owner)).andExpect(status().isOk());

        // chi tiết khách: thấy từng đơn tạo ra kết quả (tiêu chí 5)
        long customerId = jdbc.sql("SELECT id FROM customer WHERE phone = '+84912345678'")
                .query(Long.class).single();
        String customer = mvc.perform(get("/customers/" + customerId).with(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(customer).contains("VTP1").contains("Giao thành công");
    }

    @Test
    void xuatCsvCoBomVaChongCongThuc() throws Exception {
        jdbc.sql("""
                INSERT INTO import_batch (carrier, filename, sha256, file_content) VALUES ('VIETTEL_POST','x','x','\\x00')
                """).update();
        jdbc.sql("""
                INSERT INTO customer (phone, name, address, segment, successful_purchases)
                VALUES ('+84912345678', '=HYPERLINK("http://x")', 'a', 'ONE_TIME', 1)""").update();

        MvcResult result = mvc.perform(get("/customers/export.csv?segment=ONE_TIME")
                        .with(user("admin").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body[0]).isEqualTo((byte) 0xEF);
        String text = new String(body, StandardCharsets.UTF_8);
        assertThat(text).contains("'=HYPERLINK").doesNotContain(",=HYPERLINK").doesNotContain("\"=HYPERLINK");
        assertThat(text).contains("0912 345 678").doesNotContain("'+84");
    }
}
