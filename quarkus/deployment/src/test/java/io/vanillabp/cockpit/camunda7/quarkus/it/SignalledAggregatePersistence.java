package io.vanillabp.cockpit.camunda7.quarkus.it;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Where the test application keeps the workflow aggregates of the cases a signal started. It is in
 * memory, like {@link TestAggregatePersistence}.
 */
@ApplicationScoped
public class SignalledAggregatePersistence implements AggregatePersistenceAware<SignalledAggregate> {

  private final Map<Long, SignalledAggregate> aggregates = new ConcurrentHashMap<>();

  private final AtomicLong ids = new AtomicLong();

  @Override
  public Class<SignalledAggregate> getAggregateClass() {

    return SignalledAggregate.class;

  }

  @Override
  public SignalledAggregate save(
      final SignalledAggregate aggregate) {

    if (aggregate.getId() == null) {
      aggregate.setId(ids.incrementAndGet());
    }
    aggregates.put(aggregate.getId(), aggregate);
    return aggregate;

  }

  @Override
  public SignalledAggregate loadById(
      final Object aggregateId) {

    return aggregates.get(Long.valueOf(String.valueOf(aggregateId)));

  }

  @Override
  public Object getAggregateId(
      final SignalledAggregate aggregate) {

    return aggregate.getId();

  }

  /**
   * The Camunda 7 adapter asks for this name before it lets an application method build the
   * aggregate of a workflow the engine started. Without it, the adapter decides that no workflow
   * service serves the process and leaves the instance without a business key.
   *
   * @return The name of the id property of {@link SignalledAggregate}
   */
  @Override
  public String getAggregateIdName() {

    return "id";

  }

}
