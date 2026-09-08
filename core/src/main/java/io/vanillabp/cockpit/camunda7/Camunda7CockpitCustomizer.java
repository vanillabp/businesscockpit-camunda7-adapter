package io.vanillabp.cockpit.camunda7;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.camunda.bpm.engine.impl.history.HistoryLevel;
import org.camunda.bpm.engine.impl.history.event.HistoryEventTypes;
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

  /**
   * The history events the cockpit lives on. A workflow's lifecycle is what the engine wrote
   * about the process instance, and a user task the engine has already finished with is read
   * back from what it wrote about the task instance.
   * <p>
   * Every one of Camunda's own levels from <code>activity</code> upwards produces all of them;
   * what <code>audit</code> adds on top is variable and form-property history, which the
   * cockpit does not read. The identity-link log naming the candidates of a finished task is
   * written at <code>full</code> alone, and the bridge treats it as optional, so it is not
   * among the events an engine is held to here.
   */
  private static final Set<HistoryEventTypes> HISTORY_EVENTS_THE_COCKPIT_READS = Set
      .of(
          HistoryEventTypes.PROCESS_INSTANCE_START, HistoryEventTypes.PROCESS_INSTANCE_UPDATE,
          HistoryEventTypes.PROCESS_INSTANCE_END, HistoryEventTypes.TASK_INSTANCE_CREATE,
          HistoryEventTypes.TASK_INSTANCE_UPDATE, HistoryEventTypes.TASK_INSTANCE_COMPLETE,
          HistoryEventTypes.TASK_INSTANCE_DELETE);

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
   * The engine is about to be built, and the one thing this extension has to know about it -
   * how much history it will keep - is not settled yet: the <code>preInit</code> of every
   * engine plugin of the application is still to come and may set another level, and
   * <code>auto</code> names no level at all until the engine has read the one its database was
   * created with. So nothing is decided here; a plugin is added which is asked once both have
   * happened.
   */
  @Override
  public void customize(
      final String adapterId,
      final ProcessEngineConfigurationImpl configuration) {

    configuration
        .getProcessEnginePlugins()
        .add(new HistoryLevelCheck(adapterId));

  }

  /**
   * Asks the engine's history level whether it produces the events the cockpit reads and ends
   * the boot where it does not - see decision 8 in the repository's DECISIONS.md.
   * <p>
   * A plugin rather than a line in {@link Camunda7CockpitCustomizer#customize}, because the
   * level read while an engine is being customized is not the level that engine runs with: an
   * application sets it in the <code>preInit</code> of a plugin of its own, and every one of
   * those has run by the time the engine calls <code>postInit</code>.
   */
  private static final class HistoryLevelCheck implements ProcessEnginePlugin {

    private final String adapterId;

    private HistoryLevelCheck(
        final String adapterId) {

      this.adapterId = adapterId;

    }

    @Override
    public void preInit(
        final ProcessEngineConfigurationImpl configuration) {
      // asking here would be asking before the plugins which set the level have run
    }

    /**
     * Every plugin has had its say, so a level spelled out by name is settled - and settled
     * before the engine has written anything, which is the earliest this can end a boot.
     */
    @Override
    public void postInit(
        final ProcessEngineConfigurationImpl configuration) {

      failOnALevelWritingTooLittle(adapterId, configuration.getHistoryLevel());

    }

    /**
     * And <code>auto</code>, which the engine resolves against its own database while it is
     * built, is settled here.
     */
    @Override
    public void postProcessEngineBuild(
        final ProcessEngine engine) {

      failOnALevelWritingTooLittle(
          adapterId,
          ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
              .getHistoryLevel());

    }

  }

  /**
   * Everything the cockpit shows comes out of the engine's history, so an engine which writes
   * none of it would leave the application running and the cockpit empty without anything
   * saying why.
   * <p>
   * The level is asked rather than compared against a list of names. A level is an object an
   * application may bring along itself, and it answers per event type; asked with no entity it
   * says whether it writes such an event at all, which is exactly the question here. Comparing
   * names would judge a level nobody but the application knows, and it would pass a level
   * called <code>activity</code> which somebody replaced with one writing less.
   *
   * @param adapterId The configured adapter id, for the message
   * @param level What the engine settled on, or <code>null</code> while <code>auto</code> is
   *          still unresolved
   */
  private static void failOnALevelWritingTooLittle(
      final String adapterId,
      final HistoryLevel level) {

    if (level == null) {
      return;
    }
    final var missing = HISTORY_EVENTS_THE_COCKPIT_READS
        .stream()
        .filter(event -> !level.isHistoryEventProduced(event, null))
        .map(Enum::name)
        .sorted()
        .toList();
    if (missing.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            The Business Cockpit observes the Camunda 7 engine of the adapter '%s', but that \
            engine is configured with the history level '%s', which does not write these history \
            events: %s. The cockpit reads a workflow's lifecycle from what the engine \
            wrote about the process instance and a user task it has already finished with from \
            what it wrote about the task instance, so workflows and tasks would be missing from \
            the cockpit. The lowest of Camunda's levels writing all of them is '%s', and the \
            engine's own default is '%s' - so this engine was given its level by an engine \
            plugin or by a Camunda7EngineCustomizer of this application. Configure it with '%s' \
            or above, or take the Business Cockpit extension out of the application."""
            .formatted(
                adapterId, level.getName(), String.join(", ", missing),
                ProcessEngineConfiguration.HISTORY_ACTIVITY,
                ProcessEngineConfiguration.HISTORY_DEFAULT,
                ProcessEngineConfiguration.HISTORY_ACTIVITY));

  }

  /**
   * @param adapterId The configured adapter id
   * @return What this engine reports through, built once per engine
   */
  private Camunda7CockpitEvents eventsOf(
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
   * @param adapterId The configured adapter id
   * @return Which transaction the entries of that engine are written in - what the platform
   *         answered for it, and with it whether a rolled-back workflow can leave a report
   *         behind
   */
  public EventTransaction eventTransactionOf(
      final String adapterId) {

    return eventsOf(adapterId).transaction();

  }

  /**
   * Where the engine's work happens inside the application's transaction, the outbox entry
   * belongs in that one: the cockpit then hears about a task if and only if the workflow which
   * created it was committed. Where it does not, there is no such transaction to join and the
   * entry gets one of its own. Which of the two an engine is doing is a question about the
   * platform's transaction integration rather than about a single property, so the platform
   * modules answer it - see decision 6 in the repository's DECISIONS.md.
   */
  private EventTransaction transactionOf(
      final String adapterId) {

    return settings.joinsTheApplicationTransaction(adapterId)
        ? EventTransaction.CURRENT
        : EventTransaction.NEW;

  }

}
