package io.vanillabp.cockpit.camunda7;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricTaskInstance;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.task.IdentityLinkType;
import org.camunda.bpm.engine.task.Task;

import io.vanillabp.camunda7.Camunda7Adapter;
import io.vanillabp.camunda7.api.Camunda7EngineFacts;
import io.vanillabp.camunda7.api.Camunda7Executions;
import io.vanillabp.camunda7.api.Camunda7MultiInstances;
import io.vanillabp.camunda7.api.Camunda7TaskDefinitions;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.integration.extension.spi.handler.HandlerMultiInstance;

/**
 * What one configured Camunda 7 engine can be asked about a task or a workflow.
 * <p>
  * Everything here runs long after the event which caused the question. An outbox entry is
  * dispatched once the transaction the engine reported in was committed, and the three
  * <code>…OfAggregate</code> methods answer an application which just changed its aggregate. So
  * the state read here is the current one. A repeated report tells the cockpit what is true now,
  * not what was true when the entry was written.
 * <p>
  * A task the engine no longer holds is looked up in history instead. That is not an edge case. A
  * task somebody completed within a second of its creation is normal, and reporting its creation
  * from history is better than losing the task from the cockpit's list of what happened.
 */
public class Camunda7CockpitBridge implements BusinessCockpitBpmsBridge {

  /**
   * What the engine writes as the operation of an identity-link log entry when a candidate was
   * added; anything else in that column took one away. The engine's API names neither.
   */
  private static final String IDENTITY_LINK_ADDED = "add";

  private final Camunda7Scope scope;

  private final Camunda7EngineFacts engineFacts;

  private final Camunda7WorkflowProcesses processes;

  private final ProcessEngine engine;

  /**
   * @param scope The engine this bridge serves
   * @param engineFacts What the Camunda 7 adapter knows about that engine
   * @param processes The deployed processes, to translate the engine's identifiers back
   * @param engine The engine
   */
  public Camunda7CockpitBridge(
      final Camunda7Scope scope,
      final Camunda7EngineFacts engineFacts,
      final Camunda7WorkflowProcesses processes,
      final ProcessEngine engine) {

    this.scope = scope;
    this.engineFacts = engineFacts;
    this.processes = processes;
    this.engine = engine;

  }

  @Override
  public String adapterId() {

    return scope.adapterId();

  }

  @Override
  public String adapterType() {

    return Camunda7Adapter.ADAPTER_TYPE;

  }

  @Override
  public Optional<UserTaskDetailsPrefill> prefilledUserTaskDetails(
      final UserTaskReference userTask) {

    return inOneEngineCommand(() -> {
      final var task = engine
          .getTaskService()
          .createTaskQuery()
          .taskId(userTask.userTaskId())
          .singleResult();
      if (task != null) {
        return Optional.of(prefillOf(task, userTask.bpmnProcessId()));
      }
      final var historic = engine
          .getHistoryService()
          .createHistoricTaskInstanceQuery()
          .taskId(userTask.userTaskId())
          .singleResult();
      return Optional
          .ofNullable(historic)
          .map(gone -> prefillOf(gone, userTask.bpmnProcessId()));
    });

  }

  @Override
  public Optional<WorkflowDetailsPrefill> prefilledWorkflowDetails(
      final WorkflowReference workflow) {

    return inOneEngineCommand(() -> {
      final var instance = historicWorkflow(workflow.workflowId());
      if (instance == null) {
        return Optional.empty();
      }
      final var definition = definitionOf(instance.getProcessDefinitionId());
      return Optional
          .of(
              new WorkflowDetailsPrefill(
                  versionOf(instance.getProcessDefinitionId()), instance.getBusinessKey(), processNameOf(
                      definition, workflow.bpmnProcessId()), instance.getStartUserId()));
    });

  }

  @Override
  public List<WorkflowReference> workflowsOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    var query = engine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(workflowAggregateId)
        .processDefinitionKey(scope.scopedProcessIdOf(workflowModuleId, bpmnProcessId));
    final var tenantId = scope.tenantIdOf(workflowModuleId);
    query = tenantId != null
        ? query.tenantIdIn(tenantId)
        : query.withoutTenantId();

