package ge.tastyerp.payment.bank.tbc;

import ge.tastyerp.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * REST Controller for the TBC DBI bank integration.
 *
 * IMPORTANT: Controllers contain NO business logic.
 * All logic is delegated to TbcSyncService.
 */
@Slf4j
@RestController
@RequestMapping("/api/payments/bank/tbc")
@RequiredArgsConstructor
@Tag(name = "TBC Bank", description = "TBC DBI integration: sync, status, credential lifecycle")
public class TbcBankController {

    private final TbcSyncService tbcSyncService;

    @PostMapping("/sync")
    @Operation(summary = "Trigger a TBC sync now",
            description = "Runs the same incremental sync as the scheduler. Optional from/to (yyyy-MM-dd) "
                    + "override the window for targeted backfills; force=true bypasses the min-interval throttle.")
    public ResponseEntity<ApiResponse<TbcSyncService.SyncResult>> syncNow(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "false") boolean force) {

        TbcSyncService.SyncResult result = tbcSyncService.syncNow(from, to, force);
        return ResponseEntity.ok(ApiResponse.success(result, result.message()));
    }

    @GetMapping("/status")
    @Operation(summary = "Get TBC sync status and configuration")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        return ResponseEntity.ok(ApiResponse.success(tbcSyncService.status()));
    }

    @PostMapping("/password-change")
    @Operation(summary = "Change the TBC DBI password (one-time handshake)",
            description = "TBC requires a password change before first use and on CREDENTIALS_MUST_BE_CHANGED "
                    + "faults. The Digipass/OTP code is sent as the WS-Security nonce. After success, persist "
                    + "the new password to TBC_DBI_PASSWORD in .env — the runtime override is lost on restart.")
    public ResponseEntity<ApiResponse<String>> changePassword(@RequestBody PasswordChangeRequest request) {
        String message = tbcSyncService.changePassword(
                request.otp(), request.newPassword(), request.currentPassword());
        return ResponseEntity.ok(ApiResponse.success(message, message));
    }

    public record PasswordChangeRequest(String otp, String newPassword, String currentPassword) {
    }
}
