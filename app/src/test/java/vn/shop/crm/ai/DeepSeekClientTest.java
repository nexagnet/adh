package vn.shop.crm.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lớp gọi DeepSeek: đúng địa chỉ, khóa chỉ ở tiêu đề, lỗi nào cũng thành AiUnavailableException không lộ khóa. */
class DeepSeekClientTest {

    private static final String KEY = "sk-test-khong-that-123456";
    private HttpServer server;
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{\"choices\":[{\"message\":{\"content\":\"Xin chào\"}}]}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            path.set(ex.getRequestURI().getPath());
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private DeepSeekClient client(String model) {
        return new DeepSeekClient(new AiProperties(true, KEY, model, "http://127.0.0.1:" + server.getAddress().getPort(),
                100, 2, 200));
    }

    @Test
    void goiDungDiaChiVoiKhoOTieuDeVaTraNoiDung() {
        String out = client("mo-hinh-thu").complete("hệ thống", "câu hỏi", false);
        assertThat(out).isEqualTo("Xin chào");
        assertThat(path.get()).isEqualTo("/chat/completions");
        assertThat(auth.get()).isEqualTo("Bearer " + KEY);
        assertThat(body.get()).contains("\"model\":\"mo-hinh-thu\"").contains("câu hỏi").contains("\"stream\":false").contains("\"thinking\":{\"type\":\"disabled\"}")
                .doesNotContain(KEY).doesNotContain("response_format");
        client("m").complete("s", "u", true);
        assertThat(body.get()).contains("response_format");
    }

    @Test
    void luotDaiDuocNoiChuNhungCoTranCung() {
        client("m").complete("s", "u", true);
        assertThat(body.get()).contains("\"max_tokens\":200");
        client("m").complete("s", "u", true, new AiClient.Options(1500, 30));
        assertThat(body.get()).contains("\"max_tokens\":1500");
        client("m").complete("s", "u", true, new AiClient.Options(999999, 30));
        assertThat(body.get()).contains("\"max_tokens\":" + AiClient.Options.HARD_MAX_TOKENS);
        client("m").complete("s", "u", true, new AiClient.Options(1, 30));
        assertThat(body.get()).contains("\"max_tokens\":50");
    }

    @Test
    void loiHttpHoacTraLoiLaThanhNgoaiLeKhongLoKhoa() {
        status = 401;
        assertThatThrownBy(() -> client("m").complete("s", "u", false)).isInstanceOf(AiClient.AiUnavailableException.class)
                .hasMessageNotContaining(KEY);
        status = 200;
        reply = "{\"choices\":[]}";
        assertThatThrownBy(() -> client("m").complete("s", "u", false)).isInstanceOf(AiClient.AiUnavailableException.class);
        reply = "khong phai json";
        assertThatThrownBy(() -> client("m").complete("s", "u", false)).isInstanceOf(AiClient.AiUnavailableException.class)
                .hasMessageNotContaining(KEY);
    }

    @Test
    void khongNoiDuocThanhNgoaiLe() {
        int port = server.getAddress().getPort();
        server.stop(0);
        var c = new DeepSeekClient(new AiProperties(true, KEY, "m", "http://127.0.0.1:" + port, 100, 2, 200));
        assertThatThrownBy(() -> c.complete("s", "u", false)).isInstanceOf(AiClient.AiUnavailableException.class)
                .hasMessageNotContaining(KEY);
    }

    @Test
    void khoApiKhongBaoGiGuiTrenDuongKhongMaHoa() {
        assertThatThrownBy(() -> new DeepSeekClient(new AiProperties(true, KEY, "m", "http://api.example.com", 100, 2, 200)))
                .isInstanceOf(IllegalStateException.class);
    }
}
