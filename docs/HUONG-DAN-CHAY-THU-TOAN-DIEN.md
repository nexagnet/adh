# Hướng dẫn chạy thử toàn diện (giai đoạn 1) bằng số điện thoại giả làm khách

Viết ngày 23/11/2026 cho chủ shop. Mục tiêu: chạy thử MỌI phần của giai đoạn 1 bằng vài số điện thoại của bạn (đóng vai khách) **trước khi** cho khách thật nhận tin. Mọi thứ đều đã được kiểm bằng test với bộ nối giả; **chưa lần nào chạy với Zalo thật và DeepSeek thật**, nên hãy đi từng bước nhỏ và dừng ngay khi thấy lạ.

Tài liệu bổ sung (không thay thế): `docs/HUONG-DAN-THU-ZALO-TU-DONG.md` (cài đặt bộ nối, đăng nhập QR, gán mã nhóm), `docs/THIET-KE-AI-TRA-LOI.md`, `docs/THIET-KE-KHACH-QUAY-LAI.md`.

## 0. Hai cách thử, và vì sao cần cả hai

| | Chiến dịch thử (`/test-runs`) | Khách giả như khách thật |
|---|---|---|
| Kiểm | Gửi Tin 1, kết bạn, tự thêm vào nhóm, Tin 2, cầu dao, hạn mức | Cả chuỗi thật: duyệt khách, chiến dịch, tự gửi, tự tạo nhóm, **AI nhận diện từ chối, AI trả lời, khách quay lại, tự nhận khách mới** |
| Dữ liệu | Số nhập tay, nằm bảng riêng, không đụng khách thật | Nhập vài đơn giả (số của bạn) vào danh sách khách; xóa sau khi thử |
| AI | **Không chạy** (số thử không phải khách trong hệ thống) | Chạy đủ |

Vì vậy muốn "test toàn diện" phải làm cả hai. Làm Chiến dịch thử trước (an toàn nhất), rồi mới đến khách giả.

**Quy tắc quan trọng: mỗi số điện thoại chỉ nhận Tin 1 MỘT lần trong đời** (cầu nối nhớ mã Zalo của số đó và không gửi lại, kể cả bấm "Thử lại từ đầu"). Nên **chia danh sách số của bạn thành hai nhóm**, không dùng một số ở cả hai cách:

- Nhóm A (Chiến dịch thử): 2 đến 3 số.
- Nhóm B (khách giả): ít nhất 5 số, vai trò ở mục 4. Càng nhiều số càng thử được nhiều tình huống.
- Muốn dùng lại một số cho Tin 1 thì phải nhờ người quản máy chủ xóa file nhớ của cầu nối (`messages.json` trong ổ dữ liệu bộ nối); đó là thao tác trên máy chủ, đừng tự làm.

## 1. Trả lời nhanh các câu hỏi

1. **Giao diện chạy thử đã đúng nghiệp vụ chưa?** Phần mời (Tin 1, kết bạn, thêm nhóm, Tin 2, hạn mức, giờ 08:00 đến 20:00, nghỉ cố định, cầu dao) đúng. Đã sửa và bổ sung: ghi rõ màn này **không kiểm AI**, và nhắc "mỗi số một Tin 1". Đã sửa cấu hình máy chủ: trần 50 khách/ngày bị cài nhầm 25 trong file compose (và không có tác dụng vì tên biến không khớp); nay đúng 50.
2. **Trang nào xóa sau khi thử xong?** Xem mục 7. Chưa xóa gì; chỉ xóa khi bạn báo thử xong.
3. **Bật hết cờ trên máy chủ?** Được và an toàn: cờ ở máy chủ chỉ **mở khóa** tính năng, không tự gửi gì. Việc gửi thật chỉ xảy ra khi bạn bật công tắc trên web. Xem mục 2.
4. **Có cần cấu hình tài khoản Zalo chốt đơn của mẹ không?** Không đăng nhập, không nhập mật khẩu hay mã nào của mẹ vào hệ thống (cầu nối chỉ đăng nhập tài khoản phụ và từ chối mọi tài khoản khác). Mẹ chỉ cần: (a) là bạn Zalo với tài khoản phụ, (b) bật cho "tìm bằng số điện thoại" trong Zalo của mẹ, (c) bạn nhập **số điện thoại của mẹ** ở `/zalo` mục "Mẹ làm phó nhóm" để mẹ được thêm làm phó nhóm khi hệ thống tạo nhóm. Mẹ xử lý "việc cần người thật" bằng đăng nhập quản trị hiện có (hiện chỉ có một tài khoản). **Chưa chốt:** mẹ trả lời khách từ đâu (Zalo của chính mẹ khác với cuộc trò chuyện khách đang có với tài khoản phụ); lúc thử, người thật trả lời bằng điện thoại giữ tài khoản phụ.

