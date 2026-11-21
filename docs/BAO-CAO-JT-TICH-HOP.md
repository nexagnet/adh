# Báo cáo: xác minh J&T và hướng đồng bộ đơn hằng ngày (21/11/2026)

Chỉ đọc, quan sát trên trình duyệt đang đăng nhập J&T của máy người dùng. Không sửa dữ liệu trên J&T, không tạo tài khoản con, không bật thông báo, không đổi "Cấu hình cột". Báo cáo này không chứa mật khẩu, cookie, token, SĐT hay tên khách; các con số về file xuất chỉ là thống kê.

## 1. Đã xác nhận (có bằng chứng trực tiếp)

**File "Xuất" ở màn Đơn hàng** (đối chiếu với 7 file thật trong thư mục Tải xuống, 414 dòng, tạo đơn từ 28/06 đến 20/11/2026):

- 14 cột đúng như `docs/BAN-GIAO.md`: Mã vận đơn, Trạng thái vận đơn, SĐT người nhận, Địa chỉ người nhận, Tên người nhận, Địa chỉ hành chính, Nội dung hàng hóa, Thời gian tạo đơn, Trọng lượng tính phí, Cước phí, Phương thức kết toán, Giao một phần, Tiền thu hộ COD, COD thực thu. Một sheet, dòng 1 là tiêu đề.
- Mã vận đơn, SĐT (giữ số 0 đầu) và Thời gian tạo đơn đều là **chuỗi chữ** trong file J&T gốc, nên không bị Excel đổi sang số khoa học. Chỉ hỏng nếu ai đó mở và lưu lại bằng Excel.
- **"Địa chỉ hành chính" có 2 dạng**: 3 phần "Xã-mã, Huyện, Tỉnh" (287 dòng) và 2 phần "Xã-mã, Tỉnh" (127 dòng, địa chỉ sau khi bỏ cấp huyện). **Tỉnh luôn là phần cuối cùng sau dấu phẩy.** Bộ nhập nên lấy phần cuối, không đếm vị trí từ đầu.
- Trạng thái xuất hiện trong file: Đã lấy hàng (107), Giao thành công (291), Hủy (2), Ký nhận chuyển hoàn (9), Đang vận chuyển (4), **Kiện vấn đề (1)**. Bảng ánh xạ `V3` hiện thiếu "Kiện vấn đề".
- **Cả 107 đơn "Đã lấy hàng" đều tạo từ tháng 6 và 7/2026**, tức đã rất cũ mà vẫn ghi "Đã lấy hàng". Không nên coi đây là "đang vận chuyển"; nên để chủ shop quyết định hoặc ghi "chưa rõ".
- Giá trị "Giao một phần": "Tick 1 phần", "Đã giao 1 phần", "Không". "Phương thức kết toán": "Thanh toán cuối tháng", "Người nhận thanh toán". "Tiền thu hộ COD" có thể trống (2 dòng).
- 7 file chứa 414 dòng nhưng chỉ 404 mã vận đơn khác nhau, tức **có 10 đơn trùng giữa các file**. Cần gộp theo mã vận đơn.
- Danh sách chọn số dòng mỗi trang: 10, 20, 50, 100, 200, **500**. Ô tìm theo mã vận đơn ghi "tối đa 500 mã". Đây là nguồn của giới hạn 500 dòng mỗi lần.

**Phát hiện mới: file đối soát là nguồn dữ liệu thứ hai.** Ở Dòng tiền, "Lịch sử đối soát" cho tải file mỗi kỳ (tên dạng `<mã khách>-<ngày đầu><ngày cuối>.xlsx`, 13 file thật trong Tải xuống). File này có **16 cột** và hai thứ mà file Đơn hàng không có: **Mã đơn KH** và **Thời gian ký nhận**, cùng Ngày gửi hàng, SĐT, tên, địa chỉ, tiền COD đã ký nhận, các khoản phí, điều chỉnh, ghi chú. Hai dòng đầu file là "Mã khách hàng" và "Mã kỳ đối soát", **dòng tiêu đề nằm ở dòng 3** (khác file Đơn hàng). J&T tự đối soát **3 lần/tuần (thứ 2, 4, 6)**, mỗi kỳ khoảng 2 đến 3 ngày. Nhược điểm: chỉ có đơn nằm trong kỳ đối soát, mỗi file chỉ vài chục dòng; chưa biết có đủ đơn hủy/hoàn hay không.

**Các màn khác đã xem (không có đường tự động):**

