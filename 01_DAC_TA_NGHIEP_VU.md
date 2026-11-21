# ĐẶC TẢ NGHIỆP VỤ — HỆ THỐNG CHĂM SÓC KHÁCH HÀNG CŨ QUA ZALO

**Phiên bản:** 1.0 — chốt theo trao đổi ngày 21/11/2026  
**Trạng thái:** Đặc tả nghiệp vụ đã thống nhất; chưa triển khai phần mềm, chưa kết nối tài khoản.  
**Đối tượng đọc:** Chủ cửa hàng, bố mẹ vận hành, Claude Code và người phát triển.  
**Phạm vi kinh doanh:** Cửa hàng chuyên áo điều hòa và các phụ kiện liên quan (pin, quạt, linh kiện). Không mở rộng tùy tiện sang ngành hàng khác.

> **Nguyên tắc đọc:** Các mục ghi **ĐÃ CHỐT** là quyết định nghiệp vụ của chủ cửa hàng. Mục ghi **ĐỀ XUẤT / CẦN KIỂM CHỨNG** không được tự động biến thành chức năng bắt buộc. Phần kiến trúc và repo nằm ở file `02_NGHIEN_CUU_KY_THUAT.md`.

## 1. Bối cảnh và mục tiêu

- Cửa hàng là hộ kinh doanh nhỏ, hiện bố mẹ trực tiếp bán hàng, chăm sóc khách, đóng gói và giao hàng. Chưa có nhân viên thuê.
- Ngoài đơn hàng trên TikTok Shop, cửa hàng còn có **đơn bán trực tiếp ngoài nền tảng** và gửi qua **Viettel Post** hoặc **J&T Express**.
- Quy mô dữ liệu ban đầu dự kiến khoảng **500 khách hàng** ở hai đơn vị vận chuyển; con số thực tế sẽ xác định sau khi xuất dữ liệu, gộp trùng.
- Nhiều khách là người lớn tuổi, quen gọi điện hơn sử dụng ứng dụng. Việc tham gia nhóm Zalo phải ít thao tác nhất có thể.
- **Mục tiêu đầu tiên không phải quảng cáo thúc ép mua lại**, mà là mời khách đã từng mua vào những nhóm nhỏ để dễ tìm lại cửa hàng, hỏi cách dùng, được chăm sóc và bảo hành khi cần. Việc khách mua thêm là kết quả mong muốn về dài hạn, không phải điều kiện tham gia.
- Chủ cửa hàng có khả năng lập trình, sẽ xây công cụ cá nhân hóa. Ưu tiên tận dụng mã nguồn có sẵn, một server riêng, triển khai gọn và mở rộng từng giai đoạn.

### Mục tiêu kinh doanh

1. Có **một danh sách khách hàng sạch và lịch sử giao hàng chính xác** từ hai nguồn giao vận.
2. Phân biệt **khách mua thành công đúng 1 lần** và **khách mua thành công từ 2 lần trở lên**.
3. Mời nhóm khách 1 lần vào các nhóm Zalo nhỏ, **khoảng 5 khách/nhóm**.
4. Giữ cách làm việc của bố mẹ như hiện tại trong giai đoạn đầu: **bố mẹ dùng ứng dụng Zalo thật**, nhận cuộc gọi và tự lên đơn như trước.
5. Một tài khoản Zalo riêng được giới thiệu rõ là **trợ lý AI của cửa hàng** để trả lời câu hỏi thông thường, nhận diện nhu cầu đặt hàng và báo cho bố mẹ.
6. Khi tuyển nhân viên đầu tiên mới phát triển trải nghiệm **nhân viên làm việc trên hệ thống riêng**, điều phối theo nhóm Zalo, không buộc khách đổi kênh giao tiếp.

### Không nằm trong dự án hiện tại

- Nghiên cứu/nhập nguồn hàng trên 1688, mua hộ Trung Quốc hoặc quản lý nhà cung cấp.
- Tự động hóa TikTok Shop, đồng bộ toàn bộ bán hàng trên TikTok, quảng cáo sản phẩm hàng loạt.
- Tự động xử lý cuộc gọi điện thoại; mẹ trực tiếp nghe điện thoại trong giai đoạn đầu.
- Tự động chốt đơn, trừ kho, thanh toán hay tạo vận đơn khi chưa có xác nhận của người vận hành.
- Bắt khách xác nhận OTP, gửi mã vào nhóm hoặc cung cấp số điện thoại chỉ để được tham gia nhóm.
- Bắt buộc mỗi nhóm có **đúng** 5 khách hoặc phải ghép được số điện thoại với tài khoản Zalo ngay khi vào nhóm.

