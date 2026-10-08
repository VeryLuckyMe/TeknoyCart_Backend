package com.teknoycart.repositories;

import com.teknoycart.models.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentEventRepository extends JpaRepository<PaymentEvent, String> {
    // Primary key is event_id (String); existsById() used for idempotency check.
}
