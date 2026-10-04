package io.vanillabp.cockpit.camunda7;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.interceptor.CommandContextListener;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.camunda7.api.Camunda7Executions;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;

/**
 * What one configured Camunda 7 engine reports to the Business Cockpit.
 * <p>
  * The engine has two hooks here, the task listeners of a user task and the handler of the
  * process-instance history events. Both end in this class, and both do the same: turn what the
  * engine says into the identifiers the cockpit addresses a task or a workflow by, and hand the
  * event over. The cockpit's neutral half then builds the whole report and writes one outbox
  * entry. Nothing is sent from here. The engine is in the middle of a transaction, and a listener
  * which talks to a server holds that transaction open for as long as the server takes.
 * <p>
  * The report is built out of the event rather than out of a later reading, so what the engine
  * handed over is put into {@link Camunda7EventBeingReported} for as long as that takes.
  * {@link Camunda7CockpitBridge} answers the cockpit's questions from there.
 * <p>
  * The version of the deployed process is one of those identifiers, and the cockpit picks the
  * details provider of a task or a workflow by it. The Camunda 7 adapter has it cached, so
  * asking for it here costs the transaction nothing.
 * <p>
  * The entry is written in the transaction the engine's work happens in, so it becomes visible if
  * and only if that work was committed. An engine which commits on its own has no such
  * transaction to share. The entry then gets one of its own.
 */
public class Camunda7CockpitEvents {

  private static final Logger logger = LoggerFactory.getLogger(Camunda7CockpitEvents.class);

  private final Camunda7Scope scope;

  private final Camunda7WorkflowProcesses processes;

  private final Camunda7EventBeingReported eventBeingReported;

  private final Supplier<BusinessCockpitEventPublisher> publisher;

  private final Supplier<EventTransaction> transaction;

  /**
   * The starts of process instances which had no name yet, by the id of the instance, with the
   * moment each one started. An entry lives for as long as the engine command which started the
   * instance: see {@link #rememberTheStartOfAnUnnamedWorkflow}.
   */
  private final Map<String, Date> startsWaitingForTheirName = new ConcurrentHashMap<>();

  /**
   * @param scope The engine these events come from
   * @param processes The deployed processes, to translate the engine's identifiers back
   * @param eventBeingReported Where the event is put for as long as its report is built, shared
   *          with the bridge which answers out of it
   * @param publisher Where an event is handed to, asked for on the first event rather than up
   *          front: the engine is built while the application is still wiring itself together,
   *          and the extension is built from the engines
   * @param transaction Which transaction the outbox entry is written in, asked for on the first
   *          event for the same reason the publisher is: it is the engine's answer, and the
   *          engine is still being built while this is
   */
  public Camunda7CockpitEvents(
      final Camunda7Scope scope,
      final Camunda7WorkflowProcesses processes,
      final Camunda7EventBeingReported eventBeingReported,
      final Supplier<BusinessCockpitEventPublisher> publisher,
      final Supplier<EventTransaction> transaction) {

    this.scope = scope;
    this.processes = processes;
    this.eventBeingReported = eventBeingReported;
    this.publisher = publisher;
    this.transaction = transaction;

  }

  /**
   * @return The engine these events come from
   */
  public Camunda7Scope scope() {

    return scope;

  }

  /**
   * @return Which transaction the outbox entry of an event is written in
   */
  public EventTransaction transaction() {

    return transaction.get();

  }

  /**
   * Reports what happened to one user task.
   *
   * @param task The task the engine handed to the listener
   * @param bpmnTaskId The BPMN element id of the user task
   * @param taskDefinition The task's form key, or its element id where it has none
   * @param kind What happened
   */
  public void reportUserTask(
      final DelegateTask task,
      final String bpmnTaskId,
      final String taskDefinition,
      final UserTaskEventKind kind) {

    final var execution = (ExecutionEntity) task.getExecution();
    final var definition = execution.getProcessDefinition();
    final var process = processes
        .resolve(scope, definition.getTenantId(), definition.getKey());
    if (process.isEmpty()) {
      logger
          .debug(
              "Camunda7[{}]: not reporting user task '{}': its process definition '{}' belongs to no workflow module of this application",
              scope.adapterId(), task.getId(), definition.getKey());
      return;
    }

    final var workflowAggregateId = execution.getBusinessKey();
    if (workflowAggregateId == null) {
      logger
          .debug(
              "Camunda7[{}]: not reporting user task '{}' of BPMN process '{}': the process instance carries no business key, so there is no workflow aggregate to report it for",
              scope.adapterId(), task.getId(), process.get().bpmnProcessId());
      return;
    }

    final var reference = new UserTaskReference(
        scope.adapterId(), process.get().workflowModuleId(), process.get()
            .bpmnProcessId(), scope
                .processVersionOf(
                    definition.getId()), workflowAggregateId, Camunda7Executions
                        .rootProcessInstanceIdOf(
                            execution), task.getId(), taskDefinition, bpmnTaskId);
    eventBeingReported
        .whileReporting(
            task,
            () -> publisher
                .get()
                .publishUserTaskEvent(
                    reference, kind, "%s#%s".formatted(task.getId(), task.getEventName()), OffsetDateTime
                        .now(),
                    transaction()));

  }