## 2. Cờ trên máy chủ (cho người SSH vào máy chủ; không gửi khóa qua chat)

Đã có từ trước (không đổi): `APP_ZALO_AUTO_ENABLED=true`, `COMPOSE_PROFILES=zalo-auto`, `APP_ZALO_BRIDGE_KEY`, `ZALO_SESSION_KEY`, `ZALO_ACCOUNT_ID` (tài khoản phụ đã đăng nhập bằng QR).

Cần thêm vào file `.env` trên máy chủ, rồi triển khai lại **một lần** (cả ứng dụng và cầu nối; đọc log cầu nối trước khi tạo lại container):

| Biến | Giá trị | Để làm gì |
|---|---|---|
| `APP_AI_ENABLED` | `true` | Bật AI (DeepSeek) |
| `APP_AI_API_KEY` | khóa DeepSeek của bạn | Chỉ ghi trong `.env` trên máy chủ, không vào chat, repo hay log |
| `APP_AI_MODEL` | tên mô hình bạn chọn | Hệ thống không đoán tên mô hình |
| `APP_ZALO_INBOX_ENABLED` | `true` | Ứng dụng đọc tin riêng của khách đã nhận tin |
| `BRIDGE_INBOX_ENABLED` | `true` | Cầu nối giữ tin riêng (chỉ trong bộ nhớ, tối đa 6 giờ) |
| `APP_SHOP_NAME` | tên shop thật | Có trong Tin 1 và chữ ký "Trợ lý AI của ...". Đừng để mặc định "Cửa hàng" |

Không cần đặt: `ZALO_DEPUTY_ID` (chỉ dự phòng; dùng trang `/zalo` để chọn mẹ), `ZALO_PERSON_ERROR_CODES` (để trống lúc đầu; sau lần thử đầu, người SSH đọc log cầu nối, thấy mã lỗi nào chắc chắn là lỗi riêng một người thì điền vào), các ngưỡng AI (mặc định 0,8 và 0,85 là hợp lý), `BRIDGE_MSG_DAILY` (phanh khẩn cấp 150 tin/ngày, giữ nguyên).

Triển khai bản mới (PR của hướng dẫn này) cũng sửa trần 50 khách/ngày; cron tự deploy sẽ đưa lên trong khoảng 10 phút sau khi gộp.

## 3. Công tắc trên web (bạn bấm, theo đúng thứ tự)

Đăng nhập quản trị. Mọi thứ dưới đây tắt mặc định. Mọi lúc muốn dừng khẩn cấp: mục 6.

