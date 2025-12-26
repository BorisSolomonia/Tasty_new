package ge.tastyerp.config.repository;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import ge.tastyerp.common.dto.config.CustomerDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * Repository for customers stored in Firebase.
 *
 * Data access only - NO business logic here.
 *
 * Structure in Firebase:
 * customers/{customerId}: {
 *   "CustomerName": "შპს წისქვილი ჯგუფი",
 *   "Identification": "202200778",
 *   "ContactInfo": "..."
 * }
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CustomerRepository {

    private static final String COLLECTION = "customers";

    private final Firestore firestore;

    /**
     * Get all customers.
     */
    public List<CustomerDto> findAll() {
        try {
            List<CustomerDto> customers = new ArrayList<>();

            for (QueryDocumentSnapshot document : firestore.collection(COLLECTION).get().get().getDocuments()) {
                customers.add(mapToDto(document));
            }

            return customers;

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching all customers: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    /**
     * Find customer by identification number.
     */
    public Optional<CustomerDto> findByIdentification(String identification) {
        try {
            var query = firestore.collection(COLLECTION)
                    .whereEqualTo("Identification", identification)
                    .limit(1)
                    .get()
                    .get();

            if (query.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(mapToDto(query.getDocuments().get(0)));

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching customer by identification {}: {}", identification, e.getMessage());
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private CustomerDto mapToDto(QueryDocumentSnapshot document) {
        return CustomerDto.builder()
                .identification(document.getString("Identification"))
                .customerName(document.getString("CustomerName"))
                .contactInfo(document.getString("ContactInfo"))
                .build();
    }
}
