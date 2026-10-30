package com.fitclub.auth.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    @Query("select count(u) > 0 from UserEntity u join u.roles r where r.roleCode = :role")
    boolean existsWithRole(@Param("role") String role);

    /** pattern — уже готовый шаблон LIKE в нижнем регистре ('%' — все). */
    @Query(value = """
            select u from UserEntity u
            where lower(u.email) like :pattern or lower(u.displayName) like :pattern
            """, countQuery = """
            select count(u) from UserEntity u
            where lower(u.email) like :pattern or lower(u.displayName) like :pattern
            """)
    Page<UserEntity> search(@Param("pattern") String pattern, Pageable pageable);

    @Query(value = """
            select u from UserEntity u
            where exists (select 1 from UserEntity x join x.roles r where x = u and r.roleCode = :role)
              and (lower(u.email) like :pattern or lower(u.displayName) like :pattern)
            """, countQuery = """
            select count(u) from UserEntity u
            where exists (select 1 from UserEntity x join x.roles r where x = u and r.roleCode = :role)
              and (lower(u.email) like :pattern or lower(u.displayName) like :pattern)
            """)
    Page<UserEntity> searchByRole(@Param("role") String role, @Param("pattern") String pattern, Pageable pageable);
}
