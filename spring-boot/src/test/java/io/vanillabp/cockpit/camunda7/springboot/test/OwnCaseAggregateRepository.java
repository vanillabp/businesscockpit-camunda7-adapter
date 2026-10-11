package io.vanillabp.cockpit.camunda7.springboot.test;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Stores the workflow aggregates of the called processes which are cases of their own.
 */
public interface OwnCaseAggregateRepository extends JpaRepository<OwnCaseAggregate, Long> {
}