- Nút **"Báo cáo"** ở Dòng tiền chỉ mở "Báo cáo chi tiết" với 3 tab: Đối soát tự động, Rút tiền tự động, Yêu cầu rút tiền. Đây là sổ tiền, **không phải xuất danh sách đơn**.
- **Quản lý tài khoản con**: bảng có cột Ghi chú, Sửa COD, Mã PIN, Trạng thái, Ngày tạo, Quyền hạn; hiện chưa có tài khoản nào. Không tạo thử vì sẽ thay đổi dữ liệu J&T.
- **Cấu hình thông báo đẩy**: chỉ là thông báo trong trình duyệt (Báo cáo, Đơn hàng, Membership, Thông báo, Chat), đang tắt hết. Không phải webhook hay email.
- **Cấu hình cột** chỉ cho chọn cột hiển thị trên bảng (có thêm "Mã đơn KH", "Số lần in"...). **Chưa biết** file Xuất có theo cấu hình này không (file xuất mẫu có cả SĐT, Phương thức kết toán mà bảng không hiện đủ). Chưa thử vì bấm sẽ lưu thay đổi.
- **Điều khoản và chính sách** trong cổng là hợp đồng dịch vụ chuyển phát. Tìm theo từ khóa không thấy điều khoản nào về robot, truy cập tự động hay chia sẻ tài khoản. Điều đó **không có nghĩa là được phép**, chỉ là chưa thấy cấm rõ trong phần này.

## 2. Tích hợp chính thức của J&T Việt Nam

| Hạng mục | Kết quả | Mức chắc chắn |
|---|---|---|
| Tài liệu API `api-docs.jtexpress.vn` | **Hôm nay tên miền không phân giải** (Google DNS báo "Non-existent domain", curl và Edge của người dùng đều lỗi). Công cụ tìm kiếm vẫn còn đánh chỉ mục các trang cũ. Ghi chú trong BAN-GIAO cho rằng đã kiểm chứng trang này; cần sửa thành "trang đã ngừng hoặc đổi địa chỉ" | Chắc (đã thử 3 cách) |
| Các API được liệt kê trong kết quả tìm kiếm | Tạo đơn, Hủy đơn, Tra hành trình, Tra giá tiền, Mã hóa API (ký bằng `data_digest`, MD5 rồi Base64, cần `customerid` và `eccompanyid` do J&T cấp) | Trung bình (chỉ đọc được đoạn trích, không đọc được trang) |
| API liệt kê đơn theo thời gian | **Không thấy** | Trung bình |
| Cách có quyền API | Phải **ký hợp đồng** với J&T rồi nhận mã khách hàng và khóa (Fchat, Sapo nêu rõ) | Khá chắc (2 nguồn đối tác) |
| Webhook trạng thái | Có, nhưng **đăng ký qua nhân viên sale hoặc bưu cục đã ký hợp đồng** (gửi link webhook cho họ). Chỉ thấy áp dụng cho đơn đẩy qua API/đối tác | Khá chắc (Fchat) |
| Đối tác đã tích hợp sẵn | Pancake, Sapo, MISA eShop, Fchat, Goship, TrustSales: shop nhập Mã khách hàng, **tạo đơn từ nền tảng đó**. Không thấy nói đến việc kéo về các đơn **đã tạo trên web/app J&T** | Khá chắc |
| Xuất tự động, gửi email báo cáo hằng ngày | Không thấy trong cổng khách hàng | Chắc cho cổng; chưa hỏi J&T |
| Tổng đài | 1900 1088, cskh@jtexpress.vn | Từ trang chính thức |

**Kết luận mục 2:** J&T có API và webhook chính thức, nhưng thiết kế cho shop **tạo đơn từ hệ thống của mình** rồi theo dõi hành trình. Cửa hàng này tạo đơn trên web/app J&T, nên các API trên **không kéo được danh sách đơn đã có kèm SĐT người nhận**. Chưa có bằng chứng J&T cung cấp đường này; chỉ J&T mới trả lời được.

## 3. Nếu vẫn muốn giữ phiên đăng nhập J&T trên máy chủ

**Điều đã quan sát về phiên đăng nhập (không ghi giá trị nào):**

