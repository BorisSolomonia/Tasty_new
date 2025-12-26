package ge.tastyerp.config.service;

import ge.tastyerp.common.dto.config.CustomerDto;
import ge.tastyerp.common.exception.ResourceNotFoundException;
import ge.tastyerp.config.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for customer master data.
 *
 * ALL business logic for customer queries is here.
 * Controllers only delegate to this service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;

    /**
     * Get all customers.
     */
    public List<CustomerDto> getAllCustomers() {
        log.debug("Fetching all customers");
        return customerRepository.findAll();
    }

    /**
     * Get customer by identification number.
     */
    public CustomerDto getCustomerByIdentification(String identification) {
        log.debug("Fetching customer by identification: {}", identification);

        return customerRepository.findByIdentification(identification)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", identification));
    }
}
