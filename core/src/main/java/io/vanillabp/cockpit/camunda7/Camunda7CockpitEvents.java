package io.vanillabp.cockpit.camunda7;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.function.Supplier;

import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;

/**
 * What one configured Camunda 7 engine reports to the Business Cockpit.
 * <p>
 * Both of the engine's hooks - the task listeners of a user task and the handler of the
 * process-instance history events - end here, and both do the same little: turn what the
 * engine says into the identifiers the cockpit addresses a task or a workflow by, and write
 * one outbox entry. Nothing is read beyond those identifiers and nothing is sent, because the
 * engine is in the middle of a transaction and a listener which talks to a server holds that
 * transaction open for as long as the server takes.
 * <p>
 * The entry is written in the transaction the engine's work happens in, so it becomes visible
 * if and only if that work was committed. An engine which commits on its own has no such
 * transaction to share, and the entry then gets one of its own.
 */
public class Camunda7CockpitEvents {

  private static final Logger logger = LoggerFactory.getLogger(Camunda7CockpitEvents.class);

  private final Camunda7Scope scope;

  private final Camunda7WorkflowProcesses processes;

  private final Supplier<BusinessCockpitEventPublisher> publisher;

  private final EventTransaction transaction;

  /**
   * @param scope The engine these events come from
   * @param processes The deployed processes, to translate the engine's identifiers back
   * @param publisher Where an event is handed to, asked for on the first event rather than up
   *          front: the engine is built while the application is still wiring itself together,
   *          and the extension is built from the engines
   * @param transaction Which transaction the outbox entry is written in
   */
  public Camunda7CockpitEvents(
      final Camunda7Scope scope,
      final Camunda7WorkflowProcesses processes,
      final Supplier<BusinessCockpitEventPublisher> publisher,
      final EventTransaction transaction) {

    this.scope = scope;
    this.processes = processes;
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

    return transaction;

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

    publisher
        .get()
        .publishUserTaskEvent(
            new UserTaskReference(
                scope.adapterId(), process.get().workflowModuleId(), process.get()
                    .bpmnProcessId(), workflowAggregateId, rootProcessInstanceIdOf(
                        execution), task.getId(), taskDefinition, bpmnTaskId),
            kind, "%s#%s".formatted(task.getId(), task.getEventName()), OffsetDateTime.now(), transaction);

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
      logger
          .debug(
              "Camunda7[{}]: not reporting workflow '{}' of BPMN process '{}': the process instance carries no business key, so there is no workflow aggregate to report it for",
              scope.adapterId(), event.getProcessInstanceId(), process.get().bpmnProcessId());
      return;
    }

    publisher
        .get()
        .publishWorkflowEvent(
            new WorkflowReference(
                scope.adapterId(), process.get().workflowModuleId(), process.get()
                    .bpmnProcessId(), workflowAggregateId, event
                        .getProcessInstanceId()),
            kind, event.getId(), timestampOf(event, kind), transaction);

  }

  /**
   * The instance a business case is: a task of an embedded subprocess, of a parallel branch or
   * of a called process belongs to the workflow its whole hierarchy hangs below. The engine
   * records that instance on every execution; a workflow started before it kept that column
   * falls back to the instance the task runs in.
   */
  private static String rootProcessInstanceIdOf(
      final ExecutionEntity execution) {

    final var root = execution.getRootProcessInstanceId();
    return root == null
        ? execution.getProcessInstanceId()
        : root;

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