**Bước 0: kiểm chuẩn bị (chưa gửi gì)**
1. `/zalo`: tài khoản phụ báo "đang nghe" và đúng tài khoản.
2. `/campaigns`, ô nội dung mặc định: đọc lại Tin 1. Tin 1 phải nói "sản phẩm" (không chỉ "pin"), có tên shop, ưu đãi mua qua nhóm rẻ hơn TikTok 10% và miễn ship. Nếu mẹ từng lưu bản cũ có chữ "pin" thì sửa tại đây.
3. `/zalo/reply`, "Bảng thông tin cửa hàng": điền ưu đãi (giảm 10% so với TikTok, miễn ship), cách liên hệ bảo hành. Ô nào để trống thì AI không nói về việc đó.
4. `/zalo/reply`, "Thử một câu": gõ khoảng 30 câu khách có thể nhắn (hỏi vào nhóm, hỏi ưu đãi, hỏi giá, hỏi bảo hành, phàn nàn, từ chối, "gặp người thật", câu lạ). Câu ngoài phạm vi phải bị chuyển người thật, câu trong phạm vi phải đúng bảng thông tin. **Đây là cách thử AI không cần Zalo.** Câu nào lạ, chụp lại gửi cho người sửa.
5. `/groups`: có sẵn ít nhất một nhóm **thử** đã gán mã nhóm Zalo (xem `HUONG-DAN-THU-ZALO-TU-DONG.md` mục 1.5 và 1.6; tài khoản phụ phải ở trong nhóm).

## 4. Các giai đoạn thử

Chỉ gửi trong **08:00 đến 20:00**; ngoài giờ hệ thống tự chờ. Hạn mức: ở `/zalo` mục "Tự gửi tin cho khách", đặt **5 khách/ngày**, nghỉ **120 giây** cho đợt thử (trần thật là 50).

### Giai đoạn A: Chiến dịch thử (Nhóm A, 2 đến 3 số)

1. `/zalo`, mục "Nấc hoạt động": chọn "Tự thêm trong hạn mức", bấm "Lưu nấc".
2. Cùng trang, mục "Tự gửi tin cho khách": đặt hạn mức như trên, bấm "Bật tự gửi tin".
3. Menu "Chạy thử trên Zalo" (`/test-runs`): nhập các số Nhóm A (tên, tỉnh tùy ý), chọn nhóm đích là nhóm thử, bấm "Tạo chiến dịch thử", mở nó, bấm **Bắt đầu**.
4. **Kỳ vọng:** mỗi hai phút một tin; chi tiết hiện "Đã nhắn". Trên điện thoại số thử nhận Tin 1 từ tài khoản phụ (người lạ). Việc **kết bạn làm bằng tay** (hệ thống cố ý không tự gửi hay nhận lời mời kết bạn): số thử bấm kết bạn rồi người cầm điện thoại tài khoản phụ chấp nhận, hoặc ngược lại; ghi lại chiều nào xảy ra để sửa chữ Tin 1 cho đúng.
5. Khi đã là bạn: trong vài phút hệ thống **thêm vào nhóm thử**, rồi gửi **Tin 2**. Chi tiết hiện "Đã vào nhóm", "Đã gửi Tin 2".
6. Đọc "Nhật ký gần đây" ở `/zalo`. Gặp mã lỗi lạ: bộ nối tự dừng (cầu dao), **đừng bấm Mở lại liên tục**; báo người SSH đọc log và điền `ZALO_PERSON_ERROR_CODES`.
7. Xong, bấm **Dừng** ở chiến dịch thử. **Tạo nhóm bằng tay chưa phải bước này** (xem giai đoạn C).

Nếu giai đoạn A không êm (lỗi lạ, Zalo cảnh báo), **dừng ở đây**, chưa sang B.

### Giai đoạn B: Khách giả như khách thật (Nhóm B, ít nhất 5 số)

**Chuẩn bị file đơn giả** (làm trên máy bạn, không đưa lên GitHub): một file CSV đúng định dạng J&T, ví dụ cột `Mã vận đơn, Mã đơn hàng, Thời gian tạo, Người nhận, Số điện thoại người nhận, Địa chỉ nhận, Trạng thái` (mẫu: `fixtures/jt_sample.csv`). Mỗi số Nhóm B một dòng, mỗi dòng **một đơn, trạng thái "Đã ký nhận"**, mã vận đơn và mã đơn **khác nhau và không trùng đơn thật** (ví dụ bắt đầu bằng TEST), địa chỉ có tỉnh rõ (ví dụ "Quận 1, TP Hồ Chí Minh"). Ít nhất hai số cùng một tỉnh, tỉnh đó **thuộc miền chưa có nhóm nào còn chỗ** (để thử tự tạo nhóm). Giữ riêng một số (số cuối cùng) cho bước "tự nhận khách mới", không đưa vào file đầu.

