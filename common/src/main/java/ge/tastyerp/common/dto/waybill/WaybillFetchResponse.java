package ge.tastyerp.common.dto.waybill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for waybill fetch operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaybillFetchResponse {

    private boolean success;
    private String message;
    private int totalCount;
    private int afterCutoffCount;
    private List<WaybillDto> waybills;
    private WaybillDiagnostics diagnostics;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WaybillDiagnostics {
        private int totalProcessed;
        private int validNames;
        private int emptyNames;
        private int idAsName;
        private int invalidStatus;
    }
}
