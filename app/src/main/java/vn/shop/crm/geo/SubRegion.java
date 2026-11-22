package vn.shop.crm.geo;

/** Vùng nhỏ hơn miền, dùng để gom các tỉnh gần nhau vào cùng nhóm Zalo. */
public enum SubRegion {
    TAY_BAC(Region.BAC, "Tây Bắc"),
    DONG_BAC(Region.BAC, "Đông Bắc"),
    DB_SONG_HONG(Region.BAC, "Đồng bằng sông Hồng"),
    BAC_TRUNG_BO(Region.TRUNG, "Bắc Trung Bộ"),
    NAM_TRUNG_BO(Region.TRUNG, "Nam Trung Bộ"),
    TAY_NGUYEN(Region.TRUNG, "Tây Nguyên"),
    DONG_NAM_BO(Region.NAM, "Đông Nam Bộ"),
    DB_SONG_CUU_LONG(Region.NAM, "Đồng bằng sông Cửu Long");

    private final Region region;
    private final String label;

    SubRegion(Region region, String label) {
        this.region = region;
        this.label = label;
    }

    public Region region() {
        return region;
    }

    public String label() {
        return label;
    }
}
