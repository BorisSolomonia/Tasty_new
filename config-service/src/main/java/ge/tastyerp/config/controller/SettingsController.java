package ge.tastyerp.config.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.config.SystemSettingsDto;
import ge.tastyerp.config.service.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for system settings.
 *
 * IMPORTANT: Controllers contain NO business logic.
 * All logic is delegated to SettingsService.
 */
@RestController
@RequestMapping("/api/config/settings")
@RequiredArgsConstructor
@Tag(name = "Settings", description = "System configuration settings management")
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping
    @Operation(summary = "Get all system settings")
    public ResponseEntity<ApiResponse<SystemSettingsDto>> getAllSettings() {
        SystemSettingsDto settings = settingsService.getAllSettings();
        return ResponseEntity.ok(ApiResponse.success(settings));
    }

    @GetMapping("/{key}")
    @Operation(summary = "Get a specific setting by key")
    public ResponseEntity<ApiResponse<Object>> getSetting(@PathVariable String key) {
        Object value = settingsService.getSetting(key);
        return ResponseEntity.ok(ApiResponse.success(value));
    }

    @PutMapping("/{key}")
    @Operation(summary = "Update a specific setting")
    public ResponseEntity<ApiResponse<SystemSettingsDto>> updateSetting(
            @PathVariable String key,
            @RequestBody Object value) {
        SystemSettingsDto updated = settingsService.updateSetting(key, value);
        return ResponseEntity.ok(ApiResponse.success(updated, "Setting updated successfully"));
    }

    @PostMapping("/reset")
    @Operation(summary = "Reset all settings to defaults")
    public ResponseEntity<ApiResponse<SystemSettingsDto>> resetToDefaults() {
        SystemSettingsDto defaults = settingsService.resetToDefaults();
        return ResponseEntity.ok(ApiResponse.success(defaults, "Settings reset to defaults"));
    }
}