## 2. Các quyết định nghiệp vụ ĐÃ CHỐT

| Mã | Nội dung | Quyết định |
|---|---|---|
| BR-01 | Nguồn đơn ngoài TikTok | Viettel Post và J&T Express; thiết kế cho phép thêm nguồn sau này. |
| BR-02 | Chìa khóa phân loại khách | **Số điện thoại nhận hàng** được chuẩn hóa; gộp đơn cả hai nguồn. |
| BR-03 | Số lần mua | Chỉ đếm lần mua **giao thành công, không hoàn trả**; không tính đơn hủy/hoàn. Một đơn tách nhiều kiện không được tự động tính thành nhiều lần mua. |
| BR-04 | Hai danh sách | **1 lần** và **2 lần trở lên**. |
| BR-05 | Chăm sóc khách 1 lần | Đưa vào danh sách dự kiến mời bằng SMS/Zalo, có điều kiện được phép liên hệ. |
| BR-06 | Chăm sóc khách 2+ lần | Chỉ tạo danh sách riêng để bố mẹ chủ động tư vấn; **không tự động cho vào chiến dịch mời nhóm**. |
| BR-07 | Nhóm Zalo | Khoảng **5 khách/nhóm**, lệch một chút được; mục tiêu là nhóm nhỏ, không ép chính xác. |
| BR-08 | Tham gia | Khách tự nguyện bấm lời mời, không bị tự động ép vào nhóm. |
| BR-09 | Nhận diện khi vào nhóm | **Không cần map** số điện thoại ↔ tài khoản Zalo tại thời điểm vào nhóm. |
| BR-10 | Nhận diện khi mua lại | Có thể map sau khi khách đặt đơn và cung cấp số điện thoại nhận hàng. |
| BR-11 | Tài khoản trong nhóm | Tài khoản **mẹ** làm đại diện/trưởng nhóm; tài khoản **trợ lý AI** riêng tham gia và nhắn tin công khai dưới tên bot. |
| BR-12 | Hành vi bot | Trả lời câu hỏi thông thường; khi thấy dấu hiệu muốn đặt hàng hoặc cần người xử lý thì thông báo, **không tự tuyên bố đã chốt đơn**. |
| BR-13 | Vận hành V1 | **Bố mẹ tự trả lời trên Zalo thật**; chưa có dashboard nhân viên trả lời hội thoại. |
| BR-14 | Vận hành giai đoạn có nhân viên | Nhân viên nhận nhiệm vụ theo **nhóm Zalo** trên hệ thống riêng; AI hỗ trợ, có thể bàn giao khi cần. |
| BR-15 | Điện thoại | Mẹ tiếp nhận như hiện tại; tự động chuyển cuộc gọi **ngoài phạm vi V1**. |
| BR-16 | Nội dung lời mời | Nhằm kết nối/chăm sóc khách đã mua, **không dùng làm chiến dịch quảng cáo sản phẩm**. |

## 3. Luồng nghiệp vụ tổng thể

```text
Viettel Post ──┐
               ├─> Nhập dữ liệu ─> Làm sạch & gộp theo SĐT ─> Đếm lần mua thành công
J&T Express ──┘                                          │
                                      ┌──────────────────┴───────────────────┐
                                      ▼                                      ▼
                              ĐÚNG 1 LẦN                              TỪ 2 LẦN
                              Danh sách mời                          Danh sách chăm
                                      │                               sóc riêng cho
                            Kiểm tra quyền liên hệ                      bố mẹ
                                      │
                            Gửi lời mời SMS/Zalo
                                      │
                          Khách tự nguyện bấm link
                                      │
                    Phân phối vào nhóm Zalo ~5 khách
                                      │
                         Mẹ + tài khoản bot AI
                                      │
                            Hỏi đáp / hỗ trợ
                                      │
                           Phát hiện muốn mua?
                              │               │
                             Không            Có
                              │               │
                         AI hỗ trợ       Báo mẹ xử lý
                                      (giai đoạn sau:
                                      báo nhân viên)
                                              │
                                        Chốt đơn thủ công
                                              │
                                        Giao hàng như cũ
                                              │
                                       Cập nhật lịch sử;
                                      map tài khoản khi cần
```

