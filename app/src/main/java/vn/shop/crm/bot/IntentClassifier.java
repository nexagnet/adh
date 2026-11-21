package vn.shop.crm.bot;

import java.util.List;

/**
 * Nhận diện ý định bằng từ khóa tiếng Việt. Cố ý nghiêng về "chuyển người thật": nhận nhầm thì mẹ chỉ mất công xem,
 * bỏ sót thì khách bị bỏ rơi hoặc bot trả lời sai.
 */
final class IntentClassifier {

    static final List<String> HUMAN = List.of("nguoi that", "goi dien", "gap truc tiep", "noi chuyen voi chu",
            "cho gap chu", "chu shop", "chu cua hang", "tong dai", "so dien thoai cua shop", "gap nguoi");
    static final List<String> COMPLAINT = List.of("khieu nai", "hang loi", "bi loi", "bi hong", "hong", "loi",
            "doi tra", "tra hang", "tra lai", "hoan tien", "khong hai long", "that vong", "lua dao", "te qua",
            "giao cham", "chua nhan duoc", "thieu hang", "sai hang", "bao hanh khong duoc");
    /** Ý định mua rõ ràng: luôn chuyển người thật, kể cả khi có câu FAQ khớp. */
    static final List<String> STRONG_ORDER = List.of("dat hang", "dat them", "mua them", "mua lai", "chot don",
            "lay them", "muon mua", "can mua", "dat mua", "gui cho toi", "gui them");
    /** Hỏi giá/còn hàng: nếu FAQ của cửa hàng có câu trả lời thì dùng, nếu không thì chuyển người thật. */
    static final List<String> WEAK_ORDER = List.of("mua", "con hang", "gia bao nhieu", "bao nhieu tien", "ship",
            "gia the nao", "gia sao");
    static final List<String> DIRECTED = List.of("shop", "cua hang", "tro ly", "cho hoi", "xin hoi", "hoi ve",
            "huong dan", "cach dung", "lam sao", "the nao", "nhu nao", "bao lau", "duoc khong", "co the");

    private IntentClassifier() {
    }

    static boolean anyMatch(String normalized, List<String> phrases) {
        return phrases.stream().anyMatch(p -> TextNorm.containsPhrase(normalized, p));
    }

    static boolean strongOrder(String text) {
        return anyMatch(TextNorm.words(text), STRONG_ORDER);
    }

    static boolean weakOrder(String text) {
        return anyMatch(TextNorm.words(text), WEAK_ORDER);
    }

    static boolean directed(String text) {
        return (text != null && text.contains("?")) || anyMatch(TextNorm.words(text), DIRECTED);
    }

    static Intent classify(String text) {
        String n = TextNorm.words(text);
        if (anyMatch(n, HUMAN)) {
            return Intent.WANTS_HUMAN;
        }
        if (anyMatch(n, COMPLAINT)) {
            return Intent.COMPLAINT;
        }
        if (anyMatch(n, STRONG_ORDER) || anyMatch(n, WEAK_ORDER)) {
            return Intent.ORDER;
        }
        return directed(text) ? Intent.QUESTION : Intent.OTHER;
    }
}
