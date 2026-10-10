package io.vanillabp.cockpit.camunda7.springboot.test;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Where the workflow aggregates of the cases of their own live.
 */
public interface OwnCaseAggregateRepository extends JpaRepository<OwnCaseAggregate, Long> {
}