- Cổng web lưu một chuỗi đăng nhập JWT trong bộ nhớ trình duyệt (khóa `YL_TOKEN`, có các trường iss, id, exp, uuid) và gọi một API nội bộ không có tài liệu (`ylvipapi.jtexpress.vn`). Mình **không gọi trực tiếp** API này.
- **Hạn của chuỗi này cố định**: hết hạn lúc 06:13 UTC ngày 22/11 và **không dài thêm** sau khoảng 10 phút thao tác liên tục. Không thấy khóa làm mới (refresh). Giờ cấp không có sẵn; nếu cấp lúc người dùng đăng nhập (khoảng 13:13 giờ VN) thì hạn là **khoảng 24 giờ**. Đây là **suy luận**, chưa kiểm chứng. Hệ quả: phiên lưu lại sẽ chết sau khoảng 1 ngày và phải đăng nhập lại bằng người thật.
- Đăng nhập có **OTP qua email** (người dùng cho biết) và có dấu hiệu **captcha** (khóa `TDC_itoken`, `captcha_webworker_supported`). Trang cũng gắn công cụ theo dõi thiết bị (Bonree). Tức J&T có chủ đích chống đăng nhập tự động.
- Tài khoản này gắn **CCCD, tài khoản ngân hàng, rút tiền, COD**. Bị khóa hoặc lộ phiên là thiệt hại tiền thật, không chỉ mất tiện lợi.

**Repo GitHub liên quan** (số liệu lấy từ GitHub ngày 21/11/2026; chưa có repo nào dành riêng cho cổng J&T Việt Nam, đã tìm theo "jtexpress", "khachhang.jtexpress.vn"; chỉ ra toàn repo không liên quan, 0 sao):

