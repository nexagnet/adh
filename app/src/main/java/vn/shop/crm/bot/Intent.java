package vn.shop.crm.bot;

/** Ý định nhận diện bằng luật (không dùng AI): quyết định bot trả lời hay chuyển người thật. */
public enum Intent {
    WANTS_HUMAN("Khách muốn gặp/gọi người thật"),
    COMPLAINT("Khiếu nại, hàng lỗi, đổi trả"),
    ORDER("Muốn đặt hàng hoặc hỏi giá"),
    QUESTION("Câu hỏi hướng tới cửa hàng"),
    OTHER("Trao đổi bình thường");

    private final String label;

    Intent(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
