package io.vanillabp.cockpit.camunda7.quarkus.it;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Keeps the workflow aggregates of the test application. It keeps them in memory and not in a
 * database, because the test is about the extension and not about persistence.
 */
@ApplicationScoped
public class TestAggregatePersistence implements AggregatePersistenceAware<TestAggregate> {

  private final Map<Long, TestAggregate> aggregates = new ConcurrentHashMap<>();

  private final AtomicLong ids = new AtomicLong();

  @Override
  public Class<TestAggregate> getAggregateClass() {

    return TestAggregate.class;

  }

  @Override
  public TestAggregate save(
      final TestAggregate aggregate) {

    if (aggregate.getId() == null) {
      aggregate.setId(ids.incrementAndGet());
    }
    aggregates.put(aggregate.getId(), aggregate);
    return aggregate;

  }

  @Override
  public TestAggregate loadById(
      final Object aggregateId) {

    return aggregates.get(Long.valueOf(String.valueOf(aggregateId)));

  }

  @Override
  public Object getAggregateId(
      final TestAggregate aggregate) {

    return aggregate.getId();

  }

  /**
   * @param id The aggregate's id
   * @return What is stored under it
   */
  public TestAggregate byId(
      final Long id) {

    return aggregates.get(id);

  }

}
