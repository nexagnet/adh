# Thiết kế: trợ lý AI tự trả lời khách nhắn riêng (D20)

Ngày 23/11/2026, chủ dự án chốt: "tôi muốn AI tự sinh câu trả lời". Khách tự nhắn riêng thì trả lời hết, không giới hạn số câu; câu hỏi ngoài việc **mời vào nhóm** và **ưu đãi khi vào nhóm** thì chuyển người thật. Phạm vi này là hết giai đoạn 1 (máy 2 GB RAM).

## Đường đi của một tin
1. Bộ nối đọc tin riêng của người đã nhận Tin 1 hoặc Tin 2 (như D19). Khách đang trong luồng mời (SENT/CLICKED/FRIENDED) hoặc đã vào nhóm (JOINED) và không có cờ từ chối còn hiệu lực. Khách từng từ chối đi đường "khách quay lại", không bao giờ vào đây.
2. AI gắn nhãn (D19): từ chối thì xử lý như cũ (ghi sổ hoặc tạm dừng, không trả lời). `MUON_MUA` và `KHIEU_NAI` chuyển người thật ngay, không gọi AI thêm.
3. Các nhãn còn lại: AI phân loại phạm vi `TRONG`/`NGOAI`. Nghi ngờ là `NGOAI`; cần độ chắc từ 0,8 mới coi là trong phạm vi.
4. AI soạn MỘT câu CHỈ từ **bảng thông tin cửa hàng** (nguồn duy nhất). Nếu bảng không đủ thì AI trả rỗng và việc chuyển người thật.
5. `ReplyValidator` kiểm cứng (không dùng AI kiểm AI), rồi hệ thống thêm chữ ký "Trợ lý AI của <shop>" và cách gặp người thật.
6. Nấc **Chỉ soạn nháp**: lưu nháp, KHÔNG gửi. Nấc **Tự động**: xếp hàng, `ZaloReplySender` gửi qua đường chung (công tắc, khung giờ 08:00–22:00, khoảng nghỉ cố định, cầu dao, phanh khẩn cấp của bộ nối). Mỗi tin đến một câu trả lời; không tự gửi lại khi lỗi không rõ.

## Bộ kiểm cứng
Chặn: câu rỗng hoặc quá 500 ký tự; đường dẫn; số điện thoại, email, chuỗi số dài; **mọi con số không có trong bảng thông tin** (nên không bịa giá, phần trăm, thời hạn); nhắc nền tảng khác ngoài TikTok; hứa hẹn ("cam kết", "đảm bảo", "chắc chắn", tặng quà, mã giảm); đổi trả và hoàn tiền; chê hàng hoặc đối thủ; giả làm người hoặc chủ shop; xin địa chỉ, số điện thoại, mật khẩu, chuyển khoản. Các chữ "bảo hành", "còn hàng", "hết hàng", "tồn kho", "giá bán", "TikTok", đơn vị tiền chỉ được nhắc khi bảng thông tin cũng có chữ đó. Khớp không phân biệt dấu. Câu không qua kiểm thì không bao giờ gửi.

## Bảng thông tin cửa hàng (sửa ở `/zalo/reply`)
Mặc định (chủ shop chốt 23/11): cửa hàng bán chủ yếu **áo điều hòa và phụ kiện áo điều hòa (trong đó có pin)**; ưu đãi vào nhóm là giảm 10% so với giá TikTok và miễn phí ship; lý do: TikTok thu phí sàn nên mua qua Zalo shop nhường lại 10%, TikTok khó mua hơn, liên hệ bảo hành qua Zalo thuận tiện hơn; cách vào nhóm. Ô bảo hành để trống (AI không nói gì cụ thể). Không ghi chính sách đổi trả của TikTok vào bảng; AI không chủ động nhắc và được chỉ dẫn không nói.

## Dữ liệu và an toàn
- Chỉ gửi AI văn bản đã che (`PiiMasker`), không tên, số điện thoại, địa chỉ hay mã Zalo. Riêng bước soạn câu nhận thêm **hồ sơ của đúng khách đang nhắn** (khu vực, hàng đã mua, nhóm, các mốc, lượt trao đổi trước) theo danh sách trường được phép, xem `THIET-KE-AI-NGU-CANH-KHACH.md`; tin của khách là dữ liệu không đáng tin (bọc giữa `<<<` `>>>`, đầu ra phải đúng khuôn JSON).
- Mỗi lượt đi qua `AiGate` (trần ngày, cầu dao). Mỗi tin dùng tối đa 3 lượt (nhãn, phạm vi, soạn); nên đặt `APP_AI_DAILY_LIMIT` đủ rộng.
- Bảng `reply_draft` (V31): chữ khách (đã che) và câu AI soạn tự xóa sau 30 ngày; không lưu số điện thoại. Không bao giờ tự xóa số điện thoại hay khách (CLAUDE.md mục 5a).
- Bộ nối `POST /messages` có thêm loại `REPLY`, cùng chốt chặn với `WELCOME`: chỉ theo mã Zalo, cần cờ đọc tin riêng, chỉ người vừa nhắn trong 24 giờ, một lần cho mỗi tin đến.
- Việc chuyển người thật là **hàng đợi chung** ở trang "Việc cần xử lý" (cột `assigned_to` để dành cho giai đoạn 2). Khách nhắn thêm khi việc đang chờ thì chữ được ghi tiếp vào cùng việc, không gọi AI chen vào.
- Có ô "Thử một câu" ở `/zalo/reply` để chạy chuỗi trên câu giả mà không lưu hay gửi gì. Nên thử vài chục câu (trong và ngoài phạm vi, câu cố lừa AI) trước khi bật.