## 4. Nghiệp vụ A — Thu thập và hợp nhất đơn hàng

**Đầu vào:** File dữ liệu đơn/vận đơn do người bán xuất từ tài khoản **Viettel Post** và **J&T Express**. Ban đầu ưu tiên Excel/CSV; tích hợp API tự động là nâng cấp, không được làm chậm V1.

**Dữ liệu tối thiểu cần tìm trong tệp:** đơn vị vận chuyển, mã vận đơn, số điện thoại nhận, tên người nhận, địa chỉ, ngày tạo/ngày giao, trạng thái giao hàng; nếu có thì thêm mã đơn bán hàng, sản phẩm, số lượng, tổng tiền/COD.

**Quy tắc:**

- Chỉ nhập dữ liệu của chính cửa hàng có quyền khai thác; đơn TikTok Shop không thuộc phạm vi hai nguồn đơn ngoài lần này.
- Chuẩn hóa số điện thoại Việt Nam (ví dụ `+84...`, `84...`, `0...` → cùng một định dạng), nhưng **không tự suy diễn số thiếu/sai**. Giữ giá trị gốc để đối chiếu.
- Gộp khách từ hai hãng vận chuyển **theo số điện thoại đã chuẩn hóa**. Theo nghiệp vụ, đây là **định danh liên hệ**, không tuyên bố chắc chắn các số đó là một người thực ngoài đời.
- Chống nhập trùng theo nguồn + mã vận đơn; khi nhập file cũ lần nữa không làm tăng lần mua.
- Mọi trạng thái gốc của hãng giao hàng phải được giữ lại. Cần có bảng ánh xạ trạng thái theo từng hãng (giao thành công, đang giao, hủy, hoàn, không xác định).
- Chỉ tính **lần mua thành công** sau khi trạng thái đã đủ chắc chắn; nếu có biến cố hoàn sau khi giao thì cập nhật lại.
- Nếu hai mã vận đơn thuộc **cùng một đơn bán hàng**, tính **1 lần mua**. Nếu không có mã đơn gốc để xác định, đưa vào danh sách cần kiểm tra, không cố gộp theo ngày/giá trị bằng phỏng đoán.
- Nếu tệp thiếu số điện thoại hoặc trạng thái, đưa vào danh sách lỗi/cần kiểm tra; không để dữ liệu lỗi được đưa vào chiến dịch gửi tin.

**Đầu ra:** Danh sách khách duy nhất theo số điện thoại, chi tiết đơn hàng, số lần mua thành công, trạng thái phân loại, lỗi nhập liệu và ngày đồng bộ gần nhất.

## 5. Nghiệp vụ B — Phân loại khách phục vụ chăm sóc

| Điều kiện | Phân loại | Hành động |
|---|---|---|
| 0 lần giao thành công | Chưa đủ điều kiện | Không nằm trong hai danh sách khách đã mua; cần kiểm tra nếu có lỗi dữ liệu. |
| 1 lần giao thành công | `ONE_TIME` | Danh sách dự kiến mời vào nhóm Zalo. |
| ≥ 2 lần giao thành công | `REPEAT` | Danh sách tư vấn riêng cho bố mẹ, không tự động gửi lời mời nhóm. |

- Phân loại có thể thay đổi khi nhập thêm các đợt dữ liệu sau này.
- Một khách `ONE_TIME` mua thêm thành công thì chuyển sang `REPEAT`; **không tự động đuổi họ khỏi nhóm Zalo cũ**.
- Dữ liệu phân loại và **trạng thái lời mời** là hai yếu tố khác nhau: khách `ONE_TIME` chưa chắc đã được phép gửi lời mời, hoặc đã nhận, đã bấm, đã vào nhóm.

## 6. Nghiệp vụ C — Mời khách vào Zalo và chia nhóm nhỏ

