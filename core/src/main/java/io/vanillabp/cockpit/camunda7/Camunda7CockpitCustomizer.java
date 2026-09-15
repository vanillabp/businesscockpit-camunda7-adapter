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

import io.vanillabp.camunda7.api.Camunda7EngineFacts;
import io.vanillabp.camunda7.engine.Camunda7EngineCustomizer;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * What the Business Cockpit adds to every Camunda 7 engine VanillaBP builds.
 * <p>
  * Two things, and both of them the engine's own hooks rather than anything written into a model:
  * a parse listener attaching the cockpit's task listeners to each user task, and a handler for
  * the process-instance history events. The parse listener is contributed as an <b>after</b>
  * listener. The tasks it sees are therefore the tasks VanillaBP has already wired, and the
  * cockpit's listeners run behind VanillaBP's own. That is the order a details provider needs,
  * because it is called on an aggregate a completed task may just have changed.
 * <p>
  * One customizer serves every configured Camunda 7 adapter id. VanillaBP asks it once per engine
  * and hands over the id, and what the extension reports carries that id. Two engines of a
  * migration therefore stay apart all the way to the cockpit server.
 */
public class Camunda7CockpitCustomizer implements Camunda7EngineCustomizer {

  /**
   * The history events the cockpit lives on. A workflow's lifecycle is what the engine wrote
   * about the process instance, and a user task the engine has already finished with is read
   * back from what it wrote about the task instance.
   * <p>
    * Every one of Camunda's own levels from <code>activity</code> upwards produces all of them.
    * What <code>audit</code> adds on top is variable and form-property history, which the cockpit
    * does not read. The identity-link log naming the candidates of a finished task is written at
    * <code>full</code> alone. The bridge treats it as optional, so it is not among the events an
    * engine is held to here.
   */
  private static final Set<HistoryEventTypes> HISTORY_EVENTS_THE_COCKPIT_READS = Set
      .of(
          HistoryEventTypes.PROCESS_INSTANCE_START, HistoryEventTypes.PROCESS_INSTANCE_UPDATE,
          HistoryEventTypes.PROCESS_INSTANCE_END, HistoryEventTypes.TASK_INSTANCE_CREATE,
          HistoryEventTypes.TASK_INSTANCE_UPDATE, HistoryEventTypes.TASK_INSTANCE_COMPLETE,
          HistoryEventTypes.TASK_INSTANCE_DELETE);

  private final Camunda7WorkflowProcesses processes;

  private final NameClashAvoidanceSupport scoping;

  private final Supplier<List<Camunda7EngineFacts>> engines;

  private final Supplier<BusinessCockpitEventPublisher> publisher;

  private final Map<String, Camunda7CockpitEvents> eventsByAdapterId = new ConcurrentHashMap<>();

  private final Map<String, Camunda7Scope> scopesByAdapterId = new ConcurrentHashMap<>();

  private final Map<String, Camunda7EngineFacts> enginesByAdapterId = new ConcurrentHashMap<>();

