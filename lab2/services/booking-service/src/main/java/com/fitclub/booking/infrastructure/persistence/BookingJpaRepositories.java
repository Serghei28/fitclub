package com.fitclub.booking.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.fitclub.booking.domain.Booking;
import com.fitclub.booking.infrastructure.persistence.BookingEntities.BookingEntity;
import com.fitclub.booking.infrastructure.persistence.BookingEntities.InventoryEntity;
import com.fitclub.booking.infrastructure.persistence.BookingEntities.UsageEntity;

interface InventoryJpaRepository extends JpaRepository<InventoryEntity, UUID> {

    List<InventoryEntity> findBySessionIdIn(Collection<UUID> ids);
}

interface BookingJpaRepository extends JpaRepository<BookingEntity, UUID> {

    boolean existsBySessionIdAndUserIdAndStatus(UUID sessionId, UUID userId, Booking.Status status);

    List<BookingEntity> findBySessionIdAndStatusOrderByCreatedAtAsc(UUID sessionId, Booking.Status status);

    Page<BookingEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<BookingEntity> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, Booking.Status status, Pageable pageable);
}

interface UsageJpaRepository extends JpaRepository<UsageEntity, UUID> {

    List<UsageEntity> findByUserId(UUID userId);
}