  /**
   * Reports what happened to one workflow.
   *
   * @param event The history event of the process instance
   * @param kind What happened
   */
  public void reportWorkflow(
      final HistoricProcessInstanceEventEntity event,
      final WorkflowEventKind kind) {

    // the cockpit shows business cases, and a called process is a step of one rather than a
    // case of its own - see decision 3 in the repository's DECISIONS.md
    if (event.getSuperProcessInstanceId() != null) {
      return;
    }
    final var process = processes
        .resolve(scope, event.getTenantId(), event.getProcessDefinitionKey());
    if (process.isEmpty()) {
      return;
    }
    final var workflowAggregateId = event.getBusinessKey();
    if (workflowAggregateId == null) {
      if (kind == WorkflowEventKind.CREATED) {
        rememberTheStartOfAnUnnamedWorkflow(event);
      }
      logger
          .debug(
              "Camunda7[{}]: not reporting workflow '{}' of BPMN process '{}': the process instance carries no business key, so there is no workflow aggregate to report it for",
              scope.adapterId(), event.getProcessInstanceId(), process.get().bpmnProcessId());
      return;
    }

    // the update which gives a workflow its first name, after a start nobody could report, is
    // the start of the case for the cockpit, with the moment the instance started
    final var startWaitingForThisName = kind == WorkflowEventKind.UPDATED
        ? startsWaitingForTheirName.remove(event.getProcessInstanceId())
        : null;
    final var reportedKind = startWaitingForThisName == null
        ? kind
        : WorkflowEventKind.CREATED;
    final var timestamp = startWaitingForThisName == null
        ? timestampOf(event, kind)
        : atOffset(startWaitingForThisName);

    final var reference = new WorkflowReference(
        scope.adapterId(), process.get().workflowModuleId(), process.get()
            .bpmnProcessId(), scope
                .processVersionOf(
                    event.getProcessDefinitionId()), workflowAggregateId, event
                        .getProcessInstanceId());
    eventBeingReported
        .whileReporting(
            event,
            () -> publisher
                .get()
                .publishWorkflowEvent(
                    reference, reportedKind, event.getId(), timestamp, transaction()));

  }

  /**
   * A workflow which the engine started by itself has no name at its start. A timer, a signal or
   * a message correlated past VanillaBP starts the process instance without a business key, and
   * the Camunda 7 adapter writes the name into it a moment later, once the application has built
   * the workflow aggregate. For the engine that is a change of the process instance, so the next
   * event about it is an update. This remembers the start, and the update which brings the name
   * is reported as the start of the case.
   * <p>
   * The name arrives in the same engine command, while the listener of the start event runs. So
   * the entry is dropped when that command ends, whether a name arrived or not. A start which
   * never gets a name is no case of the cockpit, and a later update is a real update.
   *
   * @param event The history event of the start
   */
  private void rememberTheStartOfAnUnnamedWorkflow(
      final HistoricProcessInstanceEventEntity event) {

    final var commandContext = Context.getCommandContext();
    if (commandContext == null) {
      // there is no command whose end would drop the entry again
      return;
    }
    final var processInstanceId = event.getProcessInstanceId();
    startsWaitingForTheirName
        .put(
            processInstanceId, event.getStartTime() == null
                ? new Date()
                : event.getStartTime());
    commandContext.registerCommandContextListener(new CommandContextListener() {

      @Override
      public void onCommandContextClose(
          final CommandContext closed) {

        startsWaitingForTheirName.remove(processInstanceId);

      }

      @Override
      public void onCommandFailed(
          final CommandContext failed,
          final Throwable cause) {

        startsWaitingForTheirName.remove(processInstanceId);

      }

    });

  }

  private static OffsetDateTime timestampOf(
      final HistoricProcessInstanceEventEntity event,
      final WorkflowEventKind kind) {

    final var reported = kind == WorkflowEventKind.CREATED
        ? event.getStartTime()
        : event.getEndTime();
    return reported == null
        ? OffsetDateTime.now()
        : atOffset(reported);

  }

  private static OffsetDateTime atOffset(
      final Date date) {

    return date.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();

  }

}
