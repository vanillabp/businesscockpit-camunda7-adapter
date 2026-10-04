package io.vanillabp.cockpit.camunda7.springboot.test;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Where the workflow aggregates of the cases a signal started live.
 */
public interface SignalledAggregateRepository extends JpaRepository<SignalledAggregate, Long> {
}
