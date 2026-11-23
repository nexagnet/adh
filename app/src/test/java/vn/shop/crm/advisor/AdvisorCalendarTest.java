package vn.shop.crm.advisor;

import org.junit.jupiter.api.Test;
import vn.shop.crm.geo.Region;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisorCalendarTest {

    @Test
    void dipSapToiTinhDungNgay() {
        var list = AdvisorCalendar.upcoming(LocalDate.of(2026, 11, 23), 45);
        assertThat(list).extracting(AdvisorSnapshot.Occasion::name, AdvisorSnapshot.Occasion::inDays).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Black Friday", 4),          // thứ Sáu 27/11/2026
                org.assertj.core.groups.Tuple.tuple("Ngày đôi 12.12 trên sàn", 19),
                org.assertj.core.groups.Tuple.tuple("Giáng sinh", 31),
                org.assertj.core.groups.Tuple.tuple("Tết dương lịch", 39));
    }

    @Test
    void tetAmLichTinhTheoBang() {
        var list = AdvisorCalendar.upcoming(LocalDate.of(2027, 1, 20), 30);
        assertThat(list).extracting(AdvisorSnapshot.Occasion::name).contains("Tết Nguyên đán (mùng 1)");
        assertThat(list.stream().filter(o -> o.name().startsWith("Tết Nguyên")).findFirst().orElseThrow().inDays()).isEqualTo(17);
    }

    @Test
    void mauTheoMienVaThang() {
        assertThat(AdvisorCalendar.season(Region.BAC, 12)).contains("rét");
        assertThat(AdvisorCalendar.season(Region.NAM, 7)).contains("mưa");
        assertThat(AdvisorCalendar.seasons(1)).hasSize(3);
    }
}
