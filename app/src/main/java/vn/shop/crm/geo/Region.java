package vn.shop.crm.geo;

/** Ba miền theo cách gọi thông thường. Tây Nguyên xếp vào miền Trung. */
public enum Region {
    BAC("Miền Bắc"), TRUNG("Miền Trung"), NAM("Miền Nam");

    private final String label;

    Region(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