1. Người vận hành duyệt **danh sách đủ điều kiện liên hệ**. Nếu khách chưa đồng ý nhận lời mời/tiếp thị, không mặc định dùng số điện thoại thu thập từ giao hàng để gửi hàng loạt. Cần quy trình xác lập cơ sở liên hệ hợp lệ và ghi nhận từ chối.
2. Hệ thống chuẩn bị lời mời thân thiện, dễ hiểu cho khách lớn tuổi: cửa hàng nào, vì sao nhắn, một đường link dễ bấm, không yêu cầu OTP hay thao tác phức tạp.
3. Mỗi lời mời có mã/link riêng cho mục đích **theo dõi việc gửi và bấm link**; sau khi mở có thể dẫn tới link Zalo của nhóm phù hợp. **Không mặc định rằng người bấm link đã vào nhóm.**
4. Khách tự bấm và lựa chọn tham gia. **Không cần ghép tài khoản Zalo với số điện thoại** tại thời điểm này.
5. Hệ thống ưu tiên phân phối đến nhóm có ít khách, tránh dồn tất cả vào một nhóm. Mỗi nhóm **mục tiêu khoảng 5 khách**, có thể lệch do lời mời đến trễ/khách rời nhóm.
6. Có thể gửi theo lô nhỏ và chờ quan sát tỷ lệ tham gia, sau đó bổ sung lời mời. Người vận hành có thể tạm dừng tất cả chiến dịch bất cứ lúc nào.
7. Cần phân biệt **số khách trong nhóm** và **tổng thành viên**: khoảng 5 khách + tài khoản mẹ + tài khoản AI, tức thường khoảng 7 tài khoản nếu cả hai tài khoản cửa hàng đều có mặt.
8. Nếu một khách không có Zalo, không biết thao tác hoặc không muốn vào nhóm, họ vẫn mua hàng/gọi điện bình thường. Không nhắn thúc ép lặp lại.

**Không đòi hỏi:** Map chính xác Zalo ID ↔ số điện thoại ở bước mời; nhóm đúng 5 người tuyệt đối; đo ai đã tham gia chỉ bằng lượt click.

**Lưu ý chăm sóc:** Nhóm nhỏ nhằm giúp trao đổi thuận tiện. Không lấy việc giảm số người thấy phản ánh tiêu cực làm lý do trì hoãn hoặc che giấu khiếu nại. Nên có cách hỗ trợ riêng khi khách cần chia sẻ địa chỉ/số điện thoại.

## 7. Nghiệp vụ D — Tài khoản mẹ và tài khoản trợ lý AI

**Thành phần trong mỗi nhóm:**

- **Mẹ:** Tài khoản Zalo cá nhân thật, giữ vai trò đại diện/trưởng nhóm. Mẹ tiếp tục đọc, trả lời và nhận đơn trên ứng dụng Zalo hiện có.
- **Trợ lý AI:** Tài khoản Zalo **riêng**; đặt tên rõ là “Trợ lý cửa hàng ...”, không giả vờ là mẹ. Bot là thành viên nhóm; không bắt buộc là phó nhóm.
- **Khách:** Mục tiêu khoảng 5 tài khoản khách hàng.

**AI chỉ trả lời những tình huống an toàn, có dữ liệu đáng tin:** cách sử dụng, chính sách do cửa hàng cung cấp, thông số sản phẩm đã được xác nhận, hướng dẫn liên hệ hỗ trợ. Nếu thiếu dữ liệu, bot hỏi lại hoặc chuyển người thật; không tự bịa giá, tồn kho, bảo hành.

**Tín hiệu chuyển người thật:**

- Khách muốn đặt thêm hàng, hỏi chốt mua, xác nhận số lượng/địa chỉ.
- Khiếu nại, hàng lỗi, đổi/trả, tranh chấp, khách không hài lòng.
- Khách yêu cầu gọi điện/gặp người thật hoặc bot không tự tin về câu trả lời.

**Ở V1:** bot chỉ **thông báo cho bố mẹ** (qua kênh vận hành đã thống nhất khi triển khai) rằng “nhóm X có khách cần hỗ trợ”, kèm ngữ cảnh tối thiểu. Bố mẹ tự vào Zalo xử lý. Bot ngừng chen vào cuộc trao đổi đang được bố mẹ xử lý, tránh trả lời trùng.

**Khi khách đặt lại:** bố mẹ/nhân viên ghi số điện thoại nhận hàng như khi bán bình thường. Nếu cần, hệ thống liên kết cuộc hội thoại với số điện thoại lúc này; **không yêu cầu khách thực hiện bước xác minh riêng ngay khi vào nhóm**.