    return query
        .list()
        .stream()
        // the cockpit shows business cases; a called process is a step of one - see
        // decision 3 in the repository's DECISIONS.md
        .filter(instance -> instance.getSuperProcessInstanceId() == null)
        .map(
            instance -> new WorkflowReference(
                scope.adapterId(), workflowModuleId, bpmnProcessId, workflowAggregateId, instance
                    .getId()))
        .toList();

  }

  @Override
  public List<UserTaskReference> userTasksOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final List<String> userTaskIds) {

    var query = engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceBusinessKey(workflowAggregateId);
    final var tenantId = scope.tenantIdOf(workflowModuleId);
    query = tenantId != null
        ? query.tenantIdIn(tenantId)
        : query.withoutTenantId();
    if ((userTaskIds != null) && !userTaskIds.isEmpty()) {
      query = query.taskIdIn(userTaskIds.toArray(String[]::new));
    }
    final var tasksOfTheAggregate = query;

    return inOneEngineCommand(
        () -> tasksOfTheAggregate
            .list()
            .stream()
            .map(task -> referenceOf(task, workflowModuleId, workflowAggregateId))
            .flatMap(Optional::stream)
            .toList());

  }

  @Override
  public Optional<UserTaskReference> userTaskOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String userTaskId) {

    // the business key is part of the query rather than checked afterwards, so a task of
    // another case cannot be read by guessing its id
    var query = engine
        .getTaskService()
        .createTaskQuery()
        .taskId(userTaskId)
        .processInstanceBusinessKey(workflowAggregateId);
    final var tenantId = scope.tenantIdOf(workflowModuleId);
    query = tenantId != null
        ? query.tenantIdIn(tenantId)
        : query.withoutTenantId();
    final var theTask = query;

    return inOneEngineCommand(
        () -> Optional
            .ofNullable(theTask.singleResult())
            .flatMap(task -> referenceOf(task, workflowModuleId, workflowAggregateId)));

  }

  /**
    * A running task as the cockpit addresses it. Its BPMN process has to be one the asking
    * workflow module deployed. Under <code>none</code> and under <code>use-prefix</code> no
    * tenant separates the modules of an application. A business key which two of them use would
    * otherwise answer one module's question with another module's task.
   *
   * @param task The task the engine answered with
   * @param workflowModuleId The workflow module which was asked about
   * @param workflowAggregateId The aggregate which was asked about, which is the business key
   *          every one of these queries filtered on
   */
  private Optional<UserTaskReference> referenceOf(
      final Task task,
      final String workflowModuleId,
      final String workflowAggregateId) {

    final var definition = definitionOf(task.getProcessDefinitionId());
    if (definition == null) {
      return Optional.empty();
    }
    return processes
        .resolve(scope, definition.getTenantId(), definition.getKey())
        .filter(process -> process.workflowModuleId().equals(workflowModuleId))
        .map(
            process -> new UserTaskReference(
                scope.adapterId(), process.workflowModuleId(), process
                    .bpmnProcessId(), workflowAggregateId, Camunda7Executions
                        .rootProcessInstanceIdOf(
                            engine.getHistoryService(), task
                                .getProcessInstanceId()), task
                                    .getId(), taskDefinitionOf(task), task.getTaskDefinitionKey()));

  }

  private UserTaskDetailsPrefill prefillOf(
      final Task task,
      final String bpmnProcessId) {

    final var definition = definitionOf(task.getProcessDefinitionId());
    final var workflow = historicWorkflow(task.getProcessInstanceId());
    final var candidates = candidatesOf(task.getId());

    return UserTaskDetailsPrefill
        .builder()
        .bpmnProcessVersion(versionOf(task.getProcessDefinitionId()))
        .bpmnProcessName(processNameOf(definition, bpmnProcessId))
        .bpmnTaskName(task.getName())
        .workflowId(Camunda7Executions.rootProcessInstanceIdOf(workflow))
        .subWorkflowId(subWorkflowIdOf(workflow))
        .businessId(workflow == null ? null : workflow.getBusinessKey())
        .initiator(workflow == null ? null : workflow.getStartUserId())
        .assignee(task.getAssignee())
        .candidateUsers(candidates.users())
        .candidateGroups(candidates.groups())
        .dueDate(atOffset(task.getDueDate()))
        .followUpDate(atOffset(task.getFollowUpDate()))
        .variables(variablesOf(task.getId()))
        .multiInstances(multiInstancesOf(task.getExecutionId()))
        .build();

  }

  /**
    * A task the engine has finished with. History records what the task looked like, and, where
    * the engine keeps an identity-link log, who its candidates were. The variables it saw are not
    * read, so a details provider of such a task sees none.
   * <p>
    * The business case the task belongs to is read off the process instance rather than off the
    * task, although the task records it too. The instance is in hand here anyway, and taking it
    * from one place means one rule about what a root is.
   */
  private UserTaskDetailsPrefill prefillOf(
      final HistoricTaskInstance task,
      final String bpmnProcessId) {

    final var definition = definitionOf(task.getProcessDefinitionId());
    final var workflow = historicWorkflow(task.getProcessInstanceId());
    final var candidates = historicCandidatesOf(task.getId());

    return UserTaskDetailsPrefill
        .builder()
        .bpmnProcessVersion(versionOf(task.getProcessDefinitionId()))
        .bpmnProcessName(processNameOf(definition, bpmnProcessId))
        .bpmnTaskName(task.getName())
        .workflowId(Camunda7Executions.rootProcessInstanceIdOf(workflow))
        .subWorkflowId(subWorkflowIdOf(workflow))
        .businessId(workflow == null ? null : workflow.getBusinessKey())
        .initiator(workflow == null ? null : workflow.getStartUserId())
        .assignee(task.getAssignee())
        .candidateUsers(candidates.users())
        .candidateGroups(candidates.groups())
        .dueDate(atOffset(task.getDueDate()))
        .followUpDate(atOffset(task.getFollowUpDate()))
        .build();

  }

  /**
   * Everything a prefill reads, in one engine command.
   * <p>
    * A task, its process instance, its identity links, its variables and its execution tree are
    * five questions. Each of them opens a command of its own when it is asked through the
    * engine's services. The engine reuses a command context which is already open, so wrapping
    * them makes the five share one session and one transaction instead of taking one apiece.
   *
   * @param reads What is to be read while that command is open
   * @return Whatever those reads produced
   */
  private <T> T inOneEngineCommand(
      final Supplier<T> reads) {

    return ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
        .getCommandExecutorTxRequired()
        .execute(commandContext -> reads.get());

  }

  /**
    * Every variable the task can see, which is what a <code>&#64;TaskParam</code> parameter of a
    * details provider is bound from. A variable somebody set to <code>null</code> is handed over
    * as it is. The cockpit's neutral half binds the parameter to what the engine says, and a
    * variable dropped here would look exactly like one nobody ever set.
   */
  private Map<String, Object> variablesOf(
      final String taskId) {

    return engine.getTaskService().getVariables(taskId);

  }

  /** The users and the groups an identity link of a task may name. */
  private record Candidates(
                            List<String> users,
                            List<String> groups) {
  }

  private Candidates candidatesOf(
      final String taskId) {

    final var users = new LinkedList<String>();
    final var groups = new LinkedList<String>();
    engine
        .getTaskService()
        .getIdentityLinksForTask(taskId)
        .stream()
        .filter(link -> IdentityLinkType.CANDIDATE.equals(link.getType()))
        .forEach(link -> {
          if (link.getGroupId() != null) {
            groups.add(link.getGroupId());
          } else if (link.getUserId() != null) {
            users.add(link.getUserId());
          }
        });
    return new Candidates(List.copyOf(users), List.copyOf(groups));

  }

  /**
    * Who was a candidate for a task the engine no longer holds. The engine keeps a log of the
    * identity links it added and removed only at history level <code>full</code>. Below that the
    * log is empty, and a finished task is reported without candidates rather than with wrong
    * ones. Where there is a log, it is replayed in the order the engine wrote it, so a candidate
    * somebody took away again is not reported as one.
   */
  private Candidates historicCandidatesOf(
      final String taskId) {

    final var users = new LinkedHashSet<String>();
    final var groups = new LinkedHashSet<String>();
    engine
        .getHistoryService()
        .createHistoricIdentityLinkLogQuery()
        .taskId(taskId)
        .type(IdentityLinkType.CANDIDATE)
        .orderByTime()
        .asc()
        .list()
        .forEach(entry -> {
          final var named = entry.getGroupId() != null
              ? groups
              : users;
          final var candidate = entry.getGroupId() != null
              ? entry.getGroupId()
              : entry.getUserId();
          if (candidate == null) {
            return;
          }
          if (IDENTITY_LINK_ADDED.equals(entry.getOperationType())) {
            named.add(candidate);
          } else {
            named.remove(candidate);
          }
        });
    return new Candidates(List.copyOf(users), List.copyOf(groups));

  }

  private HistoricProcessInstance historicWorkflow(
      final String processInstanceId) {

    return processInstanceId == null
        ? null
        : engine
            .getHistoryService()
            .createHistoricProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .singleResult();

  }

  /** Named only where the task really sits in a called process. */
  private static String subWorkflowIdOf(
      final HistoricProcessInstance workflow) {

    if (workflow == null) {
      return null;
    }
    return workflow.getId().equals(Camunda7Executions.rootProcessInstanceIdOf(workflow))
        ? null
        : workflow.getId();

  }

  private ProcessDefinition definitionOf(
      final String processDefinitionId) {

    return processDefinitionId == null
        ? null
        : engine.getRepositoryService().getProcessDefinition(processDefinitionId);

  }

  /**
   * How Camunda counts the deployed process a workflow runs on, as an operator reads it.
   * <p>
    * The adapter resolved that definition when it first met it, and it answers every later
    * question about it from its own cache. A cockpit asking once per task and once per rendered
    * page therefore pays the engine for none of them. Turning the version and its tag into one
    * string is the platform's rule rather than this repository's, which is what makes one
    * deployment read the same however the cockpit heard about it.
   * <p>
    * Nothing is reported while the adapter has no deployment service for this adapter id yet, and
    * nothing for a definition the engine no longer holds. Every field of a prefill is optional,
    * so a version nobody can name is left out rather than guessed at.
   *
   * @param processDefinitionId The engine's process definition id
   * @return The version as an operator reads it, or <code>null</code>
   */
  private String versionOf(
      final String processDefinitionId) {

    final var deployed = engineFacts.definitionOf(processDefinitionId);
    return deployed == null
        ? null
        : deployed.displayVersion();

  }

  /**
   * The name the modeller wrote, which is what the cockpit shows where neither a template nor
   * a details provider produced a title. A model without a name falls back to its id.
   */
  private static String processNameOf(
      final ProcessDefinition definition,
      final String fallback) {

    if (definition == null) {
      return fallback;
    }
    final var name = definition.getName();
    return (name == null) || name.isBlank()
        ? fallback
        : name;

  }

  /**
   * What the cockpit calls a running task: the form key the modeller wrote, and the element id
   * where the model carries none.
   * <p>
    * The form key is read off the deployed process definition rather than off the task. A task
    * answers the form key its engine COMPUTED, and a form key which is an expression computes
    * another string per workflow instance. One task would then reach the cockpit under as many
    * identities as it has instances, and none of them would be the identity its listener reported
    * while the model was parsed. Reading the definition is also why no query here asks the engine
    * to evaluate form keys any more.
   *
   * @param task The task the engine answered with
   * @return What a details provider is matched by and what the cockpit shows a form for
   */
  private String taskDefinitionOf(
      final Task task) {

    return Camunda7TaskDefinitions
        .of(
            Camunda7TaskDefinitions
                .formKeyOf(engine, task.getProcessDefinitionId(), task.getTaskDefinitionKey()),
            task.getTaskDefinitionKey());

  }

  /**
   * The multi-instance scopes a user task runs in, as a details provider's
   * <code>&#64;MultiInstanceElement</code>, <code>&#64;MultiInstanceIndex</code> and
   * <code>&#64;MultiInstanceTotal</code> parameters are bound from.
   * <p>
    * The walk itself belongs to the Camunda 7 adapter. It reads the execution tree, which is the
    * engine knowledge a Camunda upgrade is most likely to invalidate, and the adapter does it for
    * its own task deliveries anyway.
   * <p>
    * What is left here is a copy from one record into another. The adapter answers what a BPMS
    * reports about a task, and the platform's handler layer takes what a call into application
    * code runs in. They are separate contracts, although they carry the same three values.
    * Copying them is done in this one place, and the map keeps the order the adapter promises,
    * outermost first.
   *
   * @param executionId The execution the user task runs in
   * @return The scopes, keyed by BPMN element id
   */
  private Map<String, HandlerMultiInstance> multiInstancesOf(
      final String executionId) {

    final var outermostFirst = new LinkedHashMap<String, HandlerMultiInstance>();
    Camunda7MultiInstances
        .of(engine, executionId)
        .forEach(
            (
                elementId,
                scope) -> outermostFirst
                    .put(
                        elementId,
                        new HandlerMultiInstance(scope.element(), scope.index(), scope.total())));
    return outermostFirst;

  }

  private static OffsetDateTime atOffset(
      final Date date) {

    return date == null
        ? null
        : date.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();

  }

}
