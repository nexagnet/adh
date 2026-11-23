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
}