## Không làm ở giai đoạn 1
AI trả lời trong nhóm Zalo, nhiều người trong đội bán hàng (Chatwoot), tra cứu nhiều sản phẩm, tóm tắt việc.

## Cách đưa tin của khách cho AI và các câu mẫu (24/11/2026, sau chạy thử pilot)

Chạy thử thật cho thấy AI hay hiểu sai câu hỏi chung về ưu đãi ("Ưu đãi như nào") thành "muốn mua" hoặc "ngoài phạm vi". Đã sửa theo cách làm tốt nhất:

- **Mỗi lượt gọi AI có hai phần**: tin hệ thống (vai trò, bối cảnh, định nghĩa nhãn, ví dụ, khuôn JSON) và tin người dùng (chỉ văn bản khách đã che, nằm giữa `<<<` `>>>`). `temperature` 0, chế độ JSON, parser chặt (đúng các khóa, nhãn lạ thì bỏ).
- **Bối cảnh cố định cho AI** (không có dữ liệu cá nhân): khách vừa nhận lời mời vào nhóm có nhắc ưu đãi 10% và miễn ship, nên "ưu đãi" nói chung là ưu đãi khi vào nhóm.
- **Nhãn có thứ tự quyết định và ví dụ đúng/sai**: TU_CHOI, KHIEU_NAI, MUON_MUA (chỉ khi nói rõ muốn mua hoặc hỏi giá/còn hàng một sản phẩm cụ thể), CAU_HOI (hỏi chung về nhóm và ưu đãi, KHÔNG phải MUON_MUA), KHAC. Phạm vi trả lời cũng có hai chủ đề TRONG và danh sách ví dụ TRONG/NGOAI.
- **Nhãn MUON_MUA chỉ chuyển người thật ngay khi AI chắc từ 0,9** (`ReplyService.BUY_HANDOFF_MIN`); dưới đó bước phạm vi quyết, và nếu ngoài phạm vi thì vẫn chuyển người thật với lý do "muốn mua".
- **Không ghép tin trước khi phân loại phạm vi**: bước phạm vi chỉ xét tin MỚI NHẤT (tin cũ trong nháp đã qua bước này). Khi soạn câu, các tin liên tiếp được tách thành "Tin 1, Tin 2 (mới nhất)" thay vì dính bằng dấu `|`. Việc đã chuyển người thật vẫn ghi tiếp tin mới vào cùng việc, không nhờ AI chen vào.
- **Bộ câu mẫu** nằm trong `ReplyTest.boCauMauDuongDiDungKhiAiGanNhanKhacNhau`; dán cùng các câu đó vào ô "Thử một câu" ở `/zalo/reply` để thử với DeepSeek thật trước khi bật nấc Tự động.

## Giọng trả lời và chuyện trợ lý AI luôn nói rõ mình là AI (24/11/2026)

- Prompt soạn câu (`ReplyAssistant.COMPOSE_RULES`) viết lại: giọng như bạn nhân viên bán hàng nhắn Zalo (xưng "em", "Dạ... ạ"), **một câu dưới 120 ký tự (hai câu dưới 200 chỉ khi khách hỏi hai ý)**, chỉ trả lời đúng điều khách hỏi, kèm vài ví dụ về giọng và độ ngắn (nội dung thật vẫn chỉ lấy từ bảng thông tin cửa hàng). Xưng "em", gọi khách là "bác" (chủ dự án chốt 24/11/2026, theo giọng mẫu Tin 1: lễ phép, ấm áp, "mua trực tiếp qua nhóm sẽ rẻ hơn mua qua TikTok và được miễn ship ạ"; không gọi tên vì AI không nhận tên khách). Chữ ký một dòng ngắn: "— Trợ lý AI của shop. Cần người thật, nhắn "gặp người thật"." (không kèm tên shop dài)
- **Cập nhật cùng ngày (chủ dự án chốt): bỏ chữ ký ở mọi câu.** Giới hạn còn lại: AI không bao giờ nhận mình là người/nhân viên/chủ shop (`ReplyValidator`), và khách hỏi thẳng "bạn là AI hay người" thì được trả lời thật là trợ lý AI (câu đó nằm TRONG phạm vi); chưa còn chuyện chuyển người thật im lặng cho câu hỏi này. Phần dưới là lập luận trước khi bỏ chữ ký.
- **Không bao giờ giấu hay chối mình là AI.** Chủ dự án có đề nghị bỏ tiết lộ và để AI trả lời "như người thật"; phần đó KHÔNG làm, vì khách sẽ tin mình đang nói chuyện với người. Giữ nguyên: mọi câu có chữ ký trợ lý AI; `ReplyValidator` chặn "em là người", "em là nhân viên/chủ shop/mẹ"; khách hỏi thẳng "bạn là AI hay người thật" thì rơi vào NGOÀI phạm vi, chuyển người thật (không tự trả lời).
