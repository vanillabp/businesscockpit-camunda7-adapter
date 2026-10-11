package io.vanillabp.cockpit.camunda7.springboot.test;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Stores the workflow aggregates of the cases which a signal started.
 */
public interface SignalledAggregateRepository extends JpaRepository<SignalledAggregate, Long> {
}
