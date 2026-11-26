package vn.shop.crm.zalo;

import vn.shop.crm.safety.TestMode;

import java.util.List;

/**
 * Lưới an toàn CUỐI CÙNG của chế độ thử: bọc đường nối duy nhất tới Zalo. Mọi việc chạm tới MỘT NGƯỜI (gửi tin, thêm vào nhóm, tạo nhóm
 * có thành viên, đọc tin riêng) đều bị kiểm lại ở đây ngay trước khi gọi cầu nối; ở chế độ thử, người không thuộc danh sách số thử bị từ chối
 * với kết quả "chưa chạm Zalo" (không tính hạn mức, không làm cầu dao nhảy) và tin riêng của họ bị bỏ ngay, không tới AI.
 *
 * <p>Các lớp trên (danh sách mời, chiến dịch, hàng chờ) đã lọc từ nguồn nên bình thường lớp này không bao giờ chặn gì; nó có mặt để một chỗ
 * quên lọc, một lỗi hay một trạng thái cũ trong cơ sở dữ liệu cũng không đưa được tin tới khách thật. Chạy thật thì chỉ chuyển tiếp.
 * Tra người để xác nhận phó nhóm ({@link #findByPhone}) chỉ đọc và do chủ shop nhập số, nên được chuyển tiếp.
 */
public final class SafeZaloLink implements ZaloLink {

    static final String CODE = "TEST_MODE";

    private final ZaloLink inner;
    private final TestMode mode;
    /** Số thứ tự lớn nhất của tin riêng đã bị bỏ và đã đếm (để không đếm lại cùng một tin ở các nhịp sau). */
    private volatile long countedSeq;

    public SafeZaloLink(ZaloLink inner, TestMode mode) {
        this.inner = inner;
        this.mode = mode;
    }

    // ---------- ZaloGateway ----------

    @Override
    public Status status() {
        return inner.status();
    }

    /** Chỉ đọc sự kiện kết bạn (không liên hệ ai): chuyển tiếp. Việc làm theo sự kiện (thêm nhóm) bị chặn ở {@link #addToGroup}. */
    @Override
    public List<FriendEvent> friendEvents(long afterSeq) {
        return inner.friendEvents(afterSeq);
    }

    /** Chỉ đọc; ở chế độ thử chỉ hỏi về các mã thuộc số thử (lớp chặn thứ hai sau bộ lọc ở nơi gọi). */
    @Override
    public FriendCheck friendsAmong(List<String> uids) {
        List<String> allowed = uids == null ? List.of() : uids.stream().filter(mode::uidAllowed).toList();
        if (allowed.isEmpty()) {
            return FriendCheck.of(List.of());
        }
        return inner.friendsAmong(allowed);
    }

    @Override
    public AddResult addToGroup(String uid, String zaloGroupId) {
        if (!mode.uidAllowed(uid)) {
            mode.noteBlocked("THEM_NHOM");
            return AddResult.of(Outcome.UNREACHABLE, CODE);
        }
        return inner.addToGroup(uid, zaloGroupId);
    }

    // ---------- ZaloMessenger ----------

    @Override
    public Sent sendByPhone(Kind kind, String phoneDigits, String text) {
        if (!mode.phoneAllowed(phoneDigits)) {
            mode.noteBlocked("GUI_TIN_" + kind);
            return Sent.of(Result.UNREACHABLE, CODE, true);
        }
        return inner.sendByPhone(kind, phoneDigits, text);
    }

    @Override
    public Sent sendByUid(Kind kind, String uid, String text) {
        if (!mode.uidAllowed(uid)) {
            mode.noteBlocked("GUI_TIN_" + kind);
            return Sent.of(Result.UNREACHABLE, CODE, true);
        }
        return inner.sendByUid(kind, uid, text);
    }

    @Override
    public Sent sendReply(String uid, String text, java.util.function.BooleanSupplier stillEligible) {
        if (!mode.uidAllowed(uid)) {
            mode.noteBlocked("GUI_TIN_" + Kind.REPLY);
            return Sent.of(Result.UNREACHABLE, CODE, true);
        }
        return inner.sendReply(uid, text, stillEligible);
    }

