package vn.shop.crm.common;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Chuẩn hóa số điện thoại Việt Nam về dạng E.164 (+84...).
 * Không suy diễn số thiếu/sai: số không hợp lệ trả về rỗng để đưa vào danh sách lỗi.
 */
public final class PhoneNormalizer {

    private static final String REGION = "VN";
    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();
    /** Excel hay xuất số điện thoại dạng số thực: "912345678.0". */
    private static final Pattern TRAILING_ZERO_DECIMAL = Pattern.compile("^(\\d+)\\.0+$");

    private PhoneNormalizer() {
    }

    public static Optional<String> toE164(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = raw.strip();
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        var decimal = TRAILING_ZERO_DECIMAL.matcher(cleaned);
        if (decimal.matches()) {
            cleaned = decimal.group(1);
        }
        try {
            Phonenumber.PhoneNumber number = UTIL.parse(cleaned, REGION);
            if (!UTIL.isValidNumberForRegion(number, REGION)) {
                return Optional.empty();
            }
            return Optional.of(UTIL.format(number, PhoneNumberUtil.PhoneNumberFormat.E164));
        } catch (NumberParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Ô có nhiều số ("0912345678, 0987654321", hãng cho khách ghi 2 số): lấy số hợp lệ ĐẦU TIÊN.
     * Số còn lại không bị mất: dòng gốc vẫn nằm trong raw_payload của vận đơn.
     * Ô chỉ có một số thì hành xử đúng như {@link #toE164}.
     */
    public static Optional<String> firstValid(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (String part : raw.split("[,;/]")) {
            Optional<String> phone = toE164(part);
            if (phone.isPresent()) {
                return phone;
            }
        }
        return Optional.empty();
    }

    /** Dạng quốc gia để hiển thị cho bố mẹ, ví dụ 0912 345 678. */
    public static String toDisplay(String e164) {
        try {
            Phonenumber.PhoneNumber number = UTIL.parse(e164, REGION);
            return UTIL.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL);
        } catch (NumberParseException e) {
            return e164;
        }
    }
}
