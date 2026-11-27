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
    private final vn.shop.crm.zalo.ZaloQueueView queueView;
    private final vn.shop.crm.zalo.ZaloCampaignIntake intake;
    private final vn.shop.crm.zalo.CampaignPolicy policy;
    private final CampaignSteps steps;
    private final vn.shop.crm.zalo.CampaignAutoStop autoStop;
    private final vn.shop.crm.zalo.CampaignGroupTarget groupTarget;
    private final vn.shop.crm.inbox.ComebackService comebacks;
    private final vn.shop.crm.zalo.ManualAttention attention;
    private final CampaignArchive archive;
    private final vn.shop.crm.zalo.CampaignFriendReconciliation friendReconciliation;
    private final vn.shop.crm.zalo.CampaignReconciliationView reconciliationView;

    private final CampaignFilterForm filterForm;

    public CampaignController(CampaignFilterForm filterForm, InviteService invites, ContactService contacts, AppSettings settings,
                              vn.shop.crm.zalo.ZaloInviteSender autoSender, vn.shop.crm.zalo.ZaloMessageService messages,
                              vn.shop.crm.zalo.ZaloCampaignIntake intake, vn.shop.crm.zalo.CampaignPolicy policy,
                              CampaignSteps steps, vn.shop.crm.zalo.CampaignAutoStop autoStop,
                              vn.shop.crm.zalo.CampaignGroupTarget groupTarget, vn.shop.crm.inbox.ComebackService comebacks,
                              vn.shop.crm.zalo.ZaloQueueView queueView, CampaignArchive archive, vn.shop.crm.zalo.ManualAttention attention,
                              vn.shop.crm.zalo.CampaignFriendReconciliation friendReconciliation,
                              vn.shop.crm.zalo.CampaignReconciliationView reconciliationView) {
        this.friendReconciliation = friendReconciliation;
        this.reconciliationView = reconciliationView;
        this.filterForm = filterForm;
        this.attention = attention;
        this.archive = archive;
        this.comebacks = comebacks;
        this.groupTarget = groupTarget;
        this.autoStop = autoStop;
        this.steps = steps;
        this.intake = intake;
        this.policy = policy;
        this.invites = invites;
        this.contacts = contacts;
        this.settings = settings;
        this.autoSender = autoSender;
        this.messages = messages;
        this.queueView = queueView;
    }

    @GetMapping("/campaigns")
    public String list(@RequestParam(defaultValue = "ds") String tab, @RequestParam(defaultValue = "false") boolean luutru, Model model) {
        // "moi" = tab tạo chiến dịch; mọi giá trị khác về danh sách (tab mặc định).
        model.addAttribute("tab", "moi".equals(tab) ? "moi" : "ds");
        var all = invites.campaigns();
        model.addAttribute("campaigns", luutru ? all : all.stream().filter(c -> !c.archived()).toList());
        model.addAttribute("showArchived", luutru);
        model.addAttribute("archivedCount", all.stream().filter(InviteService.Campaign::archived).count());
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
        filterForm.addOptions(model);
        return "campaigns";
    }

    /** Số khách đã duyệt, chưa từng được mời, ở một miền: để chọn khu vực khi tạo chiến dịch. */
    public record RegionAvailable(String code, String label, long count) {
    }

    @PostMapping("/campaigns")
    public String create(@RequestParam String name, @RequestParam(required = false) String template,
                         @RequestParam(defaultValue = "14") int expiresDays, @RequestParam int count,
                         @RequestParam(required = false) String area,
                         @RequestParam org.springframework.util.MultiValueMap<String, String> all, RedirectAttributes redirect) {
        long id = invites.createCampaign(name, template, expiresDays, count, area, filterForm.parse(all));
        redirect.addFlashAttribute("flash", "Đã tạo chiến dịch. Chưa có tin nào được gửi: hãy xem lại lịch và số lần gửi, "
                + "rồi duyệt cho hệ thống tự gửi ở tab \"Tổng quan và lời mời\".");
        return "redirect:/campaigns/" + id;
    }

    /** Cách nhận biết đơn đổi (chữ nằm trong mã đơn, tên hàng hoặc trạng thái gốc); để trống cả ba là tắt. */
    @PostMapping("/campaigns/exchange-markers")
    public String saveExchangeMarkers(@RequestParam(required = false) String orderRef, @RequestParam(required = false) String goods,
                                      @RequestParam(required = false) String status, RedirectAttributes redirect) {
        filterForm.saveMarkers(orderRef, goods, status, ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã lưu cách nhận biết đơn đổi.");
        return "redirect:/campaigns?tab=moi";
    }

    /** Lưu bộ lọc đang điền ở form thành mẫu (chỉ bộ lọc và thứ tự lấy khách, không khách, không nội dung tin). */
    @PostMapping("/campaigns/templates")
    public String saveFilterTemplate(@RequestParam(required = false) String templateName,
                                     @RequestParam org.springframework.util.MultiValueMap<String, String> all, RedirectAttributes redirect) {
        filterForm.saveTemplate(templateName, all, ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã lưu mẫu. Mẫu nằm ở mục \"Bắt đầu từ mẫu\" của form tạo chiến dịch.");
        return "redirect:/campaigns?tab=moi";
    }

    @PostMapping("/campaigns/templates/{id}/delete")
    public String deleteFilterTemplate(@PathVariable long id, RedirectAttributes redirect) {
        filterForm.deleteTemplate(id);
        redirect.addFlashAttribute("flash", "Đã xóa mẫu.");
        return "redirect:/campaigns?tab=moi";
    }

    /** Chủ shop soạn mẫu tin mặc định trên trang quản trị (để trống = dùng lại mẫu có sẵn). */
    @PostMapping("/campaigns/default-template")
    public String saveTemplate(@RequestParam(required = false) String template, RedirectAttributes redirect) {
        invites.saveDefaultTemplate(template);
        redirect.addFlashAttribute("flash", "Đã lưu nội dung tin mặc định. Chiến dịch mới sẽ dùng nội dung này.");
        return "redirect:/campaigns?tab=moi";
    }

    /** Các tab của trang chi tiết chiến dịch (D21 PR 8). Mỗi cài đặt nằm ở đúng một tab; mặc định là tab đầu. */
    public record Tab(String key, String label) {
    }

    static final java.util.List<Tab> TABS = java.util.List.of(
            new Tab("tong-quan", "Tổng quan và lời mời"), new Tab("lich", "Lịch và điều kiện gửi"),
            new Tab("tin", "Chuỗi tin"), new Tab("nhom", "Nhóm Zalo và khách quay lại"), new Tab("can-xu-ly", "Cần xử lý tay"),
            new Tab("doi-chieu", "Đối chiếu Zalo"));

    /** Tab được chọn: tham số {@code tab}, hoặc tab vừa lưu (gửi qua thuộc tính flash để địa chỉ chuyển hướng không đổi); lạ thì về tab đầu. */
    private static String pickTab(String param, Model model, java.util.List<Tab> tabs) {
        Object flash = model.asMap().get("tab");
        for (String candidate : new String[]{param, flash instanceof String f ? f : null}) {
            if (candidate != null && tabs.stream().anyMatch(t -> t.key().equals(candidate))) {
                return candidate;
            }
        }
        return tabs.get(0).key();
    }

    @GetMapping("/campaigns/{id}")
    public String detail(@PathVariable long id, @RequestParam(required = false) String tab,
                         @RequestParam(defaultValue = "0") int reconciliationPage,
                         jakarta.servlet.http.HttpServletRequest request, Model model) {
        boolean canReconcile = request.isUserInRole("OWNER");
        var visibleTabs = TABS.stream().filter(t -> canReconcile || !"doi-chieu".equals(t.key())).toList();
        model.addAttribute("tabs", visibleTabs);
        model.addAttribute("tab", pickTab(tab, model, visibleTabs));
        var campaign = invites.campaign(id).orElseThrow(() -> new ImportException("Không tìm thấy chiến dịch."));
        model.addAttribute("campaign", campaign);
        model.addAttribute("attentionCount", attention.count(id));
        if ("doi-chieu".equals(model.asMap().get("tab"))) {
            model.addAttribute("reconcileAccounts", friendReconciliation.accounts(id));
            model.addAttribute("reconcileRows", reconciliationView.rows(id, reconciliationPage));
            return "campaign-detail";
        }
        var filter = invites.campaignFilter(id);
        model.addAttribute("filterLines", filter.describe());
        model.addAttribute("filterPick", filter.pickLabel());
        model.addAttribute("rows", invites.invitations(id, campaign.template()));
        model.addAttribute("paused", settings.paused());
        model.addAttribute("joinGroups", invites.joinableGroups());
        model.addAttribute("autoApproved", autoSender.approved(id));
        model.addAttribute("autoSwitch", messages.enabled());
        model.addAttribute("queue", queueView.preview());
        model.addAttribute("intake", intake.status(id));
        var raw = policy.load(id);
        model.addAttribute("sched", raw);
        model.addAttribute("schedEffective", policy.effective(raw));
        model.addAttribute("plan", steps.plan(id));
        model.addAttribute("progress", steps.progress(id));
        model.addAttribute("maxTouches", CampaignSteps.MAX_TOUCHES);
        model.addAttribute("minGapDays", CampaignSteps.MIN_GAP_DAYS);
        model.addAttribute("stopFooter", CampaignSteps.STOP_FOOTER);
        model.addAttribute("multiTemplate", InviteService.MULTI_TEMPLATE);
        model.addAttribute("attention", "can-xu-ly".equals(model.asMap().get("tab")) ? attention.rows(id) : java.util.List.of());
        model.addAttribute("friendAsk", steps.friendAskMessage(id));
        model.addAttribute("friendAskDefault", vn.shop.crm.zalo.ZaloFriendAskSender.DEFAULT_TEXT);
        model.addAttribute("friendAskMax", vn.shop.crm.zalo.ZaloFriendAskSender.MAX_TEXT);
        model.addAttribute("extra", policy.loadExtra(id));
        model.addAttribute("groupTarget", groupTarget.load(id));
        model.addAttribute("comebackOwn", comebacks.campaignMode(id));
        model.addAttribute("comebackSystem", comebacks.mode());
        model.addAttribute("comebackModes", vn.shop.crm.inbox.ComebackService.Mode.values());
        model.addAttribute("sysStopPct", vn.shop.crm.zalo.CampaignAutoStop.SYSTEM_PCT);
        model.addAttribute("minStopSent", vn.shop.crm.zalo.CampaignAutoStop.MIN_SENT);
        model.addAttribute("stoppedReason", autoStop.stoppedReason(id).orElse(null));
        model.addAttribute("schedBlock", policy.blocker(id).map(vn.shop.crm.zalo.CampaignPolicy.Block::label).orElse(null));
        return "campaign-detail";
    }

    @GetMapping("/campaigns/{id}/reconcile/{account}")
    @org.springframework.web.bind.annotation.ResponseBody
    public org.springframework.http.ResponseEntity<vn.shop.crm.zalo.CampaignFriendReconciliation.Status> friendReadStatus(
            @PathVariable long id, @PathVariable long account) {
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(friendReconciliation.status(id, account));
    }

    @PostMapping("/campaigns/{id}/reconcile/{account}")
    public String readFriends(@PathVariable long id, @PathVariable long account) {
        friendReconciliation.start(id, account);
        return "redirect:/campaigns/" + id + "?tab=doi-chieu";
    }

    @PostMapping("/campaigns/{id}/reconcile/{account}/cancel")
    public String cancelFriendRead(@PathVariable long id, @PathVariable long account, @RequestParam long job,
                                   @RequestParam(defaultValue = "false") boolean confirmed) {
        if (!confirmed) {
            throw new ImportException("Hãy xác nhận hủy phần lời mời chưa đọc.");
        }
        friendReconciliation.cancel(id, account, job);
        return "redirect:/campaigns/" + id + "?tab=doi-chieu";
    }

    /**
     * Lịch và nhịp RIÊNG của chiến dịch (D21 PR 4): chỉ làm chặt hơn trần hệ thống, để trống là theo hệ thống.
     * Nút "Theo hệ thống" (reset) xóa hết giá trị riêng.
     */
    @PostMapping("/campaigns/{id}/schedule")
    public String saveSchedule(@PathVariable long id, @RequestParam(required = false) String startOn,
                               @RequestParam(required = false) String endOn, @RequestParam(required = false) String windowFrom,
                               @RequestParam(required = false) String windowTo, @RequestParam(required = false) String dailyCap,
                               @RequestParam(required = false) String gapSeconds, @RequestParam(defaultValue = "false") boolean reset,
                               RedirectAttributes redirect) {
        invites.campaign(id).orElseThrow(() -> new ImportException("Không tìm thấy chiến dịch."));
        var raw = reset ? vn.shop.crm.zalo.CampaignPolicy.Raw.NONE
                : new vn.shop.crm.zalo.CampaignPolicy.Raw(date(startOn, "Ngày bắt đầu"), date(endOn, "Ngày kết thúc"),
                time(windowFrom, "Giờ bắt đầu"), time(windowTo, "Giờ kết thúc"), number(dailyCap, "Hạn mức ngày"),
                number(gapSeconds, "Khoảng nghỉ"));
        policy.save(id, raw, ContactService.actor());
        redirect.addFlashAttribute("tab", "lich");
        redirect.addFlashAttribute("flash", reset
                ? "Đã bỏ lịch và nhịp riêng: chiến dịch theo cài đặt chung của hệ thống."
                : "Đã lưu lịch và nhịp riêng. Các giới hạn chung của hệ thống vẫn áp dụng thêm.");
        return "redirect:/campaigns/" + id;
    }

    /**
     * Điều kiện gửi riêng thêm (D21 PR 4): ngày trong tuần được gửi, hoãn khi mưa lớn, ngưỡng tự dừng khi khách từ chối nhiều.
     * Chỉ làm chặt hơn hệ thống. Nút "Theo hệ thống" (reset) xóa hết.
     */
    @PostMapping("/campaigns/{id}/rules")
    public String saveRules(@PathVariable long id, @RequestParam(required = false) java.util.List<Integer> days,
                            @RequestParam(defaultValue = "false") boolean holdOnRain, @RequestParam(required = false) String stopPct,
                            @RequestParam(defaultValue = "false") boolean reset, RedirectAttributes redirect) {
        invites.campaign(id).orElseThrow(() -> new ImportException("Không tìm thấy chiến dịch."));
        var extra = reset ? vn.shop.crm.zalo.CampaignPolicy.Extra.NONE
                : new vn.shop.crm.zalo.CampaignPolicy.Extra(vn.shop.crm.zalo.CampaignPolicy.Extra.maskOf(
                        days == null ? java.util.List.of() : days), holdOnRain, number(stopPct, "Ngưỡng tự dừng"));
        policy.saveExtra(id, extra, ContactService.actor());
        redirect.addFlashAttribute("tab", "lich");
        redirect.addFlashAttribute("flash", reset
                ? "Đã bỏ điều kiện gửi riêng: chiến dịch theo cài đặt chung của hệ thống."
                : "Đã lưu điều kiện gửi riêng. Các giới hạn chung của hệ thống vẫn áp dụng thêm.");
        return "redirect:/campaigns/" + id;
    }

    /**
     * Nhóm Zalo đích riêng của chiến dịch (D21 PR 6): chỉ làm chặt hơn hệ thống (không cho vào nhóm tự tạo).
     * Nút "Theo hệ thống" (reset) xóa hết.
     */
    @PostMapping("/campaigns/{id}/group-target")
    public String saveGroupTarget(@PathVariable long id, @RequestParam(defaultValue = "false") boolean allowCreation,
                                  @RequestParam(defaultValue = "false") boolean reset, RedirectAttributes redirect) {
        invites.campaign(id).orElseThrow(() -> new ImportException("Không tìm thấy chiến dịch."));
        var target = reset ? vn.shop.crm.zalo.CampaignGroupTarget.Target.SYSTEM
                : new vn.shop.crm.zalo.CampaignGroupTarget.Target(allowCreation);
        groupTarget.save(id, target, ContactService.actor());
        redirect.addFlashAttribute("tab", "nhom");
        redirect.addFlashAttribute("flash", reset
                ? "Đã bỏ cài đặt nhóm riêng: chiến dịch theo cài đặt chung của hệ thống."
                : "Đã lưu cài đặt nhóm Zalo của chiến dịch.");
        return "redirect:/campaigns/" + id;
    }

    /**
     * Khách từng từ chối nhưng tự nhắn lại muốn mua (D21 PR 7): nấc RIÊNG của chiến dịch. Chỉ làm chặt hơn nấc chung của hệ thống
     * (nấc có hiệu lực luôn là nấc thấp hơn); để trống là theo hệ thống.
     */
    @PostMapping("/campaigns/{id}/comeback")
    public String saveComeback(@PathVariable long id, @RequestParam(required = false) String mode, RedirectAttributes redirect) {
        invites.campaign(id).orElseThrow(() -> new ImportException("Không tìm thấy chiến dịch."));
        comebacks.saveCampaignMode(id, vn.shop.crm.inbox.ComebackService.Mode.ofCampaign(mode), ContactService.actor());
        messages.audit("KHACH_QUAY_LAI_CHIEN_DICH", id, ContactService.actor());
        redirect.addFlashAttribute("tab", "nhom");
        redirect.addFlashAttribute("flash", "Đã lưu cách xử lý khách từng từ chối nhắn lại của chiến dịch. Nấc chung của hệ thống vẫn áp dụng thêm (lấy nấc thấp hơn).");
        return "redirect:/campaigns/" + id;
    }

    /** Số lần gửi mời (1 đến 3) và nội dung, khoảng cách các tin nhắc. PR 5a: chỉ lưu và kiểm, chưa có gì gửi tin nhắc. */
    @PostMapping("/campaigns/{id}/touches")
    public String saveTouches(@PathVariable long id, @RequestParam(defaultValue = "1") int touches,
                              @RequestParam(required = false) String text2, @RequestParam(required = false) String gap2,
                              @RequestParam(required = false) String text3, @RequestParam(required = false) String gap3,
                              RedirectAttributes redirect) {
        if (touches < 1 || touches > CampaignSteps.MAX_TOUCHES) {
            throw new ImportException("Số lần gửi phải từ 1 đến " + CampaignSteps.MAX_TOUCHES + ".");
        }
        var list = new java.util.ArrayList<CampaignSteps.Step>();
        if (touches >= 2) {
            list.add(new CampaignSteps.Step(2, text2, java.util.Objects.requireNonNullElse(number(gap2, "Khoảng cách lần 2"), 0)));
        }
        if (touches >= 3) {
            list.add(new CampaignSteps.Step(3, text3, java.util.Objects.requireNonNullElse(number(gap3, "Khoảng cách lần 3"), 0)));
        }
        steps.saveFollowups(id, list, ContactService.actor());
        redirect.addFlashAttribute("tab", "tin");
        redirect.addFlashAttribute("flash", touches == 1
                ? "Chiến dịch gửi mời 1 lần."
                : "Đã lưu: chiến dịch gửi mời " + touches + " lần. Lưu ý: hệ thống CHƯA tự gửi tin nhắc (phần gửi làm ở bước sau); hiện chỉ lưu cấu hình.");
        return "redirect:/campaigns/" + id;
    }

    /** Sửa nội dung Tin 1 (chỉ khi chưa gửi cho khách nào của chiến dịch). */
    @PostMapping("/campaigns/{id}/first-message")
    public String saveFirstMessage(@PathVariable long id, @RequestParam String template, RedirectAttributes redirect) {
        steps.saveFirstMessage(id, template, ContactService.actor());
        redirect.addFlashAttribute("tab", "tin");
        redirect.addFlashAttribute("flash", "Đã lưu nội dung Tin 1 của chiến dịch.");
        return "redirect:/campaigns/" + id;
    }

    /** Tab "Cần xử lý tay": gửi lại lời mời kết bạn MỘT lần cho khách "chưa rõ đã gửi chưa" (trả về hàng chờ, không gọi Zalo ngay). */
    @PostMapping("/campaigns/{id}/attention/{invitationId}/resend-friend")
    public String resendFriend(@PathVariable long id, @PathVariable long invitationId, RedirectAttributes redirect) {
        attention.resendFriend(id, invitationId, ContactService.actor());
        redirect.addFlashAttribute("tab", "can-xu-ly");
        redirect.addFlashAttribute("flash", "Đã đưa khách về hàng chờ kết bạn. Hệ thống gửi lại một lần ở nhịp kế tiếp, theo hạn mức, khung giờ và khoảng nghỉ.");
        return "redirect:/campaigns/" + id + "?tab=can-xu-ly";
    }

    /** Tab "Cần xử lý tay": đánh dấu đã xử lý (ẩn khỏi tab, không đổi gì khác). */
    @PostMapping("/campaigns/{id}/attention/{invitationId}/done")
    public String attentionDone(@PathVariable long id, @PathVariable long invitationId, RedirectAttributes redirect) {
        attention.markDone(id, invitationId, ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã đánh dấu đã xử lý.");
        return "redirect:/campaigns/" + id + "?tab=can-xu-ly";
    }

    /** Sửa nội dung tin nhờ khách kết bạn lại (gửi khi Zalo báo danh sách lời mời đầy). */
    @PostMapping("/campaigns/{id}/friend-ask-message")
    public String saveFriendAskMessage(@PathVariable long id, @RequestParam String template, RedirectAttributes redirect) {
        steps.saveFriendAskMessage(id, template, ContactService.actor());
        redirect.addFlashAttribute("tab", "tin");
        redirect.addFlashAttribute("flash", "Đã lưu nội dung tin nhờ khách kết bạn lại.");
        return "redirect:/campaigns/" + id;
    }

    private static java.time.LocalDate date(String v, String label) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(v.strip());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ImportException(label + " không đúng (cần dạng năm-tháng-ngày).");
        }
    }

    private static java.time.LocalTime time(String v, String label) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalTime.parse(v.strip());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ImportException(label + " không đúng (cần dạng giờ:phút).");
        }
    }

    private static Integer number(String v, String label) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(v.strip());
        } catch (NumberFormatException e) {
            throw new ImportException(label + " phải là một số nguyên.");
        }
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

    /** Lưu trữ chiến dịch cũ: bỏ duyệt tự gửi, tắt tự nhận khách mới, AI không còn coi là đang chạy. Có thể hủy luôn các lời mời chưa gửi. */
    @PostMapping("/campaigns/{id}/archive")
    public String archiveCampaign(@PathVariable long id, @RequestParam(defaultValue = "false") boolean cancelUnsent,
                                  RedirectAttributes redirect) {
        int n = archive.archive(id, cancelUnsent, ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã lưu trữ chiến dịch: hệ thống không tự gửi cho chiến dịch này nữa và AI không còn coi nó là đang chạy. "
                + "Tin đã gửi và kết quả giữ nguyên."
                + (n > 0 ? " Đã hủy " + n + " lời mời chưa gửi, các khách này quay về danh sách chưa được mời." : ""));
        return "redirect:/campaigns/" + id;
    }

    @PostMapping("/campaigns/{id}/restore")
    public String restoreCampaign(@PathVariable long id, RedirectAttributes redirect) {
        archive.restore(id, ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã khôi phục chiến dịch. Chiến dịch chưa được duyệt tự gửi: bạn duyệt lại ở tab \"Tổng quan và lời mời\" nếu muốn gửi tiếp.");
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