**Cẩn thận dữ liệu thật:** trong lúc thử **đừng nhập file J&T thật** và chạy tool Edge (khách thật mới nhập về có thể bị chiến dịch tự nhận mời). Trước khi bắt đầu mở `/contacts` xem "Đã duyệt, đủ điều kiện mời": nếu đã có khách thật ở đó, **hãy tạo chiến dịch chọn đúng khu vực của khách giả** để không lấy khách thật.

1. Menu "Nhập đơn": chọn file, đối chiếu cột, nhập. Mở "Khách hàng" kiểm các số giả đã có (mỗi người "mua một lần").
2. `/contacts` ("Danh sách mời"): bấm **Duyệt N khách** (chỉ nên có các số giả).
3. `/campaigns`: bước 1 chọn khu vực của khách giả, bước 3 bấm "Tạo chiến dịch (chưa gửi gì)". Mở chiến dịch, bấm **Duyệt cho hệ thống tự gửi**.
4. Trước khi khách giả nhận tin, đặt các nấc AI ở mức an toàn:
   - `/zalo/reply`: nấc **Chỉ soạn nháp** (AI soạn nhưng không gửi).
   - `/zalo` mục "Khách từng từ chối nay nhắn lại muốn mua": nấc **Chỉ tạo việc**.
   - `/zalo` mục "Tự tạo nhóm Zalo": nấc **Xác nhận**, "Khách chờ tối thiểu để tạo nhóm" đặt **2**, bấm "Lưu giới hạn"; mục "Mẹ làm phó nhóm": nhập số điện thoại của mẹ, "Tra tên và ảnh", nhìn đúng người thì "Đúng, làm phó nhóm". (Điều kiện: mẹ đã là bạn của tài khoản phụ và bật cho tìm bằng số.)
5. Hệ thống tự gửi Tin 1 cho từng khách giả, mỗi hai phút một người. Rồi **mỗi số đóng một vai**:

| Số | Vai | Việc làm trên điện thoại | Kỳ vọng |
|---|---|---|---|
| B1 | Từ chối rồi quay lại | Nhận Tin 1, nhắn lại "mình không cần, đừng nhắn nữa". Vài phút sau nhắn tiếp "mình muốn mua thêm áo điều hòa, còn không?" | AI ghi từ chối (ở `/handoffs` mục "Trợ lý AI đã ghi khách từ chối", có nút **Hoàn tác**); tin sau tạo **việc nổi bật** "khách quay lại muốn mua" (nấc Chỉ tạo việc, chưa tự gỡ) |
| B2 | Khách ngoan | Kết bạn, được thêm vào nhóm (hoặc chờ nhóm mới). Sau đó nhắn "vào nhóm có ưu đãi gì vậy?" | Nháp trả lời ở `/zalo/reply` mục "Nháp trợ lý soạn": đúng bảng thông tin, không hứa gì ngoài bảng, có chữ ký trợ lý AI |
| B3 | Hỏi ngoài phạm vi | Nhắn "áo này giá bao nhiêu, bảo hành sao, còn size L không?" | Không có nháp; có việc ở `/handoffs` ("người thật") |
| B4 | Muốn gặp người thật / phàn nàn | Nhắn "cho mình gặp người thật" rồi một câu phàn nàn | Việc ở `/handoffs` |
| B5 | Không trả lời | Không làm gì | Không có gì xảy ra; không bị nhắn lại |

