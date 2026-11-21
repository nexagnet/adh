package vn.shop.crm.common;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Mã QR in ra phải đọc ngược được đúng địa chỉ: quét thử bằng bộ giải mã thật, không chỉ kiểm tra có tạo ra byte. */
class QrCodesTest {

    private static String decode(byte[] png) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, pixels)));
        return new QRCodeReader().decode(bitmap).getText();
    }

    @Test
    void anhPngDocNguocDuocDungDiaChi() throws Exception {
        String url = "https://chamsoc.cuahang.vn/moi";
        assertThat(decode(QrCodes.png(url, 8))).isEqualTo(url);
        assertThat(decode(QrCodes.png(url, 16))).isEqualTo(url);
    }

    @Test
    void svgHopLeVaCungNoiDungLuonGiongNhau() {
        String url = "https://chamsoc.cuahang.vn/moi";
        String svg = new String(QrCodes.svg(url), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(svg).startsWith("<svg").contains("viewBox=").contains("<path d=\"M").endsWith("</svg>");
        assertThat(QrCodes.svg(url)).isEqualTo(QrCodes.svg(url));
        assertThat(QrCodes.svg(url + "x")).isNotEqualTo(QrCodes.svg(url));
    }
}
