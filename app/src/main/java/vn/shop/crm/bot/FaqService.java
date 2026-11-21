package vn.shop.crm.bot;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.ImportException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Câu hỏi thường gặp do cửa hàng soạn. Bot chỉ trả lời bằng nội dung ở đây. */
@Service
public class FaqService {

    public record Faq(long id, String title, String keywords, String answer, boolean enabled) {
        List<String> keywordList() {
            return Arrays.stream(keywords.split("[,\\n]")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        }
    }

    private final JdbcClient jdbc;

    public FaqService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Faq> list() {
        return jdbc.sql("SELECT id, title, keywords, answer, enabled FROM faq_entry ORDER BY id")
                .query((rs, i) -> new Faq(rs.getLong("id"), rs.getString("title"), rs.getString("keywords"),
                        rs.getString("answer"), rs.getBoolean("enabled"))).list();
    }

    @Transactional
    public long create(String title, String keywords, String answer) {
        validate(title, keywords, answer);
        return jdbc.sql("INSERT INTO faq_entry (title, keywords, answer) VALUES (:t, :k, :a) RETURNING id")
                .param("t", title.strip()).param("k", keywords.strip()).param("a", answer.strip())
                .query(Long.class).single();
    }

    @Transactional
    public void update(long id, String title, String keywords, String answer, boolean enabled) {
        validate(title, keywords, answer);
        int n = jdbc.sql("""
                        UPDATE faq_entry SET title = :t, keywords = :k, answer = :a, enabled = :e, updated_at = now()
                        WHERE id = :id""")
                .param("t", title.strip()).param("k", keywords.strip()).param("a", answer.strip())
                .param("e", enabled).param("id", id).update();
        if (n == 0) {
            throw new ImportException("Không tìm thấy câu hỏi.");
        }
    }

    @Transactional
    public void delete(long id) {
        jdbc.sql("DELETE FROM faq_entry WHERE id = :id").param("id", id).update();
    }

    /** Câu FAQ đang bật khớp nhiều từ khóa nhất với tin nhắn; hòa thì lấy câu soạn trước. */
    public Optional<Faq> match(String text) {
        String n = TextNorm.words(text);
        Faq best = null;
        long bestScore = 0;
        for (Faq f : list()) {
            if (!f.enabled()) {
                continue;
            }
            long score = f.keywordList().stream().filter(k -> TextNorm.containsPhrase(n, k)).count();
            if (score > bestScore) {
                best = f;
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    private static void validate(String title, String keywords, String answer) {
        if (title == null || title.isBlank() || title.strip().length() > 100) {
            throw new ImportException("Hãy đặt tiêu đề cho câu hỏi (tối đa 100 ký tự).");
        }
        if (keywords == null || keywords.isBlank() || keywords.length() > 300) {
            throw new ImportException("Hãy nhập từ khóa để bot nhận ra câu hỏi, cách nhau bằng dấu phẩy.");
        }
        if (answer == null || answer.isBlank() || answer.strip().length() > 800) {
            throw new ImportException("Câu trả lời không được để trống (tối đa 800 ký tự).");
        }
    }
}
