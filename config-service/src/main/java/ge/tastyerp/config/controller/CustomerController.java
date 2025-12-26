package ge.tastyerp.config.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.config.CustomerDto;
import ge.tastyerp.config.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for customer master data.
 *
 * IMPORTANT: Controllers contain NO business logic.
 * All logic is delegated to CustomerService.
 */
@RestController
@RequestMapping("/api/config/customers")
@RequiredArgsConstructor
@Tag(name = "Customers", description = "Customer master data management")
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping
    @Operation(summary = "Get all customers")
    public ResponseEntity<ApiResponse<List<CustomerDto>>> getAllCustomers() {
        List<CustomerDto> customers = customerService.getAllCustomers();
        return ResponseEntity.ok(ApiResponse.success(customers));
    }

    @GetMapping("/{identification}")
    @Operation(summary = "Get customer by identification number")
    public ResponseEntity<ApiResponse<CustomerDto>> getCustomer(@PathVariable String identification) {
        CustomerDto customer = customerService.getCustomerByIdentification(identification);
        return ResponseEntity.ok(ApiResponse.success(customer));
    }
}
