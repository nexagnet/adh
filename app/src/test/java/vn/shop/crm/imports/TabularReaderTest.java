package vn.shop.crm.imports;

import org.junit.jupiter.api.Test;
import vn.shop.crm.common.ImportException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TabularReaderTest {

    private static final long LIMIT = 100_000_000L;

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void docCsvCoBomVaDauPhayCoNgoacKep() {
        String csv = "﻿Mã vận đơn,Địa chỉ\r\nVTP1,\"12 Duong A, Quan 1\"\r\n";
        List<List<String>> rows = TabularReader.read(utf8(csv), LIMIT);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("Mã vận đơn", "Địa chỉ");
        assertThat(rows.get(1)).containsExactly("VTP1", "12 Duong A, Quan 1");
    }

    @Test
    void tuDoanDauChamPhayCuaExcelVN() {
        List<List<String>> rows = TabularReader.read(utf8("a;b;c\n1;2;3\n"), LIMIT);
        assertThat(rows.get(1)).containsExactly("1", "2", "3");
    }

    @Test
    void csvKhongPhaiUtf8BiTuChoiVoiThongBaoRoRang() {
        byte[] latin1 = "Ma van don;Dia chi\nVTP1;éè".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> TabularReader.read(latin1, LIMIT))
                .isInstanceOf(ImportException.class).hasMessageContaining("UTF-8");
    }

    @Test
    void fileXlsCuBiTuChoi() {
        byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        assertThatThrownBy(() -> TabularReader.read(ole2, LIMIT))
                .isInstanceOf(ImportException.class).hasMessageContaining(".xlsx");
    }

    @Test
    void fileRongBiTuChoi() {
        assertThatThrownBy(() -> TabularReader.read(new byte[0], LIMIT)).isInstanceOf(ImportException.class);
    }

    @Test
    void docXlsxToiThieu() throws IOException {
        byte[] xlsx = minimalXlsx();
        List<List<String>> rows = TabularReader.read(xlsx, LIMIT);
        assertThat(rows.get(0)).containsExactly("Mã vận đơn", "SĐT người nhận");
        assertThat(rows.get(1)).containsExactly("VTP1", "0912345678");
        assertThat(rows.get(2).get(1)).isEqualTo("912345678"); // số lưu dạng số, mất số 0 đầu
    }

    @Test
    void zipBomBiChan() throws IOException {
        byte[] xlsx = minimalXlsx();
        assertThatThrownBy(() -> TabularReader.read(xlsx, 100))
                .isInstanceOf(ImportException.class).hasMessageContaining("quá lớn");
    }

    /** XLSX nhỏ nhất có thể, dùng chuỗi inline và một ô số. */
    private static byte[] minimalXlsx() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>""");
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            put(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>""");
            put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>""");
            put(zip, "xl/worksheets/sheet1.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <sheetData>
                    <row r="1"><c r="A1" t="inlineStr"><is><t>Mã vận đơn</t></is></c><c r="B1" t="inlineStr"><is><t>SĐT người nhận</t></is></c></row>
                    <row r="2"><c r="A2" t="inlineStr"><is><t>VTP1</t></is></c><c r="B2" t="inlineStr"><is><t>0912345678</t></is></c></row>
                    <row r="3"><c r="A3" t="inlineStr"><is><t>VTP2</t></is></c><c r="B3"><v>912345678</v></c></row>
                    </sheetData>
                    </worksheet>""");
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
