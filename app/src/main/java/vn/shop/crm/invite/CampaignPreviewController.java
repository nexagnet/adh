package vn.shop.crm.invite;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.CampaignPreview;

/**
 * Xem trước chiến dịch (PR 2): trả về một đoạn trang nhỏ để chèn vào form tạo chiến dịch. CHỈ ĐẾM, không tạo gì, không chạm Zalo.
 * Dùng chính các ô của form tạo chiến dịch nên không thể lệch với việc tạo thật.
 */
@Controller
public class CampaignPreviewController {

    private final CampaignPreview preview;
    private final CampaignFilterForm filterForm;
    private final InviteService invites;

    public CampaignPreviewController(CampaignPreview preview, CampaignFilterForm filterForm, InviteService invites) {
        this.preview = preview;
        this.filterForm = filterForm;
        this.invites = invites;
    }

    @PostMapping("/campaigns/preview")
    public String preview(@RequestParam(required = false) String area, @RequestParam(defaultValue = "30") int count,
                          @RequestParam MultiValueMap<String, String> all, Model model) {
        CampaignPreview.Result result;
        try {
            result = preview.preview(area, Math.min(Math.max(count, 0), invites.maxCampaign()), filterForm.parse(all));
        } catch (ImportException e) {
            result = CampaignPreview.Result.failed(e.getMessage());
        }
        model.addAttribute("preview", result);
        return "campaign-preview :: preview";
    }
}