  /**
   * @param processes The deployed processes, shared with the wiring service which fills them
   * @param scoping VanillaBP's name-clash avoidance
   * @param engines What the Camunda 7 adapter knows about each of its engines, one entry per
   *          configured adapter id. It is a supplier because an engine is built with what this
   *          customizer contributes to it, so there is nothing to ask about an engine while
   *          this is being built
   * @param publisher Where events are handed to. It is a supplier because the extension is
   *          built from the BPMS halves and the BPMS halves from the engines, so asking for it
   *          while an engine is being built would close that circle
   */
  public Camunda7CockpitCustomizer(
      final Camunda7WorkflowProcesses processes,
      final NameClashAvoidanceSupport scoping,
      final Supplier<List<Camunda7EngineFacts>> engines,
      final Supplier<BusinessCockpitEventPublisher> publisher) {

    this.processes = processes;
    this.scoping = scoping;
    this.engines = engines;
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
    * The engine is about to be built, and the one thing this extension has to know about it is
    * not settled yet: how much history it will keep. The <code>preInit</code> of every engine
    * plugin of the application is still to come and may set another level, and <code>auto</code>
    * names no level at all until the engine has read the one its database was created with. So
    * nothing is decided here. A plugin is added instead, and it is asked once both have happened.
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
    * level read while an engine is being customized is not the level that engine runs with. An
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
      * Every plugin has had its say, so a level spelled out by name is settled. It is settled
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
    * application may bring along itself, and it answers per event type. Asked with no entity it
    * says whether it writes such an event at all, which is exactly the question here. Comparing
    * names would judge a level nobody but the application knows, and it would pass a level called
    * <code>activity</code> which somebody replaced with one writing less.
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
            The Business Cockpit observes the Camunda 7 engine of the adapter '%s'. That engine \
            is configured with the history level '%s', and that level does not write these \
            history events: %s. The cockpit reads a workflow's lifecycle from what the engine \
            wrote about the process instance. It reads a user task the engine has already \
            finished with from what it wrote about the task instance. Workflows and tasks would \
            therefore be missing from the cockpit. The lowest of Camunda's levels writing all of \
            them is '%s', and the engine's own default is '%s', so this engine was given its \
            level by an engine plugin or by a Camunda7EngineCustomizer of this application. \
            Configure it with '%s' or above, or take the Business Cockpit extension out of the \
            application."""
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
                scopeOf(id), processes, publisher, () -> transactionOf(id)));

  }

  /**
   * What the Camunda 7 adapter knows about the engine of one adapter id, looked up once.
   *
   * @param adapterId The configured adapter id
   * @return What that engine answers about itself
   */
  public Camunda7EngineFacts engineOf(
      final String adapterId) {

    return enginesByAdapterId
        .computeIfAbsent(
            adapterId,
            id -> engines
                .get()
                .stream()
                .filter(engine -> id.equals(engine.adapterId()))
                .findFirst()
                .orElseThrow(
                    () -> new IllegalStateException(
                        """
                            The Business Cockpit extension found nothing the Camunda 7 adapter \
                            knows about the engine of the adapter '%s', although VanillaBP counts \
                            that id among the Camunda 7 adapters of this application. The adapter \
                            publishes one entry per configured adapter id and published: %s. Add \
                            the artifact 'org.camunda.community.vanillabp:camunda7-adapter' of \
                            your platform to your workflow module: this extension listens to the \
                            engines that adapter builds and has none of its own."""
                            .formatted(
                                id,
                                engines
                                    .get()
                                    .stream()
                                    .map(Camunda7EngineFacts::adapterId)
                                    .toList()))));

  }

  /**
   * @param adapterId The configured adapter id
   * @return How that engine names what the extension asks it about. The bridge answering the
   *         cockpit's reads takes it from here, so the queries it sends and the events the
   *         listeners report cannot end up scoped differently
   */
  public Camunda7Scope scopeOf(
      final String adapterId) {

    return scopesByAdapterId
        .computeIfAbsent(adapterId, id -> new Camunda7Scope(id, scoping, () -> engineOf(id)));

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
    * Where the engine's work happens inside the transaction the caller is in, the outbox entry
    * belongs in that one. The cockpit then hears about a task if and only if the workflow which
    * created it was committed. Where it does not, there is no such transaction to join, and the
    * entry gets one of its own.
   * <p>
    * Which of the two an engine is doing is the adapter's answer. It is read off the engine the
    * adapter built rather than off a property. An extension answering it itself would be a second
    * reading of an engine somebody else assembled.
   */
  private EventTransaction transactionOf(
      final String adapterId) {

    return engineOf(adapterId).joinsTheApplicationTransaction()
        ? EventTransaction.CURRENT
        : EventTransaction.NEW;

  }

}
