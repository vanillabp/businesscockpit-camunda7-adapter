package io.vanillabp.cockpit.camunda7.springboot.test;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Stores the workflow aggregates of the calling cases.
 */
public interface CaseCallingAggregateRepository extends JpaRepository<CaseCallingAggregate, Long> {
}
