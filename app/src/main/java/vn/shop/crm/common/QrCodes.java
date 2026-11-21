package vn.shop.crm.common;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Tạo mã QR TĨNH (cùng một nội dung cho mọi tờ giấy) dạng SVG hoặc PNG. Tạo cục bộ, không gọi dịch vụ ngoài. */
public final class QrCodes {

    private static final int QUIET = 4;

    private QrCodes() {
    }

    static BitMatrix matrix(String text) {
        try {
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            EncodeHintType.MARGIN, 0,
                            EncodeHintType.CHARACTER_SET, "UTF-8"));
        } catch (WriterException e) {
            throw new IllegalStateException("Không tạo được mã QR", e);
        }
    }

    public static byte[] svg(String text) {
        BitMatrix m = matrix(text);
        int n = m.getWidth();
        int box = n + 2 * QUIET;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < n; y++) {
            int x = 0;
            while (x < n) {
                if (!m.get(x, y)) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < n && m.get(x, y)) {
                    x++;
                }
                path.append('M').append(start + QUIET).append(' ').append(y + QUIET)
                        .append('h').append(x - start).append("v1h-").append(x - start).append('z');
            }
        }
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + box + " " + box
                + "\" shape-rendering=\"crispEdges\"><rect width=\"" + box + "\" height=\"" + box
                + "\" fill=\"#fff\"/><path d=\"" + path + "\" fill=\"#000\"/></svg>";
        return svg.getBytes(StandardCharsets.UTF_8);
    }

    /** PNG có lề trắng, mỗi ô vuông {@code scale} điểm ảnh. */
    public static byte[] png(String text, int scale) {
        BitMatrix m = matrix(text);
        int n = m.getWidth();
        int size = (n + 2 * QUIET) * scale;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_BINARY);
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                int x = px / scale - QUIET;
                int y = py / scale - QUIET;
                boolean dark = x >= 0 && y >= 0 && x < n && y < n && m.get(x, y);
                img.setRGB(px, py, dark ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Không tạo được ảnh PNG", e);
        }
    }
}