    @Override
    public Sent sendFriendRequest(String uid, String text) {
        if (!mode.uidAllowed(uid)) {
            mode.noteBlocked("KET_BAN");
            return Sent.of(Result.UNREACHABLE, CODE, true);
        }
        return inner.sendFriendRequest(uid, text);
    }

    @Override
    public Sent acceptFriendRequest(String uid) {
        if (!mode.uidAllowed(uid)) {
            mode.noteBlocked("NHAN_KET_BAN");
            return Sent.of(Result.UNREACHABLE, CODE, true);
        }
        return inner.acceptFriendRequest(uid);
    }

    @Override
    public List<String> incomingRequests() {
        return inner.incomingRequests().stream().filter(mode::uidAllowed).toList();
    }

    @Override
    public Sent undoFriendRequest(String uid) {
        if (!mode.uidAllowed(uid)) {
            mode.noteBlocked("THU_HOI_KET_BAN");
            return Sent.of(Result.UNREACHABLE, CODE, true);
        }
        return inner.undoFriendRequest(uid);
    }

    // ---------- ZaloGroupMaker ----------

    @Override
    public Created createGroup(String name, List<String> memberUids, String deputyUid, String welcome) {
        if (memberUids == null || memberUids.stream().anyMatch(u -> !mode.uidAllowed(u))) {
            mode.noteBlocked("TAO_NHOM");
            return Created.of(GroupOutcome.UNREACHABLE, CODE, true);
        }
        return inner.createGroup(name, memberUids, deputyUid, welcome);
    }

    /** Ở chế độ thử chỉ đặt phó nhóm cho nhóm do chính Adh tạo (có mã nhóm Zalo trong bảng nhóm); mã lạ thì từ chối, chưa chạm Zalo. */
    @Override
    public DeputyResult setDeputy(String groupId, String deputyUid) {
        if (!mode.ownGroup(groupId)) {
            mode.noteBlocked("PHO_NHOM");
            return DeputyResult.of(GroupOutcome.UNREACHABLE, CODE);
        }
        return inner.setDeputy(groupId, deputyUid);
    }

    // ---------- ZaloPersonFinder ----------

    @Override
    public Found findByPhone(String phoneDigits) {
        return inner.findByPhone(phoneDigits);
    }

    // ---------- ZaloInboxReader ----------

    /** Ở chế độ thử chỉ tin của số thử đi tiếp: tin của khách thật bị bỏ ngay, nội dung không bao giờ tới AI. */
    @Override
    public List<Inbound> inbound(long afterSeq) {
        List<Inbound> all = inner.inbound(afterSeq);
        if (mode.live() || all.isEmpty()) {
            return all;
        }
        List<Inbound> kept = new java.util.ArrayList<>();
        long highest = countedSeq;
        for (Inbound m : all) {
            if (mode.inboundAllowed(m.uid())) {
                kept.add(m);
            } else if (m.seq() > countedSeq) {
                // cầu nối giữ tin vài giờ nên cùng một tin bị bỏ lại được hỏi lại mỗi nhịp: chỉ đếm mỗi tin một lần
                mode.noteBlocked("DOC_TIN_RIENG");
                highest = Math.max(highest, m.seq());
            }
        }
        countedSeq = highest;
        return List.copyOf(kept);
    }

    // ---------- ZaloReactor ----------

    @Override
    public Reacted heart(long seq, String uid) {
        if (!mode.uidAllowed(uid)) {
            mode.noteBlocked("THA_TIM");
            return Reacted.failed(CODE, true);
        }
        return inner.heart(seq, uid);
    }

    // ---------- ZaloSession ----------

    @Override
    public GroupList listGroups() {
        return inner.listGroups();
    }

    @Override
    public Login startLogin() {
        return inner.startLogin();
    }

    @Override
    public Login loginStatus() {
        return inner.loginStatus();
    }

    @Override
    public boolean logout() {
        return inner.logout();
    }

    @Override
    public boolean stop() {
        return inner.stop();
    }

    @Override
    public boolean resume() {
        return inner.resume();
    }
}
