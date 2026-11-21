package vn.shop.crm.bot;

import java.util.List;
import java.util.Optional;

/**
 * Nhà cung cấp AI tùy chọn để gợi ý câu trả lời. Chỉ nhận văn bản ĐÃ CHE thông tin cá nhân và các câu FAQ đã duyệt.
 * Chưa có bản cài thật (chưa chọn nhà cung cấp, chưa có hạn mức chi tiêu); mặc định dùng {@link MockAiProvider}.
 */
public interface AiProvider {

    Optional<String> suggest(String maskedQuestion, List<String> approvedFaqAnswers);
}
