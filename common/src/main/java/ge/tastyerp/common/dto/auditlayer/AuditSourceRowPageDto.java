package ge.tastyerp.common.dto.auditlayer;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * A capped page of source rows, plus the truth about what was left out.
 *
 * <p>The bare list this replaced was dangerous: asking for 2023-01-01 → today
 * returned 1,000 rows covering only the most recent three weeks, and nothing in
 * the response said so. Any view counting from that feed would have implied 40+
 * clean months. A module built to expose hidden gaps must not hide its own.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditSourceRowPageDto {

    private List<AuditSourceRowDto> rows;

    /** How many rows matched the filter in the requested period. */
    private int totalCount;

    /** How many are in {@link #rows}. */
    private int returnedCount;

    /** True when {@link #totalCount} exceeds {@link #returnedCount}. */
    private boolean truncated;

    /** Earliest date actually present in {@link #rows}. Null when empty. */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate coverageFrom;

    /** Latest date actually present in {@link #rows}. Null when empty. */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate coverageTo;

    /** The period that was asked for, so the UI can contrast it with coverage. */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate requestedFrom;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate requestedTo;
}
