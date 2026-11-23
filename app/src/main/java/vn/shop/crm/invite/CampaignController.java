package vn.shop.crm.invite;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;

/** Chiến dịch mời: tạo lô nhỏ, xem lời mời và lượt bấm, tạm dừng tất cả. Mốc 1C chưa gửi tin cho ai. */
@Controller
public class CampaignController {

    private final InviteService invites;
    private final ContactService contacts;
    private final AppSettings settings;
    private final vn.shop.crm.zalo.ZaloInviteSender autoSender;
    private final vn.shop.crm.zalo.ZaloMessageService messages;
    private final vn.shop.crm.zalo.ZaloCampaignIntake intake;

    public CampaignController(InviteService invites, ContactService contacts, AppSettings settings,
                              vn.shop.crm.zalo.ZaloInviteSender autoSender, vn.shop.crm.zalo.ZaloMessageService messages,
                              vn.shop.crm.zalo.ZaloCampaignIntake intake) {
        this.intake = intake;
        this.invites = invites;
        this.contacts = contacts;
        this.settings = settings;
        this.autoSender = autoSender;
        this.messages = messages;
    }

    @GetMapping("/campaigns")
    public String list(Model model) {
        model.addAttribute("campaigns", invites.campaigns());
        model.addAttribute("stats", invites.stats());
        model.addAttribute("contact", contacts.stats());
        model.addAttribute("paused", settings.paused());
        model.addAttribute("pausedReason", settings.pausedReason());
        model.addAttribute("maxBatch", invites.maxBatch());
        model.addAttribute("maxCampaign", invites.maxCampaign());
        model.addAttribute("areaGroups", vn.shop.crm.customers.CustomerController.areaGroups());
        model.addAttribute("regionCounts", java.util.Arrays.stream(vn.shop.crm.geo.Region.values())
                .map(r -> new RegionAvailable(vn.shop.crm.geo.GeoArea.region(r).code(), r.label(),
                        contacts.countEligibleNotInvited(vn.shop.crm.geo.GeoArea.region(r).code()))).toList());
        model.addAttribute("unknownAvailable", contacts.countEligibleNotInvited(vn.shop.crm.geo.GeoArea.UNKNOWN));
        model.addAttribute("defaultTemplate", invites.defaultTemplate());
        model.addAttribute("groupLinkTemplate", invites.groupLinkTemplate());
        return "campaigns";
    }

    /** Số khách đã duyệt, chưa từng được mời, ở một miền: để chọn khu vực khi tạo chiến dịch. */
    public record RegionAvailable(String code, String label, long count) {
    }

    @PostMapping("/campaigns")
    public String create(@RequestParam String name, @RequestParam(required = false) String template,
                         @RequestParam(defaultValue = "14") int expiresDays, @RequestParam int count,
                         @RequestParam(required = false) String area, RedirectAttributes redirect) {
        long id = invites.createCampaign(name, template, expiresDays, count, area);
        redirect.addFlashAttribute("flash", "Đã tạo chiến dịch. Chưa có tin nào được gửi: mỗi ngày mở mục "
                + "\"Lô nhắn hôm nay\" để nhắn từng người theo hạn mức.");
        return "redirect:/campaigns/" + id;
    }

    /** Chủ shop soạn mẫu tin mặc định trên trang quản trị (để trống = dùng lại mẫu có sẵn). */
    @PostMapping("/campaigns/default-template")
    public String saveTemplate(@RequestParam(required = false) String template, RedirectAttributes redirect) {
        invites.saveDefaultTemplate(template);
        redirect.addFlashAttribute("flash", "Đã lưu nội dung tin mặc định. Chiến dịch mới sẽ dùng nội dung này.");
        return "redirect:/campaigns";
    }

    /** Chủ shop soạn mẫu tin gửi link nhóm cho khách đã kết bạn (để trống = dùng lại mẫu có sẵn). */
    @PostMapping("/campaigns/group-link-template")
    public String saveGroupLinkTemplate(@RequestParam(required = false) String template, RedirectAttributes redirect) {
        invites.saveGroupLinkTemplate(template);
        redirect.addFlashAttribute("flash", "Đã lưu nội dung tin gửi link nhóm.");
        return "redirect:/campaigns";
    }

