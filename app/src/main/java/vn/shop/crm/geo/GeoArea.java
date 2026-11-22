package vn.shop.crm.geo;

import java.util.Optional;

/**
 * Một khu vực để lọc khách hoặc gán cho nhóm Zalo: cả miền, một vùng, hoặc một tỉnh. Chuỗi mã dạng
 * {@code R:NAM}, {@code S:DONG_NAM_BO}, {@code P:can-tho}.
 */
public record GeoArea(Level level, String key) {

    public enum Level { REGION, SUBREGION, PROVINCE }

    /** Mã dùng cho "chưa rõ khu vực" khi lọc khách. */
    public static final String UNKNOWN = "U";

    public static GeoArea region(Region r) {
        return new GeoArea(Level.REGION, r.name());
    }

    public static GeoArea sub(SubRegion s) {
        return new GeoArea(Level.SUBREGION, s.name());
    }

    public static GeoArea province(Province p) {
        return new GeoArea(Level.PROVINCE, p.code());
    }

    /** Đọc từ chuỗi mã; mã sai hoặc rỗng trả rỗng. */
    public static Optional<GeoArea> parse(String code) {
        if (code == null || code.length() < 3 || code.charAt(1) != ':') {
            return Optional.empty();
        }
        String key = code.substring(2);
        try {
            return switch (code.charAt(0)) {
                case 'R' -> Optional.of(region(Region.valueOf(key)));
                case 'S' -> Optional.of(sub(SubRegion.valueOf(key)));
                case 'P' -> VnGeo.byCode(key).map(GeoArea::province);
                default -> Optional.empty();
            };
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public String code() {
        return switch (level) {
            case REGION -> "R:" + key;
            case SUBREGION -> "S:" + key;
            case PROVINCE -> "P:" + key;
        };
    }

    public String label() {
        return switch (level) {
            case REGION -> Region.valueOf(key).label();
            case SUBREGION -> SubRegion.valueOf(key).label();
            case PROVINCE -> VnGeo.byCode(key).map(Province::name).orElse(key);
        };
    }

    public boolean contains(Province p) {
        return switch (level) {
            case REGION -> p.region().name().equals(key);
            case SUBREGION -> p.sub().name().equals(key);
            case PROVINCE -> p.code().equals(key);
        };
    }

    /**
     * Mức gần nhau giữa khu vực này và một tỉnh: 0 cùng tỉnh, 1 cùng vùng, 2 cùng miền, -1 khác miền. Khu vực cấp
     * miền chỉ có thể đạt mức 2; cấp vùng đạt 1 hoặc 2.
     */
    public int closeness(Province p) {
        return switch (level) {
            case REGION -> p.region().name().equals(key) ? 2 : -1;
            case SUBREGION -> {
                if (p.sub().name().equals(key)) {
                    yield 1;
                }
                yield SubRegion.valueOf(key).region() == p.region() ? 2 : -1;
            }
            case PROVINCE -> {
                if (p.code().equals(key)) {
                    yield 0;
                }
                Province own = VnGeo.byCode(key).orElse(null);
                if (own == null) {
                    yield -1;
                }
                if (own.sub() == p.sub()) {
                    yield 1;
                }
                yield own.region() == p.region() ? 2 : -1;
            }
        };
    }
}
