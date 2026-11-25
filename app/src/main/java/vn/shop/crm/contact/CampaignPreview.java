package vn.shop.crm.contact;

import org.springframework.stereotype.Service;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.geo.GeoArea;
import vn.shop.crm.geo.Region;

import java.util.ArrayList;
import java.util.List;

/**
 * Xem trước một chiến dịch: đếm số khách khớp bộ lọc, chia theo khu vực, và từng điều kiện loại bớt bao nhiêu khách (đếm cộng dồn theo thứ tự).
 * CHỈ ĐẾM: không tạo lời mời, không ghi cơ sở dữ liệu, không chạm Zalo. Dùng đúng truy vấn của {@link ContactService} nên số khớp với số khách
 * mà chiến dịch thật sự sẽ lấy.
 */
@Service
public class CampaignPreview {

    /** Một dòng của bảng "từng điều kiện loại bớt": chữ, số khách còn lại sau điều kiện này, số khách điều kiện này loại. */
    public record Step(String label, long remaining, long removed) {
    }

    public record AreaCount(String label, long count) {
    }

    /**
     * @param matched    số khách khớp (đủ điều kiện mời, chưa từng được mời, đúng khu vực và bộ lọc)
     * @param willInvite số khách chiến dịch sẽ lấy: nhỏ hơn của {@code matched} và số khách tối đa
     * @param base       số khách đủ điều kiện mời (chưa lọc, trong khu vực đã chọn)
     * @param error      lỗi bằng chữ tiếng Việt (ví dụ chưa cài đơn đổi); khi có lỗi các số khác bằng 0
     */
    public record Result(long matched, long willInvite, long base, List<Step> steps, List<AreaCount> byArea, String error) {
        public boolean ok() {
            return error == null;
        }

        public static Result failed(String error) {
            return new Result(0, 0, 0, List.of(), List.of(), error);
        }
    }

    private final ContactService contacts;

    public CampaignPreview(ContactService contacts) {
        this.contacts = contacts;
    }

    public Result preview(String area, int maxCount, CustomerFilter filter) {
        try {
            String scope = area == null || area.isBlank() ? null : area.strip();
            if (scope != null && !GeoArea.UNKNOWN.equals(scope) && GeoArea.parse(scope).isEmpty()) {
                throw new ImportException("Khu vực không hợp lệ.");
            }
            long base = contacts.countEligibleNotInvited(scope, CustomerFilter.NONE);
            List<Step> steps = new ArrayList<>();
            long previous = base;
            CustomerFilter cumulative = CustomerFilter.NONE;
            for (CustomerFilter.Facet f : filter.facets()) {
                cumulative = CustomerFilter.and(cumulative, f.only());
                long now = contacts.countEligibleNotInvited(scope, cumulative);
                steps.add(new Step(f.label(), now, Math.max(0, previous - now)));
                previous = now;
            }
            long matched = steps.isEmpty() ? base : steps.get(steps.size() - 1).remaining();
            List<AreaCount> byArea = new ArrayList<>();
            if (scope == null) {
                for (Region r : Region.values()) {
                    byArea.add(new AreaCount(r.label(), contacts.countEligibleNotInvited(GeoArea.region(r).code(), filter)));
                }
                byArea.add(new AreaCount("Chưa rõ khu vực", contacts.countEligibleNotInvited(GeoArea.UNKNOWN, filter)));
            }
            return new Result(matched, Math.min(matched, Math.max(0, maxCount)), base, List.copyOf(steps), List.copyOf(byArea), null);
        } catch (ImportException e) {
            return Result.failed(e.getMessage());
        }
    }
}
