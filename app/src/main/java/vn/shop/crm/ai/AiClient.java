package vn.shop.crm.ai;

/** Một lượt hỏi AI: chỉ văn bản ĐÃ CHE thông tin cá nhân. Thay được nhà cung cấp bằng cách cài giao diện này. */
public interface AiClient {

    /** Lỗi gọi AI (mạng, hết giờ, trả lời lạ, bị từ chối). Thông điệp không bao giờ chứa khóa hay nội dung khách. */
    class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * @param json true thì yêu cầu AI trả đúng một đối tượng JSON
     * @return nội dung AI trả về (chưa kiểm), không bao giờ null
     * @throws AiUnavailableException khi không có câu trả lời dùng được
     */
    String complete(String system, String user, boolean json);

    /** Giới hạn riêng cho một lượt dài hơn bình thường (ví dụ đề xuất chiến dịch). Giá trị được kẹp lại ở nơi gọi. */
    record Options(int maxOutputTokens, int timeoutSeconds, String model, boolean thinking) {
        public static final int HARD_MAX_TOKENS = 2000;
        public static final int HARD_MAX_TIMEOUT = 60;
        /** Khi bật suy nghĩ sâu, các token suy nghĩ cũng tính vào giới hạn đầu ra và lượt gọi lâu hơn, nên trần cứng cao hơn. */
        public static final int HARD_MAX_TOKENS_THINKING = 32000;
        public static final int HARD_MAX_TIMEOUT_THINKING = 120;

        /** Lượt dài nhưng dùng mô hình mặc định và không suy nghĩ sâu. */
        public Options(int maxOutputTokens, int timeoutSeconds) {
            this(maxOutputTokens, timeoutSeconds, null, false);
        }
    }

    /** Mặc định bỏ qua tùy chọn (các bản cài đặt giả trong test vẫn là một hàm). */
    default String complete(String system, String user, boolean json, Options options) {
        return complete(system, user, json);
    }
}
