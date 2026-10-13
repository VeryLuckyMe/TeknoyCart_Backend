package com.teknoycart.repositories;

import com.teknoycart.models.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query(value = "SELECT * FROM notifications n " +
                   "WHERE (n.push_status = 'PENDING' OR n.email_status = 'PENDING') " +
                   "AND n.retry_count < 3 " +
                   "ORDER BY n.created_at ASC " +
                   "LIMIT :limit " +
                   "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Notification> findPendingForUpdateSkipLocked(@Param("limit") int limit);

    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserIdAndIsReadFalse(UUID userId);
}