    @GetMapping("/campaigns/{id}")
    public String detail(@PathVariable long id, Model model) {
        var campaign = invites.campaign(id).orElseThrow(() -> new ImportException("Không tìm thấy chiến dịch."));
        model.addAttribute("campaign", campaign);
        model.addAttribute("rows", invites.invitations(id, campaign.template()));
        model.addAttribute("paused", settings.paused());
        model.addAttribute("joinGroups", invites.joinableGroups());
        model.addAttribute("autoApproved", autoSender.approved(id));
        model.addAttribute("autoSwitch", messages.enabled());
        model.addAttribute("intake", intake.status(id));
        return "campaign-detail";
    }

    /** Đánh dấu tay các bước của đường nhắn bằng tài khoản Zalo phụ: đã kết bạn, đã vào nhóm, khách từ chối. */
    @PostMapping("/campaigns/{id}/invitations/{invitationId}/friended")
    public String friended(@PathVariable long id, @PathVariable long invitationId,
                           @RequestParam(required = false) String back, RedirectAttributes redirect) {
        var result = invites.markFriended(invitationId);
        redirect.addFlashAttribute("flash", result.reserved()
                ? "Đã ghi: khách đồng ý kết bạn. Nên thêm khách vào nhóm \"" + result.groupName() + "\" (đã giữ chỗ "
                + invites.reserveHours() + " giờ), hoặc gửi link nhóm cho khách tự bấm."
                : "Đã ghi: khách đồng ý kết bạn. Chưa có nhóm Zalo hợp khu vực của khách, đã ghi \"chờ nhóm\". "
                + "Mở nhóm ở mục Nhóm Zalo thì khách này hiện thành việc cần thêm vào nhóm.");
        return redirectTo(id, back);
    }

    /** Một lần bấm cho trường hợp đã thêm khách vào nhóm ngay trong lúc làm (R5). */
    @PostMapping("/campaigns/{id}/invitations/{invitationId}/friended-joined")
    public String friendedJoined(@PathVariable long id, @PathVariable long invitationId,
                                 @RequestParam(required = false) Long groupId,
                                 @RequestParam(required = false) String back, RedirectAttributes redirect) {
        invites.markFriendedAndJoined(invitationId, groupId);
        redirect.addFlashAttribute("flash", "Đã ghi: khách đã kết bạn và đã được thêm vào nhóm. Số khách thật của nhóm tăng thêm 1.");
        return redirectTo(id, back);
    }

    @PostMapping("/campaigns/{id}/invitations/{invitationId}/joined")
    public String joined(@PathVariable long id, @PathVariable long invitationId,
                         @RequestParam(required = false) Long groupId,
                         @RequestParam(required = false) String back, RedirectAttributes redirect) {
        invites.markJoined(invitationId, groupId);
        redirect.addFlashAttribute("flash", "Đã ghi: khách đã được thêm vào nhóm. Số khách thật của nhóm tăng thêm 1.");
        return redirectTo(id, back);
    }

    /** Khách đã ra khỏi nhóm: số khách thật của nhóm giảm 1 (R8). */
    @PostMapping("/campaigns/{id}/invitations/{invitationId}/left")
    public String left(@PathVariable long id, @PathVariable long invitationId,
                       @RequestParam(required = false) String back, RedirectAttributes redirect) {
        invites.markLeft(invitationId);
        redirect.addFlashAttribute("flash", "Đã ghi: khách đã rời nhóm. Số khách thật của nhóm giảm 1 (nếu khách đó đã được tính).");
        return redirectTo(id, back);
    }

    /** Người gửi đã dán tin kèm link nhóm cho khách đã kết bạn (khách tự bấm). Hệ thống không gửi gì. */
    @PostMapping("/campaigns/{id}/invitations/{invitationId}/group-link-sent")
    public String groupLinkSent(@PathVariable long id, @PathVariable long invitationId, @RequestParam long groupId,
                                @RequestParam(required = false) String back, RedirectAttributes redirect) {
        invites.markGroupLinkSent(invitationId, groupId);
        redirect.addFlashAttribute("flash", "Đã ghi: đã gửi link nhóm cho khách. Khi thấy khách vào nhóm, bấm \"Đã vào nhóm\".");
        return redirectTo(id, back);
    }

