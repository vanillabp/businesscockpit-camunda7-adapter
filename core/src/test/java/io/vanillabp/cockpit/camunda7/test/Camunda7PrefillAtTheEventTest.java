package io.vanillabp.cockpit.camunda7.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.camunda.bpm.engine.impl.history.event.HistoryEventTypes;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandExecutor;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.engine.impl.persistence.entity.IdentityLinkEntity;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.camunda.bpm.engine.task.IdentityLinkType;
import org.camunda.bpm.engine.task.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.api.Camunda7EngineFacts;
import io.vanillabp.camunda7.wiring.Camunda7ProcessVersions;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.cockpit.camunda7.Camunda7CockpitBridge;
import io.vanillabp.cockpit.camunda7.Camunda7EventBeingReported;
import io.vanillabp.cockpit.camunda7.Camunda7Scope;
import io.vanillabp.cockpit.camunda7.Camunda7WorkflowProcesses;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the bridge answers while an event is being reported, without an engine.
 * <p>
 * The integration tests of this repository run the same thing through a real engine, and they run
 * it on the happy path. What is asserted here is the rule itself: while a listener reports an
 * event, the answer comes out of that event and the engine is not asked at all. An engine which
 * WAS asked would answer nothing at that moment, because the command it is in has not written its
 * history yet, and that is exactly the failure this class guards against.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7PrefillAtTheEventTest {

  private static final String ADAPTER_ID = "c7";

  private static final String MODULE_ID = "a-module";

  private static final String BPMN_PROCESS_ID = "AProcess";

  /** The engine's id of the one deployed process definition of this test. */
  private static final String DEFINITION_ID = "AProcess:4:1";

  /** What the engine counted that deployment as. */
  private static final String COUNTED_VERSION = "4";

  /** The tag the modeller gave it, which is part of the version a person reads. */
  private static final String VERSION_TAG = "release-1";

  private static final String INSTANCE_ID = "the-instance";

  private static final String TASK_ID = "the-task";

  private Camunda7EventBeingReported eventBeingReported;

  private Camunda7CockpitBridge bridge;

  private TaskService taskService;

  @BeforeEach
  public void aBridgeOnAnEngineNobodyMayQuery() {

    eventBeingReported = new Camunda7EventBeingReported();
    final var scoping = new NameClashAvoidanceService(new MigrationAdapterProperties());
    final var versions = new Camunda7ProcessVersions(
        ADAPTER_ID, null, (
            workflowModuleId,
            bpmnProcessId) -> bpmnProcessId, workflowModuleId -> workflowModuleId, null);
    versions
        .recordDeployed(MODULE_ID, BPMN_PROCESS_ID, DEFINITION_ID, Integer
            .parseInt(COUNTED_VERSION), VERSION_TAG);
    final var taskRegistry = new Camunda7TaskRegistry();
    taskRegistry.setProcessVersions(versions);
    final var engineFacts = new Camunda7EngineFacts(
        ADAPTER_ID, scoping, workflowModuleId -> null, taskRegistry);
    final var processes = new Camunda7WorkflowProcesses();
    processes.register(MODULE_ID, BPMN_PROCESS_ID);

    bridge = new Camunda7CockpitBridge(
        new Camunda7Scope(ADAPTER_ID, scoping, () -> engineFacts), engineFacts, processes, eventBeingReported, anEngine());

  }

  /**
   * An engine which answers nothing but runs the commands it is given, which is what the way
   * through the query does. Every test below either asserts that it was never asked, or asks it
   * on purpose.
   */
  private ProcessEngine anEngine() {

    taskService = mock(TaskService.class);
    final var identityService = mock(IdentityService.class);
    when(identityService.getCurrentAuthentication()).thenReturn(null);

    final var commandExecutor = mock(CommandExecutor.class);
    when(commandExecutor.execute(any()))
        .thenAnswer(invocation -> ((Command<?>) invocation.getArgument(0)).execute(null));
    final var configuration = mock(ProcessEngineConfigurationImpl.class);
    when(configuration.getCommandExecutorTxRequired()).thenReturn(commandExecutor);

    final var engine = mock(ProcessEngine.class);
    when(engine.getTaskService()).thenReturn(taskService);
    when(engine.getIdentityService()).thenReturn(identityService);
    when(engine.getProcessEngineConfiguration()).thenReturn(configuration);
    return engine;

  }

  private DelegateTask aTaskOfTheEvent() {

    final var definition = mock(ProcessDefinitionEntity.class);
    when(definition.getId()).thenReturn(DEFINITION_ID);
    when(definition.getKey()).thenReturn(BPMN_PROCESS_ID);
    when(definition.getName()).thenReturn("A process with a name");

    final var execution = mock(ExecutionEntity.class);
    when(execution.getProcessDefinition()).thenReturn(definition);
    when(execution.getProcessDefinitionId()).thenReturn(DEFINITION_ID);
    when(execution.getProcessInstanceId()).thenReturn(INSTANCE_ID);
    when(execution.getBusinessKey()).thenReturn("4711");

    final var task = mock(DelegateTask.class);
    when(task.getId()).thenReturn(TASK_ID);
    when(task.getExecution()).thenReturn(execution);
    when(task.getName()).thenReturn("Approve the order");
    when(task.getAssignee()).thenReturn("anna");
    when(task.getDueDate()).thenReturn(new Date());
    when(task.getVariables()).thenReturn(Map.of("customer", "Anna"));
    when(task.getCandidates())
        .thenReturn(
            Set
                .of(
                    aCandidate("approvers", null), aCandidate(null, "bert"), anAssignee()));
    return task;

  }

  private static IdentityLinkEntity aCandidate(
      final String groupId,
      final String userId) {

    final var link = new IdentityLinkEntity();
    link.setType(IdentityLinkType.CANDIDATE);
    link.setGroupId(groupId);
    link.setUserId(userId);
    return link;

  }

  private static IdentityLinkEntity anAssignee() {

    final var link = new IdentityLinkEntity();
    link.setType(IdentityLinkType.ASSIGNEE);
    link.setUserId("anna");
    return link;

  }

  private static UserTaskReference aReferenceTo(
      final String userTaskId) {

    return new UserTaskReference(
        ADAPTER_ID, MODULE_ID, BPMN_PROCESS_ID, COUNTED_VERSION, "4711", INSTANCE_ID, userTaskId, "the-form", "TheTask");

  }

  private static HistoricProcessInstanceEventEntity anInstanceEvent(
      final HistoryEventTypes type) {

    final var event = new HistoricProcessInstanceEventEntity();
    event.setId("history-event-1");
    event.setEventType(type.getEventName());
    event.setProcessInstanceId(INSTANCE_ID);
    event.setProcessDefinitionId(DEFINITION_ID);
    event.setProcessDefinitionKey(BPMN_PROCESS_ID);
    event.setProcessDefinitionName("A process with a name");
    event.setBusinessKey("4711");
    event.setStartTime(new Date());
    return event;

  }

  private static WorkflowReference aReferenceTo() {

    return new WorkflowReference(
        ADAPTER_ID, MODULE_ID, BPMN_PROCESS_ID, COUNTED_VERSION, "4711", INSTANCE_ID);

  }

  @Test
  @DisplayName("A user task is answered out of the event, and the engine is not asked")
  public void aUserTaskIsAnsweredOutOfItsEvent() {

    final var task = aTaskOfTheEvent();

    eventBeingReported
        .whileReporting(task, () -> {
          final var prefill = bridge.prefilledUserTaskDetails(aReferenceTo(TASK_ID)).orElseThrow();

          assertEquals("release-1:4", prefill.bpmnProcessVersion());
          assertEquals("A process with a name", prefill.bpmnProcessName());
          assertEquals("Approve the order", prefill.bpmnTaskName());
          assertEquals(INSTANCE_ID, prefill.workflowId());
          assertNull(prefill.subWorkflowId(), "a task of a root instance named a called workflow");
          assertEquals("4711", prefill.businessId());
          assertEquals("anna", prefill.assignee());
          assertEquals(List.of("approvers"), prefill.candidateGroups());
          assertEquals(List.of("bert"), prefill.candidateUsers());
          assertEquals(Map.of("customer", "Anna"), prefill.variables());
        });

    verify(taskService, never()).createTaskQuery();

  }

  @Test
  @DisplayName("A workflow is answered out of its history event, and a start names who started it")
  public void aWorkflowIsAnsweredOutOfItsEvent() {

    final var started = anInstanceEvent(HistoryEventTypes.PROCESS_INSTANCE_START);
    started.setStartUserId("anna");

    eventBeingReported
        .whileReporting(started, () -> {
          final var prefill = bridge.prefilledWorkflowDetails(aReferenceTo()).orElseThrow();

          assertEquals("release-1:4", prefill.bpmnProcessVersion());
          assertEquals("A process with a name", prefill.bpmnProcessName());
          assertEquals("4711", prefill.businessId());
          assertEquals("anna", prefill.initiator());
        });

  }

  @Test
  @DisplayName("A model without a name is reported under its BPMN process id")
  public void aModelWithoutANameFallsBackToItsId() {

    final var started = anInstanceEvent(HistoryEventTypes.PROCESS_INSTANCE_START);
    started.setProcessDefinitionName("  ");

    eventBeingReported
        .whileReporting(
            started,
            () -> assertEquals(
                BPMN_PROCESS_ID,
                bridge.prefilledWorkflowDetails(aReferenceTo()).orElseThrow().bpmnProcessName()));

  }

  @Test
  @DisplayName("A question about another task is answered by the engine, not by the event at hand")
  public void anotherTaskGoesToTheEngine() {

    final var query = mock(TaskQuery.class, org.mockito.Mockito.RETURNS_SELF);
    when(query.singleResult()).thenReturn(null);
    when(taskService.createTaskQuery()).thenReturn(query);

    eventBeingReported
        .whileReporting(
            aTaskOfTheEvent(),
            () -> assertTrue(
                bridge.prefilledUserTaskDetails(aReferenceTo("another-task")).isEmpty(),
                "a task the engine does not hold was answered"));

    verify(taskService).createTaskQuery();

  }

  @Test
  @DisplayName("Once the event was reported, nothing of it is left on the thread")
  public void theEventIsGoneAfterwards() {

    eventBeingReported.whileReporting(aTaskOfTheEvent(), () -> {
      // reported inside, so the nested report has to find its own event again afterwards
      eventBeingReported
          .whileReporting(
              anInstanceEvent(HistoryEventTypes.PROCESS_INSTANCE_START),
              () -> assertTrue(eventBeingReported.userTask(TASK_ID).isEmpty()));
      assertTrue(eventBeingReported.userTask(TASK_ID).isPresent());
    });

    assertTrue(eventBeingReported.userTask(TASK_ID).isEmpty());
    assertTrue(eventBeingReported.workflow(INSTANCE_ID).isEmpty());

  }

}
