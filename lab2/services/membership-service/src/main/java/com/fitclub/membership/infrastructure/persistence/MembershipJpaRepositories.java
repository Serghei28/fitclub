package com.fitclub.membership.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fitclub.membership.domain.OrderStatus;
import com.fitclub.membership.infrastructure.persistence.MembershipEntities.MembershipEntity;
import com.fitclub.membership.infrastructure.persistence.MembershipEntities.OrderEntity;
import com.fitclub.membership.infrastructure.persistence.MembershipEntities.PlanEntity;

interface PlanJpaRepository extends JpaRepository<PlanEntity, UUID> {

    List<PlanEntity> findAllByOrderByPriceMinorAscNameAsc();

    List<PlanEntity> findByActiveTrueOrderByPriceMinorAscNameAsc();
}

interface OrderJpaRepository extends JpaRepository<OrderEntity, UUID> {

    Optional<OrderEntity> findFirstByUserIdAndStatus(UUID userId, OrderStatus status);
}

interface MembershipJpaRepository extends JpaRepository<MembershipEntity, UUID> {

    List<MembershipEntity> findByUserId(UUID userId);

    @Query("""
            select m from MembershipEntity m
            where m.userId = :userId and m.startsAt <= :moment and m.endsAt > :moment
            order by m.startsAt desc
            """)
    List<MembershipEntity> findValidAt(@Param("userId") UUID userId, @Param("moment") Instant moment);

    boolean existsByUserIdAndEndsAtAfter(UUID userId, Instant moment);
}
