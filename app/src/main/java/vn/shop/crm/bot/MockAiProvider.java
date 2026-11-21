package vn.shop.crm.bot;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Không bao giờ đưa ra câu trả lời: bot chỉ dùng FAQ đã duyệt, còn lại chuyển người thật. */
@Component
public class MockAiProvider implements AiProvider {

    @Override
    public Optional<String> suggest(String maskedQuestion, List<String> approvedFaqAnswers) {
        return Optional.empty();
    }
}
