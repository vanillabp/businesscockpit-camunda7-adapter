package io.vanillabp.cockpit.camunda7;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.camunda.bpm.engine.impl.history.handler.HistoryEventHandler;

import io.vanillabp.camunda7.engine.Camunda7EngineCustomizer;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * What the Business Cockpit adds to every Camunda 7 engine VanillaBP builds.
 * <p>
 * Two things, and both of them the engine's own hooks rather than anything written into a
 * model: a parse listener attaching the cockpit's task listeners to each user task, and a
 * handler for the process-instance history events. The parse listener is contributed as an
 * <b>after</b> listener, so the tasks it sees are the tasks VanillaBP has already wired and
 * the cockpit's listeners run behind VanillaBP's own - which is the order a details provider
 * needs, because it is invoked on an aggregate a completed task may just have changed.
 * <p>
 * One customizer serves every configured Camunda 7 adapter id; VanillaBP asks it once per
 * engine and hands over the id, and what the extension reports carries that id, so two engines
 * of a migration stay apart all the way to the cockpit server.
 */
public class Camunda7CockpitCustomizer implements Camunda7EngineCustomizer {

  private final Camunda7WorkflowProcesses processes;

  private final NameClashAvoidanceSupport scoping;

  private final Camunda7EngineSettings settings;

  private final Supplier<BusinessCockpitEventPublisher> publisher;

  private final Map<String, Camunda7CockpitEvents> eventsByAdapterId = new ConcurrentHashMap<>();

  /**
   * @param processes The deployed processes, shared with the wiring service which fills them
   * @param scoping VanillaBP's name-clash avoidance
   * @param settings What the Camunda 7 adapter was configured with
   * @param publisher Where events are handed to. It is a supplier because the extension is
   *          built from the BPMS halves and the BPMS halves from the engines, so asking for it
   *          while an engine is being built would close that circle
   */
  public Camunda7CockpitCustomizer(
      final Camunda7WorkflowProcesses processes,
      final NameClashAvoidanceSupport scoping,
      final Camunda7EngineSettings settings,
      final Supplier<BusinessCockpitEventPublisher> publisher) {

    this.processes = processes;
    this.scoping = scoping;
    this.settings = settings;
    this.publisher = publisher;

  }

  @Override
  public List<BpmnParseListener> parseListenersAfter(
      final String adapterId) {

    return List.of(new Camunda7UserTaskParseListener(eventsOf(adapterId)));

  }

  @Override
  public HistoryEventHandler historyEventHandler(
      final String adapterId) {

    return new Camunda7WorkflowHistoryHandler(eventsOf(adapterId));

  }

  /**
   * @param adapterId The configured adapter id
   * @return What this engine reports through, built once per engine
   */
  public Camunda7CockpitEvents eventsOf(
      final String adapterId) {

    return eventsByAdapterId
        .computeIfAbsent(
            adapterId,
            id -> new Camunda7CockpitEvents(
                new Camunda7Scope(id, scoping, settings.configuredTenantId(id)), processes, publisher, transactionOf(
                    id)));

  }

  /**
   * @param adapterId The configured adapter id
   * @return How that engine names what the extension asks it about. The bridge answering the
   *         cockpit's reads takes it from here, so the queries it sends and the events the
   *         listeners report cannot end up scoped differently
   */
  public Camunda7Scope scopeOf(
      final String adapterId) {

    return eventsOf(adapterId).scope();

  }

  /**
   * An engine sharing the application's data source runs its listeners inside the
   * application's transaction, and the outbox entry belongs in that one: the cockpit then
   * hears about a task if and only if the workflow which created it was committed. An engine
   * on a data source of its own commits on its own, so there is no such transaction to join -
   * see decision 5 in the repository's DECISIONS.md.
   */
  private EventTransaction transactionOf(
      final String adapterId) {

    return settings.runsOnItsOwnDataSource(adapterId)
        ? EventTransaction.NEW
        : EventTransaction.CURRENT;

  }

}