6. **Tự tạo nhóm:** khi có ít nhất 2 khách giả cùng tỉnh đã kết bạn mà chưa có nhóm hợp, `/zalo` mục "Tự tạo nhóm" hiện **đề xuất**; bạn bấm **Duyệt**. Kỳ vọng: nhóm tên `Áo điều hòa ADH - <khu vực> - 01` xuất hiện trên Zalo với tài khoản phụ làm trưởng nhóm, mẹ là phó nhóm, khách giả được thêm. Đây là lần **thử tay 1 nhóm thật** mà trước nay còn nợ.
7. **Tự nhận khách mới:** chiến dịch đã duyệt, bấm **Bật tự nhận khách mới** (`/campaigns/{id}`). Sau đó nhập file CSV thứ hai chỉ có số giữ riêng (số cuối). Trong vài nhịp, số đó tự được duyệt và nhận Tin 1. Khách có từ trước không bao giờ bị tự duyệt.
8. **Chuyển sang tự động** (chỉ khi các bước trên đúng, đã xem nháp và việc AI tạo):
   - `/zalo/reply`: tick "Tôi đã chạy nấc Chỉ soạn nháp...", chọn **Tự động**, Lưu. B2 nhắn lại một câu hỏi ưu đãi: phải nhận **câu trả lời tự động** kèm chữ ký "Trợ lý AI của ..." và cách gặp người thật.
   - `/zalo` khách quay lại: tick xác nhận, chọn **Tự động**. B1 nhắn lại "mình muốn mua": AI chắc từ 0,85 thì tự gỡ cờ (chỉ đánh dấu "đã gỡ"), đưa vào hàng chờ vào nhóm và gửi **một** tin mẫu; có nút Hoàn tác.
   - B3, B4 vẫn phải về người thật. Câu trả lời tự động **không bao giờ** nói về giá, bảo hành ngoài bảng, đổi trả, link lạ hay số điện thoại.

### Giai đoạn C: những việc nhỏ cần thử riêng

- **Cầu dao:** không cố làm cầu dao nhảy trên Zalo thật. Trước hết xem `/zalo` mục "Tự gửi tin cho khách" hiện đúng giờ gửi, hạn mức và khoảng nghỉ, và mục "Tạm dừng và nhật ký gửi" có nút tạm dừng.
- **Khách đã vào nhóm nhưng không có nhóm hợp khu vực:** xuất hiện ở `/zalo` mục "Khách chờ vào nhóm" (ngoại lệ mẹ xử lý).
- **Hoàn tác:** bấm Hoàn tác một khách B1 ở `/handoffs`, thấy cờ từ chối gỡ (số vẫn còn trong hệ thống, **không bao giờ tự xóa số**).

## 5. Khi gặp lỗi

| Hiện tượng | Làm gì |
|---|---|
| Không gửi gì dù đã bật | Xem `/zalo` mục "Tự gửi tin cho khách" (công tắc, số đã nhắn hôm nay, trong hay ngoài giờ gửi) và "Nhật ký gần đây". Phần lớn là chờ khoảng nghỉ hoặc ngoài 08:00 đến 20:00; ở chi tiết Chiến dịch thử có huy hiệu "Chưa gửi: ..." nói lý do |
| Cầu dao nhảy (trang `/zalo` báo đã tự dừng kèm lý do) | **Đừng mở lại ngay.** Đọc lý do, báo người SSH xem log cầu nối; mở lại chỉ khi hiểu lý do |
| Tài khoản phụ bị Zalo cảnh báo hoặc khóa | Dừng toàn bộ (mục 6), xem `HUONG-DAN-THU-ZALO-TU-DONG.md` mục 3. Không đăng nhập đi đăng nhập lại |
| AI báo lỗi, hết lượt hoặc sai khuôn | Việc chuyển người thật, khách đó tạm dừng; không gì tự ghi từ chối. Kiểm khóa và tên mô hình |
| Số báo "Không tìm thấy trên Zalo" | Số chưa có Zalo hoặc không cho tìm bằng số điện thoại; không phải lỗi hệ thống |
| Tin trả lời lạ, sai bảng thông tin | Chuyển nấc về **Chỉ soạn nháp** hoặc **Tắt**, chụp lại gửi người sửa |

## 6. Dừng khẩn cấp (từ nhẹ đến mạnh)

