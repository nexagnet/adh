package vn.shop.crm.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Băm SHA-256 của SĐT dạng E.164, dùng cho danh sách từ chối: chặn liên hệ/nhập lại mà không giữ SĐT.
 * Lưu ý: SĐT có không gian giá trị nhỏ nên bản băm KHÔNG phải là ẩn danh tuyệt đối với người cố tình dò; mục đích
 * là không để lộ SĐT khi nhìn cơ sở dữ liệu hay bản sao lưu một cách tình cờ.
 */
public final class PhoneHash {

    private PhoneHash() {
    }

    public static String of(String e164) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(e164.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 không có sẵn", e);
        }
    }

    /** 3 số cuối để người dùng nhận ra bản ghi trong nhật ký mà không lộ cả số. */
    public static String tail(String e164) {
        String digits = e164 == null ? "" : e164.replaceAll("\\D", "");
        return digits.length() <= 3 ? digits : "***" + digits.substring(digits.length() - 3);
    }
}