## 8. Nghiệp vụ E — Chăm sóc khách mua nhiều lần

- Duy trì một **danh sách riêng** các số điện thoại có ít nhất 2 lần mua thành công, kèm tên, địa chỉ, số đơn thành công, lần mua gần nhất.
- Danh sách để **bố mẹ chủ động tư vấn riêng** theo thói quen bán hàng hiện tại (gọi điện hoặc cách khách đồng ý), **không tự động mời vào nhóm Zalo và không chạy quảng cáo hàng loạt**.
- Có thể ghi nhận ghi chú thủ công nếu bố mẹ muốn, nhưng không cần xây CRM phức tạp ở V1.

## 9. Các giai đoạn triển khai

### V1 — CHỈ CÓ BỐ MẸ (triển khai ngay)

**Bắt buộc / ưu tiên cao:**

- Nhập Excel/CSV từ Viettel Post và J&T Express; xem báo cáo nhập, lỗi, trùng.
- Gộp theo số điện thoại; phân loại `ONE_TIME` và `REPEAT`; xuất/xem hai danh sách.
- Danh sách lời mời với trạng thái đồng ý/từ chối/chưa rõ; chuẩn bị nhóm và quản lý link; mời bằng SMS/Zalo khi đủ điều kiện liên hệ.
- Phân phối nhóm khoảng 5 khách, có thể chỉnh tay; theo dõi số khách nhóm có trong thực tế khi đã có dữ liệu/kiểm đếm.
- Mẹ tiếp tục vận hành Zalo **trên điện thoại hoặc ứng dụng hiện tại**, không cần đăng nhập web quản trị để trả lời khách.

**Bot AI V1 — triển khai sau khi xác minh tính khả thi Zalo cá nhân:**

- Tài khoản bot riêng trong nhóm, nhận tin/đáp tin cơ bản, nhận diện “muốn mua / cần người thật” và báo bố mẹ.
- Khi bot hỏng hoặc kết nối ngắt: **không ảnh hưởng việc mẹ tự trả lời Zalo**, có nút tắt bot.

**V1 không xây:** giao diện hội thoại cho nhân viên, chia ca, tổng đài, chốt đơn tự động, tích hợp TikTok, kho/ERP đầy đủ.

### V2 — BẮT ĐẦU CÓ NHÂN VIÊN ĐẦU TIÊN (chưa làm ngay)

- Nhân viên đăng nhập **website nội bộ** để nhận nhiệm vụ và trả lời các nhóm được phân công.
- **Phân công theo nhóm Zalo**, không cần phân công từng khách trong một nhóm.
- AI xử lý câu hỏi thường gặp; khi nhận diện nhu cầu đặt hàng/ca khó, mở một nhiệm vụ cho nhóm và báo nhân viên phụ trách.
- Nhân viên tiếp quản, ghi nhận thông tin đơn; bố mẹ tiếp tục quyết định hoặc thực hiện đóng gói/vận chuyển tùy cách tổ chức lúc đó.
- Hệ thống ngăn trùng câu trả lời và nhiệm vụ; cho phép chuyển nhóm khi nhân viên nghỉ/quá tải.
- Vẫn có tài khoản mẹ và tài khoản AI riêng; **cần chốt trong V2 nhân viên sẽ trả lời qua tài khoản nào** (mẹ hay bot), chưa coi đây là quyết định hoàn tất.

### V3 — CÓ THỂ CÂN NHẮC SAU

- Tổng đài định tuyến cuộc gọi, báo cáo hiệu quả nhân viên, tích hợp API giao vận tự động, CRM nâng cao, kênh bán khác. **Không thuộc phạm vi cần xây hiện tại.**

## 10. Ngoại lệ vận hành cần xử lý nhưng chưa chốt chi tiết

- Đơn giao thành công rồi đổi trạng thái hoàn; hai nguồn ghi khác mã vận đơn; một đơn tách kiện.
- Số điện thoại sai/đã đổi chủ, hai người dùng chung số, khách có nhiều số; không gộp thiếu chứng cứ.
- Khách chưa đồng ý nhận lời mời, từ chối, yêu cầu ngừng liên hệ, SMS không phát được.
- Khách bấm link nhưng không vào Zalo; vào rồi rời; chia sẻ link cho người ngoài; nhóm tạm đầy.
- Tài khoản bot mất kết nối hoặc AI phản hồi sai; bảo đảm mẹ vẫn vận hành được.
- Tin nhắn nhóm có thông tin riêng tư; khách khiếu nại hoặc cần trả hàng: xử lý tế nhị và minh bạch.