1. `/campaigns`: **Tạm dừng tất cả chiến dịch** (không gửi Tin 1 mới).
2. `/zalo`: **Tắt tự gửi tin**; hoặc **Dừng ngay** (cầu dao: dừng cả gửi tin lẫn tạo nhóm cho tới khi bấm Mở lại).
3. `/zalo/reply` và khách quay lại: chọn nấc **Tắt**; nấc tạo nhóm: **Tắt**; nấc hoạt động: **Tắt**.
4. Người SSH: tắt `APP_ZALO_AUTO_ENABLED` hoặc dừng container cầu nối.
5. Cuối cùng: `/zalo` **Đăng xuất tài khoản phụ**.

## 7. Dọn dẹp sau khi thử xong

**Dữ liệu giả (do bạn tự bấm, vì hệ thống không bao giờ tự xóa số):** ở "Khách hàng", xóa từng khách giả bằng "Xóa dữ liệu khách" (gõ chữ XOA). Chi tiết đơn giả ở "Nhập đơn" cũng cần xem lại để khỏi lẫn vào số liệu thật.

**Màn hình và mã sẽ xóa (tôi làm sau khi bạn báo thử xong; chưa xóa gì lúc này):**

1. **Chiến dịch thử:** menu "Chạy thử trên Zalo" (`/test-runs`), gói `vn.shop.crm.testrun` (3 file), 2 mẫu `test-runs.html` và `test-run-detail.html`, `TestRunTest`, `TestRunAutomationTest`; thêm migration số kế tiếp (hiện là `V33`) bỏ bảng `test_run` và `test_target` của V22; gỡ chỗ cắm `ZaloFriendTap` / `addExternal` nếu không còn dùng.
2. **Màn nhắn tay:** "Lô nhắn hôm nay" (`/today`, `TodayController`, `today.html`, `TodayQueueTest`), "Chờ vào nhóm" (`/friends`, `FriendsController`, `friends.html`, `FriendQueueTest`, `FriendFlowTest`), mục menu của hai màn này, khối "Việc cần làm hôm nay" ở Tổng quan có mục nhắn tay.
3. **Nút nhắn tay** ở chi tiết chiến dịch: Đã nhắn, Đã kết bạn, Đã vào nhóm, Đã gửi link nhóm, Khách đã rời nhóm, Khách từ chối bấm tay (các `POST /campaigns/{id}/invitations/{id}/...`), nút "Xuất danh sách để mẹ gửi tay", và xem lại nút "Chạy thử (không gửi gì)" (`/campaigns/{id}/dry-run`) xem còn cần không sau khi bỏ hàng đợi nhắn tay.

**Giữ lại:** `/zalo` (trung tâm điều khiển), `/zalo/reply`, `/handoffs`, `/campaigns` (tạo, duyệt, tự nhận khách mới, tạm dừng), `/contacts`, `/groups` và gán mã nhóm, `/customers`, `/privacy`, `/print`, `/map`, `/bot` (trợ lý trong nhóm, cho giai đoạn 2).

## 8. Việc bạn cần có trước khi bắt đầu (kiểm nhanh)

- [ ] Tài khoản phụ đã quét QR, trang `/zalo` báo đang nghe.
- [ ] Khóa DeepSeek và tên mô hình đã đưa cho người SSH (không gửi qua chat).
- [ ] Danh sách số điện thoại chia làm Nhóm A (2 đến 3) và Nhóm B (5 trở lên), tất cả đã đồng ý nhận tin thử và có Zalo, tắt hoặc bật "cho tìm bằng số điện thoại" theo ý muốn thử (số tắt tìm sẽ báo "không tìm thấy").
- [ ] Mẹ đã là bạn Zalo của tài khoản phụ và bật cho tìm bằng số điện thoại.
- [ ] Một nhóm Zalo thử đã tạo (tài khoản phụ ở trong), đã gán mã ở `/groups`.
- [ ] Không có ai đang nhập file J&T thật trong lúc thử.
