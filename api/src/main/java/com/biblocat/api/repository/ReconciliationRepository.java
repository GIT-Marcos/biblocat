package com.biblocat.api.repository;

import com.biblocat.api.entity.Reconciliation;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRepository extends JpaRepository<Reconciliation, Integer> {

    Optional<Reconciliation> findById(Integer id);
}
