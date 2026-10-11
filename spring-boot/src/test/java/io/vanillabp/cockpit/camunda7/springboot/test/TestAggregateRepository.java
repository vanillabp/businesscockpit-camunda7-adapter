package io.vanillabp.cockpit.camunda7.springboot.test;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Stores the workflow aggregates of the test. VanillaBP loads and saves them through it, and so
 * does the extension whenever a details provider runs.
 */
public interface TestAggregateRepository extends JpaRepository<TestAggregate, Long> {
}
