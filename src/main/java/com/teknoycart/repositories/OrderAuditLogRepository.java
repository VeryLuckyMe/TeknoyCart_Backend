package com.teknoycart.repositories;

import com.teknoycart.models.OrderAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OrderAuditLogRepository extends JpaRepository<OrderAuditLog, UUID> {
}