| Repo | Sao | Giấy phép | Còn cập nhật | Dùng để làm gì | Độ phù hợp và rủi ro |
|---|---|---|---|---|---|
| [microsoft/playwright](https://github.com/microsoft/playwright) | 100k | Apache-2.0 | 21/11/2026 | `storageState` lưu phiên, `launchPersistentContext` giữ hồ sơ trình duyệt | Công cụ nền tốt nhất, nhưng **không giải quyết OTP/captcha và hạn 1 ngày**. Tài liệu Playwright khuyên không đưa file phiên vào git |
| [microsoft/playwright-mcp](https://github.com/microsoft/playwright-mcp) | 40k | Apache-2.0 | 18/11/2026 | Có chế độ dùng hồ sơ riêng và chế độ nối vào trình duyệt thật của người dùng (theo tài liệu, chưa thử) | Hợp cho kiểu "người đăng nhập, máy làm tiếp" |
| [puppeteer/puppeteer](https://github.com/puppeteer/puppeteer) | 96k | Apache-2.0 | 20/11/2026 | `userDataDir` giữ hồ sơ | Tương đương Playwright |
| [easychen/CookieCloud](https://github.com/easychen/CookieCloud) | 3,2k | GPL-3.0 | 13/11/2026 | Tiện ích trình duyệt đồng bộ **cookie và localStorage** lên máy chủ tự dựng, mã hóa đầu cuối, theo chu kỳ | **Khớp nhất về kỹ thuật** vì chuỗi đăng nhập J&T nằm trong localStorage. Nhưng máy chủ nắm được phiên đầy đủ của tài khoản có tiền, và phiên vẫn chết sau khoảng 1 ngày. Cần đồng ý rõ của người dùng. GPL-3.0: chạy như dịch vụ riêng thì không ảnh hưởng mã Adh |
| [m1k1o/neko](https://github.com/m1k1o/neko) | 22,7k | Apache-2.0 | 18/11/2026 | Trình duyệt ảo chạy trong Docker, xem qua web (WebRTC), giữ hồ sơ | Người thật đăng nhập OTP từ xa mỗi ngày trong trình duyệt của máy chủ. Ít "robot" nhất nhưng vẫn là người làm thủ công hằng ngày |
| [linuxserver/docker-chromium](https://github.com/linuxserver/docker-chromium) | 414 | GPL-3.0 | 16/11/2026 | Chromium trong container, mở qua web, giữ hồ sơ | Giống neko nhưng đơn giản và nhỏ hơn |
| [steel-dev/steel-browser](https://github.com/steel-dev/steel-browser) | 8k | Apache-2.0 | 21/11/2026 | API trình duyệt tự dựng, có quản lý phiên | Mạnh hơn nhu cầu; thêm phần phải vận hành |
| [browserless/browserless](https://github.com/browserless/browserless) | 14k | Không rõ ràng ("NOASSERTION": giấy phép kép, mô tả ghi miễn phí cho phi thương mại) | 20/11/2026 | Trình duyệt headless trong Docker | **Cửa hàng là dùng thương mại**, phải đọc kỹ giấy phép trước |
| [browser-use/browser-use](https://github.com/browser-use/browser-use) | 122k | MIT | 21/11/2026 | Tác tử AI điều khiển trình duyệt, có giữ hồ sơ | Tốn phí mô hình, kết quả không ổn định, không hợp việc xuất file đều đặn |
| [daijro/camoufox](https://github.com/daijro/camoufox), [Kaliiiiiiiiii-Vinyzu/patchright](https://github.com/Kaliiiiiiiiii-Vinyzu/patchright), SeleniumBase (chế độ UC) | 13,5k / 5,2k / 13k | MPL-2.0 / Apache-2.0 / MIT | còn hoạt động | Trình duyệt "ẩn danh", né phát hiện robot | **Không khuyến nghị**: mục đích chính là né chống robot, mà J&T có captcha. Dùng để vượt captcha của tài khoản có tiền là hướng sai |

## 4. Các lựa chọn và rủi ro

| | Lựa chọn | Tự động mức nào | Rủi ro | Trái BAN-GIAO? |
|---|---|---|---|---|
| **A** | Trang kéo thả hoặc thư mục dùng chung (Google Drive/OneDrive) + nhắc mỗi sáng; nhập cả file Đơn hàng và file Đối soát | Bán tự động: mẹ đăng nhập, bấm Xuất, thả file | Gần như không có | Không |
| **B** | Hỏi J&T (sale/bưu cục, 1900 1088): có **gửi email báo cáo đơn hằng ngày** hoặc **API xem danh sách đơn kèm SĐT** cho tài khoản này không; điều kiện và phí | Tự động hoàn toàn nếu J&T đồng ý | Chỉ tốn thời gian chờ; có thể bị từ chối | Không (BAN-GIAO đã đề xuất) |
| **C** | Nền tảng đối tác tạo đơn (Pancake, Sapo, Fchat...) hoặc để **Adh tự tạo đơn** qua API | Tự động cho đơn tạo từ đó | Đổi cách làm việc của bố mẹ, tốn hợp đồng và phí | Không, nhưng là thay đổi lớn |
| **D** | Phiên người thật + đồng bộ (neko, hoặc CookieCloud + Playwright): người đăng nhập OTP mỗi sáng, máy tự xuất file | Bán tự động có người trong vòng | Trung bình đến cao: máy chủ giữ phiên của tài khoản có tiền; có thể vi phạm điều khoản chưa đọc hết; hỏng khi J&T đổi giao diện | **Có**: BAN-GIAO viết "không đăng nhập J&T tự động" |
| **E** | Bot đăng nhập hoàn toàn (mật khẩu + đọc OTP email + né captcha) | Hoàn toàn | **Cao**: khóa tài khoản, lộ tiền, vi phạm điều khoản | **Có**, và **không khuyến nghị** |

## 5. Đề xuất

1. **Làm A ngay**: cổng nạp chung `ShipmentIngest`, nhập nhiều file, gộp theo mã vận đơn (khóa hãng + mã vận đơn, lấy bản mới), lấy tỉnh từ **phần cuối** của "Địa chỉ hành chính", đọc cả hai loại file (Đơn hàng: tiêu đề dòng 1; Đối soát: tiêu đề dòng 3), thêm trạng thái "Kiện vấn đề", không tự coi "Đã lấy hàng" của đơn cũ là đang vận chuyển.
2. **Hỏi J&T (B) song song**, vì chỉ họ mở được đường tự động chính thức: có gửi email báo cáo đơn hằng ngày không, có API xem danh sách đơn tạo trên web VIP kèm SĐT không, điều kiện và phí. Mình có thể soạn sẵn nội dung câu hỏi.
3. **Chỉ khi** người dùng muốn nhiều tự động hơn A và B không được: cân nhắc **D** với kiểu "mẹ đăng nhập OTP một lần mỗi sáng, máy làm phần xuất file". Đây là **đề xuất trái hướng BAN-GIAO**, cần người dùng đồng ý rõ ràng bằng lời, và tuyệt đối không để phiên hoặc mật khẩu trong repo, log hay chat.
4. **Không làm E.**

## 6. Còn chưa biết

- Hạn thật của phiên đăng nhập (suy luận khoảng 24 giờ).
- Nút Xuất có theo "Cấu hình cột" không, và xuất tối đa bao nhiêu khi chọn hơn một trang.
- File Đối soát có đủ đơn hủy/hoàn không.
- Nội dung chính xác tài liệu API hiện hành (tên miền cũ đã chết).
- J&T có đồng ý gửi báo cáo hoặc mở API cho tài khoản này không.

## 7. Dọn dẹp cần người dùng quyết định

Thư mục Tải xuống của máy đang có khoảng 20 file J&T chứa **tên, SĐT, địa chỉ thật của khách** (7 file "Data ... ĐƠN HÀNG.xlsx", trong đó 1 file tạo ở lần quan sát trước lúc 22:19, và 13 file đối soát `<mã khách>-<ngày>.xlsx`). Không đưa các file này lên repo. Sau khi đã nhập vào Adh, nên xóa hoặc chuyển vào nơi riêng tư.
