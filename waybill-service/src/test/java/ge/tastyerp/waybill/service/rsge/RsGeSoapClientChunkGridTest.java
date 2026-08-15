package ge.tastyerp.waybill.service.rsge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOR-82 pass 2: RS.ge chunks live on a fixed 3-day grid so any two queries
 * that overlap in time share cache entries, and rows outside the requested
 * window (from a partially covered edge chunk) are recognisable by CREATE_DATE.
 */
class RsGeSoapClientChunkGridTest {

    @Test
    @DisplayName("Chunk index is the same for every day inside one 3-day grid cell and differs across cells")
    void gridIsFixedNotQueryRelative() {
        LocalDate d0 = LocalDate.ofEpochDay(3 * 6_800L); // a grid boundary
        assertEquals(RsGeSoapClient.chunkIndex(d0), RsGeSoapClient.chunkIndex(d0.plusDays(1)));
        assertEquals(RsGeSoapClient.chunkIndex(d0), RsGeSoapClient.chunkIndex(d0.plusDays(2)));
        assertEquals(RsGeSoapClient.chunkIndex(d0) + 1, RsGeSoapClient.chunkIndex(d0.plusDays(3)));
        // Two queries starting on different days inside the same cell hit the same chunk.
        assertEquals(RsGeSoapClient.chunkIndex(d0.plusDays(1)), RsGeSoapClient.chunkIndex(d0.plusDays(2)));
        // Chunk start reconstructed from the index is the cell's first day.
        assertEquals(d0, LocalDate.ofEpochDay(RsGeSoapClient.chunkIndex(d0.plusDays(2)) * 3));
    }

    @Test
    @DisplayName("Whole 16-month cutoff-to-today sweep is ~160 chunks, not one per query start date")
    void chunkCountForTheDebtSweep() {
        LocalDate cutoff = LocalDate.of(2025, 4, 30), today = LocalDate.of(2026, 8, 15);
        long chunks = RsGeSoapClient.chunkIndex(today) - RsGeSoapClient.chunkIndex(cutoff) + 1;
        assertTrue(chunks >= 155 && chunks <= 162, "got " + chunks);
    }

    @Test
    @DisplayName("rawCreateDate reads the RS.ge CREATE_DATE prefix and tolerates junk")
    void rawCreateDate() {
        assertEquals(LocalDate.of(2026, 8, 3), RsGeSoapClient.rawCreateDate(Map.of("CREATE_DATE", "2026-08-03T10:11:12")));
        assertEquals(LocalDate.of(2026, 8, 3), RsGeSoapClient.rawCreateDate(Map.of("create_date", "2026-08-03")));
        assertNull(RsGeSoapClient.rawCreateDate(Map.of("CREATE_DATE", "not-a-date-at-all")));
        assertNull(RsGeSoapClient.rawCreateDate(Map.of("ID", "1")));
    }
}
