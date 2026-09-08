package io.vanillabp.cockpit.camunda7;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricTaskInstance;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.task.IdentityLinkType;
import org.camunda.bpm.engine.task.Task;

import io.vanillabp.camunda7.Camunda7Adapter;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;

/**
 * What one configured Camunda 7 engine can be asked about a task or a workflow.
 * <p>
 * Everything here runs long after the event which caused the question: an outbox entry is
 * dispatched once the transaction the engine reported in was committed, and the three
 * <code>…OfAggregate</code> methods answer an application which just changed its aggregate. So
 * the state read here is the current one, and a repeated report tells the cockpit what is true
 * now rather than what was true when the entry was written.
 * <p>
 * A task the engine no longer holds is looked up in history instead. That is not an edge case:
 * a task somebody completed within a second of its creation is normal, and reporting its
 * creation from history is better than losing the task from the cockpit's list of what
 * happened.
 */
public class Camunda7CockpitBridge implements BusinessCockpitBpmsBridge {

  private final Camunda7Scope scope;

  private final Camunda7WorkflowProcesses processes;

  private final ProcessEngine engine;

  /**
   * @param scope The engine this bridge serves
   * @param processes The deployed processes, to translate the engine's identifiers back
   * @param engine The engine
   */
  public Camunda7CockpitBridge(
      final Camunda7Scope scope,
      final Camunda7WorkflowProcesses processes,
      final ProcessEngine engine) {

    this.scope = scope;
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

    final var task = engine
        .getTaskService()
        .createTaskQuery()
        .taskId(userTask.userTaskId())
        .initializeFormKeys()
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

  }

  @Override
  public Optional<WorkflowDetailsPrefill> prefilledWorkflowDetails(
      final WorkflowReference workflow) {

    return Optional
        .ofNullable(historicWorkflow(workflow.workflowId()))
        .map(
            instance -> new WorkflowDetailsPrefill(
                versionOf(definitionOf(instance.getProcessDefinitionId())), instance
                    .getBusinessKey(), processNameOf(
                        definitionOf(instance.getProcessDefinitionId()),
                        workflow.bpmnProcessId()), instance.getStartUserId()));

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
        .processInstanceBusinessKey(workflowAggregateId)
        .initializeFormKeys();
    final var tenantId = scope.tenantIdOf(workflowModuleId);
    query = tenantId != null
        ? query.tenantIdIn(tenantId)
        : query.withoutTenantId();
    if ((userTaskIds != null) && !userTaskIds.isEmpty()) {
      query = query.taskIdIn(userTaskIds.toArray(String[]::new));
    }

    return query
        .list()
        .stream()
        .map(this::referenceOf)
        .flatMap(Optional::stream)
        .toList();

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
        .processInstanceBusinessKey(workflowAggregateId)
        .initializeFormKeys();
    final var tenantId = scope.tenantIdOf(workflowModuleId);
    query = tenantId != null
        ? query.tenantIdIn(tenantId)
        : query.withoutTenantId();

    return Optional.ofNullable(query.singleResult()).flatMap(this::referenceOf);

  }

  /**
   * A running task as the cockpit addresses it. Its BPMN process has to be one this
   * application deployed, or the task belongs to something else running on the same engine.
   */
  private Optional<UserTaskReference> referenceOf(
      final Task task) {

    final var definition = definitionOf(task.getProcessDefinitionId());
    if (definition == null) {
      return Optional.empty();
    }
    return processes
        .resolve(scope, definition.getTenantId(), definition.getKey())
        .map(
            process -> new UserTaskReference(
                scope.adapterId(), process.workflowModuleId(), process.bpmnProcessId(), businessKeyOf(
                    task.getProcessInstanceId()), rootWorkflowIdOf(task.getProcessInstanceId()), task
                        .getId(), taskDefinitionOf(
                            task.getFormKey(), task.getTaskDefinitionKey()), task.getTaskDefinitionKey()));

  }

