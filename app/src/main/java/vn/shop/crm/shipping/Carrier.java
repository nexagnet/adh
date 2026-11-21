package vn.shop.crm.shipping;

/** Nguồn đơn ngoài TikTok (BR-01). Thiết kế cho phép thêm hãng sau này. */
public enum Carrier {
    VIETTEL_POST("Viettel Post"),
    JT_EXPRESS("J&T Express");

    private final String label;

    Carrier(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
