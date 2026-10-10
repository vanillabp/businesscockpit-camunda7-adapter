package io.vanillabp.cockpit.camunda7.springboot.test;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Where the workflow aggregates of the calling cases live.
 */
public interface CaseCallingAggregateRepository extends JpaRepository<CaseCallingAggregate, Long> {
}