Những tình huống trên là **ràng buộc chất lượng/điểm kiểm tra**, không phải yêu cầu xây ngay một hệ thống quản trị đồ sộ.

## 11. Chỉ số theo dõi, không biến thành KPI cưỡng ép khách

- Số đơn nhập và số đơn giao thành công theo từng đơn vị vận chuyển.
- Số điện thoại khách duy nhất; số khách `ONE_TIME`, số khách `REPEAT`.
- Số khách đủ điều kiện liên hệ; số lời mời đã gửi/thất bại/từ chối.
- Số lượt bấm link (chỉ là **lượt bấm**, không phải người đã vào nhóm).
- Số nhóm đang hoạt động; số khách ước lượng/thực tế mỗi nhóm.
- Số yêu cầu AI chuyển mẹ xử lý; số trường hợp AI lỗi/không trả lời.
- Về sau: số đơn mua lại và chi phí cho mỗi khách quay lại, nếu có dữ liệu đủ chính xác.

## 12. Tiêu chí nghiệm thu V1 theo nghiệp vụ

1. Nhập cùng một file hai lần **không làm tăng số lần mua**.
2. Cùng số điện thoại có 1 đơn thành công ở Viettel Post + 1 đơn thành công ở J&T → **`REPEAT`**.
3. Có 1 đơn giao thành công + 2 đơn hủy/hoàn → **`ONE_TIME`**.
4. Nếu trạng thái chưa thể xác định → **không tự coi là thành công**.
5. Có thể xem/xuất hai danh sách khách riêng và kiểm tra từng đơn tạo ra kết quả phân loại.
6. Chỉ khách `ONE_TIME` **đủ điều kiện liên hệ** mới nằm trong danh sách gửi lời mời; khách `REPEAT` chỉ có danh sách tư vấn riêng.
7. Khách có thể vào nhóm chỉ bằng link; **không phải nhập OTP, không phải khai lại số điện thoại**.
8. Theo dõi nhóm mục tiêu khoảng 5 khách; không yêu cầu đúng 5 tuyệt đối; không nhầm 2 tài khoản cửa hàng thành khách.
9. Mẹ tiếp tục dùng Zalo thật, không phụ thuộc website quản trị để trả lời và lên đơn.
10. Nếu bot đã được bật: bot công khai danh tính, trả lời FAQ đã xác nhận; phát hiện đặt hàng chỉ **báo người thật**, không tự chốt.

## 13. Quy tắc dành cho Claude Code

1. **Không thay đổi nghiệp vụ đã chốt** bằng suy đoán của AI. Khi có mâu thuẫn, báo và hỏi ngắn gọn.
2. Đọc file kỹ thuật `02_NGHIEN_CUU_KY_THUAT.md` để chọn phương án triển khai; mọi tính năng chưa xác minh phải làm POC trước.
3. **V1 trước**, không viết đầy đủ kiến trúc nhân viên V2 hoặc tổng đài V3 chỉ vì muốn “chuẩn doanh nghiệp”.
4. Không gửi SMS thật, không tạo/thêm nhóm thật, không đăng nhập Zalo thật, không xử lý danh sách khách thật cho đến khi chủ cửa hàng cấu hình và cho phép.
5. Dùng dữ liệu giả trong phát triển; kiểm tra riêng về quyền xử lý dữ liệu khách và sự đồng ý nhận lời mời trước khi gửi SMS.
6. Đề xuất kế hoạch theo mốc nghiệm thu nhỏ; mỗi mốc có hướng dẫn chạy và kiểm chứng để chủ cửa hàng thử được.

---

**Kết luận nghiệp vụ:** Đây là hệ thống **hợp nhất dữ liệu đơn ngoài → phân loại hai tập khách → mời có chọn lọc vào nhóm Zalo nhỏ → chăm sóc bởi mẹ và một tài khoản AI riêng**, mở rộng cho nhân viên **chỉ khi tuyển người**. Không biến nó thành phần mềm quản lý bán lẻ toàn diện ở V1.