  private UserTaskDetailsPrefill prefillOf(
      final Task task,
      final String bpmnProcessId) {

    final var definition = definitionOf(task.getProcessDefinitionId());
    final var workflow = historicWorkflow(task.getProcessInstanceId());
    final var candidates = candidatesOf(task.getId());

    return UserTaskDetailsPrefill
        .builder()
        .bpmnProcessVersion(versionOf(definition))
        .bpmnProcessName(processNameOf(definition, bpmnProcessId))
        .bpmnTaskName(task.getName())
        .workflowId(workflow == null ? null : rootIdOf(workflow))
        .subWorkflowId(subWorkflowIdOf(workflow))
        .businessId(workflow == null ? null : workflow.getBusinessKey())
        .initiator(workflow == null ? null : workflow.getStartUserId())
        .assignee(task.getAssignee())
        .candidateUsers(candidates.users())
        .candidateGroups(candidates.groups())
        .dueDate(atOffset(task.getDueDate()))
        .followUpDate(atOffset(task.getFollowUpDate()))
        .variables(variablesOf(task.getId()))
        .multiInstances(Camunda7MultiInstances.of(engine, task.getExecutionId()))
        .build();

  }

  /**
   * A task the engine has finished with. History records what the task looked like, but not
   * who its candidates were nor which variables it saw, and the cockpit reports what there is.
   */
  private UserTaskDetailsPrefill prefillOf(
      final HistoricTaskInstance task,
      final String bpmnProcessId) {

    final var definition = definitionOf(task.getProcessDefinitionId());
    final var workflow = historicWorkflow(task.getProcessInstanceId());

    return UserTaskDetailsPrefill
        .builder()
        .bpmnProcessVersion(versionOf(definition))
        .bpmnProcessName(processNameOf(definition, bpmnProcessId))
        .bpmnTaskName(task.getName())
        .workflowId(task.getRootProcessInstanceId())
        .subWorkflowId(subWorkflowIdOf(workflow))
        .businessId(workflow == null ? null : workflow.getBusinessKey())
        .initiator(workflow == null ? null : workflow.getStartUserId())
        .assignee(task.getAssignee())
        .dueDate(atOffset(task.getDueDate()))
        .followUpDate(atOffset(task.getFollowUpDate()))
        .build();

  }

  /**
   * Every variable the task can see, which is what a <code>&#64;TaskParam</code> parameter of a
   * details provider is bound from.
   * <p>
   * A variable somebody set to <code>null</code> is dropped rather than carried: a parameter
   * bound from a variable which is not there receives <code>null</code> anyway, and the record
   * carrying these to the details provider does not take null values.
   */
  private Map<String, Object> variablesOf(
      final String taskId) {

    final var variables = new LinkedHashMap<String, Object>();
    engine
        .getTaskService()
        .getVariables(taskId)
        .forEach((
            name,
            value) -> {
          if (value != null) {
            variables.put(name, value);
          }
        });
    return variables;

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

  private String businessKeyOf(
      final String processInstanceId) {

    final var workflow = historicWorkflow(processInstanceId);
    return workflow == null
        ? null
        : workflow.getBusinessKey();

  }

  private String rootWorkflowIdOf(
      final String processInstanceId) {

    final var workflow = historicWorkflow(processInstanceId);
    return workflow == null
        ? processInstanceId
        : rootIdOf(workflow);

  }

  /**
   * The instance a business case is. Camunda records it, and it falls back to the instance
   * itself for a workflow started before the engine kept that column.
   */
  private static String rootIdOf(
      final HistoricProcessInstance workflow) {

    return workflow.getRootProcessInstanceId() == null
        ? workflow.getId()
        : workflow.getRootProcessInstanceId();

  }

  /** Named only where the task really sits in a called process. */
  private static String subWorkflowIdOf(
      final HistoricProcessInstance workflow) {

    if (workflow == null) {
      return null;
    }
    return workflow.getId().equals(rootIdOf(workflow))
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
   * How Camunda counts a deployed process, spelled the way version 1 spelled it so that a
   * cockpit server which has both is looking at one kind of string.
   */
  private static String versionOf(
      final ProcessDefinition definition) {

    if (definition == null) {
      return null;
    }
    final var versionTag = definition.getVersionTag();
    return (versionTag == null) || versionTag.isBlank()
        ? String.valueOf(definition.getVersion())
        : "%s:%d".formatted(versionTag, definition.getVersion());

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

  private static String taskDefinitionOf(
      final String formKey,
      final String bpmnTaskId) {

    return (formKey == null) || formKey.isBlank()
        ? bpmnTaskId
        : formKey;

  }

  private static OffsetDateTime atOffset(
      final Date date) {

    return date == null
        ? null
        : date.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();

  }

}
