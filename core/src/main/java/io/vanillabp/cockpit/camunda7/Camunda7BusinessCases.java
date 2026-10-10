package io.vanillabp.cockpit.camunda7;

import java.util.function.Function;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;

/**
 * Which process instance is the business case of an instance in a call hierarchy.
 * <p>
 * The cockpit shows business cases. A called process which shares the workflow aggregate of its
 * caller is a step of the caller's case. A called process with a workflow aggregate of its own is
 * a case of its own. See decision 15 in the repository's DECISIONS.md. So the case of an instance
 * is the highest instance above it which can be reached by calls that share the aggregate. The
 * walk stops at the first caller which does not share it.
 * <p>
 * Whether two processes share the aggregate is the core's answer, asked through
 * {@link Camunda7WorkflowProcesses#shareTheWorkflowAggregate}. This class only walks the
 * hierarchy. A process this application does not claim is unknown to the core, and a call from
 * such a process starts a case of its own.
 * <p>
 * The engine offers two ways to walk. While it runs a command, the executions of the command are
 * at hand, and an instance which started in the same command is not in the history yet. So a
 * report at an event walks the executions. Outside of such a command, the history is the only
 * place which still knows an instance which ended, so a read walks the history.
 */
final class Camunda7BusinessCases {

  private Camunda7BusinessCases() {
  }

  /**
   * One process instance as the walk needs it.
   *
   * @param id The id of the instance
   * @param tenantId Its tenant, or <code>null</code>
   * @param processDefinitionKey The key of its process definition
   * @param callerId The id of the instance which called it, or <code>null</code> where nobody
   *          did
   */
  record Instance(
                  String id,
                  String tenantId,
                  String processDefinitionKey,
                  String callerId) {
  }

  /**
   * The business case of the instance an execution belongs to, walked along the executions the
   * engine holds. This is the way for a report built while the engine runs a command.
   *
   * @param scope The adapter the execution belongs to
   * @param processes The claimed processes, which also reach the core
   * @param execution Any execution of the instance
   * @return The id of the instance which is the case
   */
  static String caseOf(
      final Camunda7Scope scope,
      final Camunda7WorkflowProcesses processes,
      final ExecutionEntity execution) {

    // an execution which is its own process instance may answer nothing here
    var theCase = execution.getProcessInstance() == null
        ? execution
        : execution.getProcessInstance();
    while (true) {
      // the caller is the instance of the super execution, which is the call activity's
      final var superExecution = theCase.getSuperExecution();
      final var caller = superExecution == null
          ? null
          : superExecution.getProcessInstance();
      if ((caller == null) || !shareTheAggregate(scope, processes, instanceOf(caller), instanceOf(theCase))) {
        return theCase.getProcessInstanceId();
      }
      theCase = caller;
    }

  }

  /**
   * The business case of an instance, walked along the history. This is the way for a read
   * outside of a report at an event.
   *
   * @param scope The adapter the instance belongs to
   * @param processes The claimed processes, which also reach the core
   * @param history The history of the engine
   * @param instance The instance
   * @return The id of the instance which is the case, or <code>null</code> where the instance is
   *         <code>null</code>
   */
  static String caseOf(
      final Camunda7Scope scope,
      final Camunda7WorkflowProcesses processes,
      final HistoryService history,
      final HistoricProcessInstance instance) {

    if (instance == null) {
      return null;
    }
    return caseOf(scope, processes, instanceOf(instance), callerId -> callerOf(history, callerId));

  }

  /**
   * Whether an instance which another one called is a case of its own. That is the case where it
   * does not share the workflow aggregate of its caller. Nothing above the caller matters for
   * this question.
   *
   * @param scope The adapter the instances belong to
   * @param processes The claimed processes, which also reach the core
   * @param called The called instance
   * @param caller The calling instance, or <code>null</code> where the engine does not know it
   * @return Whether the called instance is a case
   */
  static boolean isACase(
      final Camunda7Scope scope,
      final Camunda7WorkflowProcesses processes,
      final Instance called,
      final Instance caller) {

    return (caller == null) || !shareTheAggregate(scope, processes, caller, called);

  }

  /**
   * The calling instance as the walk needs it, read from the executions of the running command.
   * The caller is running while the instance it called starts or ends, so the command holds it.
   *
   * @param callerProcessInstanceId The id of the calling process instance
   * @return The caller, or <code>null</code> where nobody called or the command does not hold it
   */
  static Instance callerInTheRunningCommand(
      final String callerProcessInstanceId) {

    final var command = Context.getCommandContext();
    if ((callerProcessInstanceId == null) || (command == null)) {
      return null;
    }
    return instanceOf(command.getExecutionManager().findExecutionById(callerProcessInstanceId));

  }

  /**
   * The calling instance as the walk needs it, read from the history.
   *
   * @param history The history of the engine
   * @param callerProcessInstanceId The id of the calling process instance
   * @return The caller, or <code>null</code> where the history does not hold it
   */
  static Instance callerOf(
      final HistoryService history,
      final String callerProcessInstanceId) {

    if (callerProcessInstanceId == null) {
      return null;
    }
    return instanceOf(
        history
            .createHistoricProcessInstanceQuery()
            .processInstanceId(callerProcessInstanceId)
            .singleResult());

  }

  static Instance instanceOf(
      final HistoricProcessInstance instance) {

    return instance == null
        ? null
        : new Instance(
            instance.getId(), instance.getTenantId(), instance.getProcessDefinitionKey(), instance
                .getSuperProcessInstanceId());

  }

  static Instance instanceOf(
      final ExecutionEntity processInstance) {

    if (processInstance == null) {
      return null;
    }
    final var definition = processInstance.getProcessDefinition();
    final var superExecution = processInstance.getSuperExecution();
    return new Instance(
        processInstance.getProcessInstanceId(), definition == null
            ? processInstance.getTenantId()
            : definition.getTenantId(), definition == null
                ? null
                : definition.getKey(), superExecution == null
                    ? null
                    : superExecution.getProcessInstanceId());

  }

  private static String caseOf(
      final Camunda7Scope scope,
      final Camunda7WorkflowProcesses processes,
      final Instance start,
      final Function<String, Instance> instanceWithId) {

    var theCase = start;
    // a hierarchy has no cycle, but a walk which met one would never end
    final var visited = new java.util.HashSet<String>();
    while ((theCase != null) && (theCase.callerId() != null) && visited.add(theCase.id())) {
      final var caller = instanceWithId.apply(theCase.callerId());
      if ((caller == null) || !shareTheAggregate(scope, processes, caller, theCase)) {
        break;
      }
      theCase = caller;
    }
    return theCase == null
        ? null
        : theCase.id();

  }

  private static boolean shareTheAggregate(
      final Camunda7Scope scope,
      final Camunda7WorkflowProcesses processes,
      final Instance caller,
      final Instance called) {

    final var calling = processes.resolve(scope, caller.tenantId(), caller.processDefinitionKey());
    final var calledProcess = processes.resolve(scope, called.tenantId(), called.processDefinitionKey());
    return calling.isPresent() && calledProcess.isPresent() && processes
        .shareTheWorkflowAggregate(calling.get(), calledProcess.get());

  }

}