    /** Từ trang "Chờ vào nhóm" thì quay lại trang đó, không đưa mẹ sang trang chiến dịch. */
    private static String redirectTo(long campaignId, String back) {
        return "friends".equals(back) ? "redirect:/friends" : "redirect:/campaigns/" + campaignId;
    }

    @PostMapping("/campaigns/{id}/invitations/{invitationId}/declined")
    public String declined(@PathVariable long id, @PathVariable long invitationId,
                           @RequestParam(required = false) String back, RedirectAttributes redirect) {
        invites.markDeclined(invitationId);
        redirect.addFlashAttribute("flash", "Đã ghi: khách từ chối. Số này vào danh sách từ chối vĩnh viễn, không nhắn lại. "
                + "Nếu khách đang ở trong nhóm, nhớ mời khách ra khỏi nhóm trên Zalo (số khách của nhóm đã giảm 1).");
        return redirectTo(id, back);
    }

    /** Duyệt chiến dịch cho hệ thống tự gửi Tin 1 qua bộ nối Zalo (D18). Chưa duyệt thì không tự gửi gì. */
    @PostMapping("/campaigns/{id}/auto-send/approve")
    public String approveAutoSend(@PathVariable long id, RedirectAttributes redirect) {
        autoSender.approveCampaign(id, ContactService.actor());
        redirect.addFlashAttribute("flash", messages.enabled()
                ? "Đã duyệt. Hệ thống sẽ tự gửi Tin 1 từng khách một theo hạn mức và khoảng nghỉ."
                : "Đã duyệt, nhưng công tắc \"Tự gửi tin\" ở trang Zalo tài khoản phụ đang TẮT nên chưa có tin nào được gửi.");
        return "redirect:/campaigns/" + id;
    }

    /** Bỏ duyệt: dừng tự gửi cho chiến dịch này ngay. */
    @PostMapping("/campaigns/{id}/auto-send/revoke")
    public String revokeAutoSend(@PathVariable long id, RedirectAttributes redirect) {
        autoSender.revokeCampaign(id, ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã bỏ duyệt: hệ thống không tự gửi cho chiến dịch này nữa. Tin đã gửi giữ nguyên.");
        return "redirect:/campaigns/" + id;
    }

    /** Bật "tự nhận khách mới": khách mới đủ tiêu chí tự vào chiến dịch, không duyệt từng người. Tắt mặc định. */
    @PostMapping("/campaigns/{id}/auto-intake/enable")
    public String enableIntake(@PathVariable long id, RedirectAttributes redirect) {
        intake.enable(id, ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã bật tự nhận khách mới: từ giờ khách mới nhập về, đủ tiêu chí, sẽ tự vào chiến dịch này và được gửi Tin 1 theo hạn mức.");
        return "redirect:/campaigns/" + id;
    }

    @PostMapping("/campaigns/{id}/auto-intake/disable")
    public String disableIntake(@PathVariable long id, RedirectAttributes redirect) {
        intake.disable(id, ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã tắt tự nhận khách mới. Khách đã vào chiến dịch vẫn giữ nguyên.");
        return "redirect:/campaigns/" + id;
    }

    @PostMapping("/campaigns/{id}/cancel-unsent")
    public String cancelUnsent(@PathVariable long id, RedirectAttributes redirect) {
        int n = invites.cancelUnsent(id);
        redirect.addFlashAttribute("flash", "Đã hủy " + n + " lời mời chưa gửi. Các khách này quay về danh sách "
                + "chưa được mời.");
        return "redirect:/campaigns/" + id;
    }

    @PostMapping("/campaigns/pause-all")
    public String pauseAll(@RequestParam(required = false) String reason, RedirectAttributes redirect) {
        settings.pauseAll(reason == null || reason.isBlank() ? "Tạm dừng thủ công" : reason.strip(),
                ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã tạm dừng TẤT CẢ chiến dịch.");
        return "redirect:/campaigns";
    }

    @PostMapping("/campaigns/resume-all")
    public String resumeAll(RedirectAttributes redirect) {
        settings.resumeAll(ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã bỏ tạm dừng.");
        return "redirect:/campaigns";
    }
}
