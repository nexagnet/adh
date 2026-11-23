package vn.shop.crm.testrun;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.customers.CustomerController;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.zalo.ZaloMessageService;

import java.util.ArrayList;
import java.util.List;

/** Trang "Chiến dịch thử": chạy thử cả quy trình qua bộ nối thật bằng vài số tự nhập, tách hẳn khỏi khách thật. Tạm thời. */
@Controller
@org.springframework.web.bind.annotation.RequestMapping("/test-runs")
public class TestRunController {

    private final TestRunService tests;
    private final InviteService invites;
    private final ZaloMessageService messages;

    public TestRunController(TestRunService tests, InviteService invites, ZaloMessageService messages) {
        this.tests = tests;
        this.invites = invites;
        this.messages = messages;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("runs", tests.runs());
        model.addAttribute("defaultTemplate", invites.defaultTemplate());
        model.addAttribute("cfg", tests.defaults());
        model.addAttribute("groups", tests.targetGroups());
        model.addAttribute("quota", messages.quota());
        model.addAttribute("provinces", vn.shop.crm.geo.VnGeo.all());
        model.addAttribute("areaGroups", CustomerController.areaGroups());
        model.addAttribute("slots", java.util.stream.IntStream.range(0, TestRunService.MAX_TARGETS).boxed().toList());
        model.addAttribute("maxTargets", TestRunService.MAX_TARGETS);
        return "test-runs";
    }

    @PostMapping
    public String create(@RequestParam String name, @RequestParam(required = false) String template,
                         @RequestParam(required = false) String linkTemplate, @RequestParam(required = false) Integer dailyLimit,
                         @RequestParam(required = false) Integer gapSeconds, @RequestParam(required = false) Long zaloGroupId,
                         @RequestParam(required = false) String sendLink,
                         @RequestParam(name = "phone", required = false) List<String> phones,
                         @RequestParam(name = "personName", required = false) List<String> names,
                         @RequestParam(name = "province", required = false) List<String> provinces,
                         RedirectAttributes redirect) {
        List<TestRunService.Entry> entries = new ArrayList<>();
        for (int i = 0; phones != null && i < phones.size(); i++) {
            entries.add(new TestRunService.Entry(phones.get(i), at(names, i), at(provinces, i)));
        }
        long id = tests.create(new TestRunService.Config(name, template, linkTemplate, dailyLimit, gapSeconds, zaloGroupId,
                sendLink != null), entries);
        redirect.addFlashAttribute("flash", "Đã tạo chiến dịch thử. Đây là dữ liệu thử, không đụng tới khách thật. Bấm Bắt đầu khi sẵn sàng.");
        return "redirect:/test-runs/" + id;
    }

    private static String at(List<String> list, int i) {
        return list != null && i < list.size() ? list.get(i) : null;
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        var run = tests.run(id).orElseThrow(() -> new ImportException("Không tìm thấy đợt chạy thử."));
        model.addAttribute("run", run);
        model.addAttribute("targets", tests.targets(id));
        model.addAttribute("groups", tests.targetGroups());
        model.addAttribute("quota", messages.quota());
        model.addAttribute("blocker", messages.blocker("TEST:" + id, vn.shop.crm.zalo.ZaloMessenger.Kind.INVITE, run.dailyLimit(), run.gapSeconds()).orElse(null));
        model.addAttribute("usedByRun", messages.usedToday("TEST:" + id));
        return "test-run-detail";
    }

    @PostMapping("/{id}/config")
    public String config(@PathVariable long id, @RequestParam String name, @RequestParam(required = false) String template,
                         @RequestParam(required = false) String linkTemplate, @RequestParam(required = false) Integer dailyLimit,
                         @RequestParam(required = false) Integer gapSeconds, @RequestParam(required = false) Long zaloGroupId,
                         @RequestParam(required = false) String sendLink, RedirectAttributes redirect) {
        tests.updateConfig(id, new TestRunService.Config(name, template, linkTemplate, dailyLimit, gapSeconds, zaloGroupId, sendLink != null));
        redirect.addFlashAttribute("flash", "Đã lưu cấu hình chiến dịch thử.");
        return "redirect:/test-runs/" + id;
    }

    @PostMapping("/{id}/start")
    public String start(@PathVariable long id, RedirectAttributes redirect) {
        tests.start(id);
        redirect.addFlashAttribute("flash", "Đã bắt đầu. Hệ thống gửi từng tin một theo hạn mức và khoảng nghỉ; trang tự cập nhật khi bạn mở lại.");
        return "redirect:/test-runs/" + id;
    }

    @PostMapping("/{id}/stop")
    public String stop(@PathVariable long id, RedirectAttributes redirect) {
        tests.stop(id);
        redirect.addFlashAttribute("flash", "Đã dừng chiến dịch thử. Tin đã gửi không thu hồi được.");
        return "redirect:/test-runs/" + id;
    }

    @PostMapping("/{id}/targets/{targetId}/sent")
    public String sent(@PathVariable long id, @PathVariable long targetId, RedirectAttributes redirect) {
        tests.markSent(targetId);
        redirect.addFlashAttribute("flash", "Đã ghi: đã nhắn (chỉ trong đợt thử).");
        return "redirect:/test-runs/" + id;
    }

    @PostMapping("/{id}/targets/{targetId}/friended")
    public String friended(@PathVariable long id, @PathVariable long targetId, RedirectAttributes redirect) {
        tests.markFriended(targetId);
        redirect.addFlashAttribute("flash", "Đã ghi: đã kết bạn (chỉ trong đợt thử).");
        return "redirect:/test-runs/" + id;
    }

    @PostMapping("/{id}/targets/{targetId}/joined")
    public String joined(@PathVariable long id, @PathVariable long targetId, RedirectAttributes redirect) {
        tests.markJoined(targetId);
        redirect.addFlashAttribute("flash", "Đã ghi: đã vào nhóm (chỉ trong đợt thử, số khách của nhóm KHÔNG tăng).");
        return "redirect:/test-runs/" + id;
    }

    @PostMapping("/{id}/targets/{targetId}/declined")
    public String declined(@PathVariable long id, @PathVariable long targetId, RedirectAttributes redirect) {
        tests.markDeclined(targetId);
        redirect.addFlashAttribute("flash", "Đã ghi: từ chối (chỉ trong đợt thử, KHÔNG vào danh sách từ chối thật).");
        return "redirect:/test-runs/" + id;
    }

    @PostMapping("/{id}/reset")
    public String reset(@PathVariable long id, RedirectAttributes redirect) {
        tests.reset(id);
        redirect.addFlashAttribute("flash", "Đã đưa đợt thử về lúc chưa nhắn để thử lại từ đầu.");
        return "redirect:/test-runs/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, RedirectAttributes redirect) {
        tests.delete(id);
        redirect.addFlashAttribute("flash", "Đã xóa đợt chạy thử và các số đã nhập.");
        return "redirect:/test-runs";
    }
}
