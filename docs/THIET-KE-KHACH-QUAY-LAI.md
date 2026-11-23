# Thiết kế: khách từng từ chối nay tự nhắn lại muốn mua (D19, chủ shop chốt 23/11/2026)

## Quy tắc gốc
- **Không bao giờ tự xóa số điện thoại hay bản ghi khách.** Khách từ chối chỉ là cờ "không nhắn nữa"; sổ từ chối (mã băm) chạy song song.
- Gỡ cờ **không xóa dòng sổ từ chối**: đánh dấu `lifted_at` (giờ gỡ) và `lifted_by` (ai gỡ). Mọi chỗ chặn (`ContactService`, `CustomerService`, nhập đối soát) chỉ xét dòng **còn hiệu lực** (`lifted_at IS NULL`). Khách từ chối lần nữa thì dòng cũ được kích hoạt lại, không tạo dòng mới. Dòng `ERASED` (khách yêu cầu xóa dữ liệu) **không bao giờ** gỡ được.
- Chỉ khi **chính khách nhắn trước**. Hệ thống không bao giờ nhắn người chưa nhắn.

## Luồng
1. Bộ nối giữ tin riêng của người đã nhận Tin 1/Tin 2 (D19). Adh (`ZaloInboxService`) đọc từng tin. Nếu người đó còn lời mời đang chạy thì làm như cũ (nhận diện từ chối). Nếu **không** còn lời mời đang chạy mà là khách từng từ chối (lời mời `CANCELLED` và cờ OPT_OUT còn hiệu lực) thì vào phần khách quay lại.
2. Ba nấc (`/zalo`, mặc định **Tắt**): **Tắt** (không gọi AI cho người từng từ chối) / **Chỉ tạo việc** / **Tự động**. Nấc Tự động chỉ chọn được sau khi đã chạy nấc Chỉ tạo việc và tick xác nhận đã xem các việc.
3. AI (cùng bộ gắn nhãn, chỉ nhận văn bản đã che) cho nhãn và độ chắc:
   - `TU_CHOI`, `KHAC`: không làm gì, giữ cờ, không trả lời.
   - `CAU_HOI`, `KHIEU_NAI`: việc nổi bật cho mẹ.
   - `MUON_MUA`: nấc Chỉ tạo việc thì tạo việc; nấc Tự động và độ chắc từ 0,85 (`APP_ZALO_INBOX_COMEBACKTHRESHOLD`) thì tự gỡ; dưới ngưỡng, AI lỗi, sai khuôn thì tạo việc.
4. **Tự gỡ** (một giao dịch, lỗi bất kỳ thì quay lại như cũ và thành việc cho mẹ): đánh dấu dòng sổ đã gỡ, cơ sở liên hệ thành `OPT_IN/ELIGIBLE`, lời mời `FRIENDED` + `zalo_add_state = QUEUED` (đi vào **đường ống thêm nhóm sẵn có**: chọn nhóm theo khu vực, hạn mức, cầu dao, Tin 2 khi thêm lỗi), kiểm lại `stillEligible` (chỉ khách mua một lần, không cần kiểm tra). Chỉ khi bộ nối đang ở nấc cho phép thêm nhóm. **Tối đa một lần tự gỡ cho một khách trong 30 ngày** (tính cả lần đã hoàn tác).
5. **Tin mẫu** (`ZaloWelcomeSender`, loại tin thứ ba `WELCOME`): một tin cố định, không do AI viết, nói rõ là trợ lý tự động và cho đường từ chối. Gửi qua `ZaloMessageService` (chung công tắc, khung giờ 08:00–20:00, hạn mức ngày, khoảng nghỉ, cầu dao), mỗi nhịp tối đa một tin, khóa trước khi gọi, không tự thử lại khi lỗi. Quá 23 giờ kể từ lúc khách nhắn thì bỏ. Cầu nối còn chặn thêm ở phía nó (xem `docs/ZALO-BRIDGE-API.md`). Mẹ gỡ tay thì **không** có tin mẫu (mẹ tự nhắn trên Zalo).
6. **Mẹ** (`/handoffs`): việc nổi bật kèm tối đa 3 đơn gần nhất (ngày, hàng hóa, trạng thái) và trích đoạn đã che; nút "Gỡ không nhắn nữa, cho vào nhóm" (cùng kiểm tra điều kiện), "Giữ nguyên". Mục "Khách quay lại đã gỡ cờ (14 ngày)" có nút **Hoàn tác**: đánh dấu cờ hiệu lực lại, đưa lời mời về như cũ nếu khách chưa được thêm vào nhóm (đã thêm rồi thì hệ thống không có cách đưa ra khỏi nhóm; mẹ tự mời ra), hủy tin mẫu chưa gửi.

## Dữ liệu
Migration V29: `suppression.lifted_at/lifted_by` (CHECK chỉ dòng OPT_OUT được gỡ), `zalo_msg_log.kind` thêm `WELCOME`, bảng `comeback` (không có số điện thoại, tên, mã Zalo; trích đoạn đã che ≤300 ký tự chỉ ở việc đang chờ, xóa khi mẹ xử lý hoặc sau 30 ngày).

## Không làm
Không trả lời bằng AI, không soạn nháp, không nhắn người chưa nhắn, không né phát hiện bot, không xóa số. Cần thử với tin giả và Zalo thật (nấc Chỉ tạo việc trước) trước khi bật Tự động.
