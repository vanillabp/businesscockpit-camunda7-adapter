package io.vanillabp.cockpit.camunda7.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.camunda.bpm.engine.impl.history.event.HistoricTaskInstanceEventEntity;
import org.camunda.bpm.engine.impl.history.event.HistoryEventTypes;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.cockpit.camunda7.Camunda7CockpitEvents;
import io.vanillabp.cockpit.camunda7.Camunda7Scope;
import io.vanillabp.cockpit.camunda7.Camunda7WorkflowHistoryHandler;
import io.vanillabp.cockpit.camunda7.Camunda7WorkflowProcesses;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an engine reports and what the cockpit is told about it, without an engine.
 * <p>
 * The two integration tests of this repository run the happy path through a real engine. What they
 * cannot provoke is the other half: an event of a process this application never deployed, an
 * instance somebody started without a business key, a called process. Those are the cases where the
 * right behaviour is to report nothing, and a test which cannot tell "reported nothing" from "was
 * never asked" would not notice them.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7CockpitEventsTest {

  private static final String ADAPTER_ID = "c7";

  private static final String MODULE_ID = "a-module";

  private static final String BPMN_PROCESS_ID = "AProcess";

  private RecordingPublisher publisher;

  private Camunda7WorkflowProcesses processes;

  private Camunda7CockpitEvents events;

  @BeforeEach
  public void anEngineWithOneDeployedProcess() {

    publisher = new RecordingPublisher();
    processes = new Camunda7WorkflowProcesses();
    processes.register(MODULE_ID, BPMN_PROCESS_ID);
    // the real name-clash avoidance of an application which configured none, which resolves to
    // 'by-adapter': the workflow module is a tenant of its own and the process id stays plain
    events = new Camunda7CockpitEvents(
        new Camunda7Scope(
            ADAPTER_ID, new NameClashAvoidanceService(new MigrationAdapterProperties()), null), processes, () -> publisher, EventTransaction.CURRENT);

  }

  private DelegateTask aTaskOf(
      final String tenantId,
      final String processDefinitionKey,
      final String businessKey) {

    final var definition = mock(ProcessDefinitionEntity.class);
    when(definition.getTenantId()).thenReturn(tenantId);
    when(definition.getKey()).thenReturn(processDefinitionKey);

    final var execution = mock(ExecutionEntity.class);
    when(execution.getProcessDefinition()).thenReturn(definition);
    when(execution.getBusinessKey()).thenReturn(businessKey);
    when(execution.getProcessInstanceId()).thenReturn("the-instance");

    final var task = mock(DelegateTask.class);
    when(task.getExecution()).thenReturn(execution);
    when(task.getId()).thenReturn("the-task");
    when(task.getEventName()).thenReturn(TaskListener.EVENTNAME_CREATE);
    return task;

  }

  private HistoricProcessInstanceEventEntity anInstanceEvent(
      final HistoryEventTypes type,
      final String tenantId,
      final String processDefinitionKey,
      final String businessKey) {

    final var event = new HistoricProcessInstanceEventEntity();
    event.setId("history-event-1");
    event.setEventType(type.getEventName());
    event.setTenantId(tenantId);
    event.setProcessDefinitionKey(processDefinitionKey);
    event.setBusinessKey(businessKey);
    event.setProcessInstanceId("the-instance");
    event.setStartTime(new Date());
    return event;

  }

  @Test
  @DisplayName("A user task of a deployed process is reported with the identifiers the cockpit addresses it by")
  public void aUserTaskIsReported() {

    events
        .reportUserTask(
            aTaskOf(MODULE_ID, BPMN_PROCESS_ID, "4711"), "TheTask", "the-form",
            UserTaskEventKind.CREATED);

    assertEquals(1, publisher.userTasks().size());
    final var reported = publisher.userTasks().getFirst();
    assertEquals(ADAPTER_ID, reported.adapterId());
    assertEquals(MODULE_ID, reported.workflowModuleId());
    assertEquals(BPMN_PROCESS_ID, reported.bpmnProcessId());
    assertEquals("4711", reported.workflowAggregateId());
    assertEquals("the-instance", reported.workflowId());
    assertEquals("the-task", reported.userTaskId());
    assertEquals("the-form", reported.taskDefinition());
    assertEquals("TheTask", reported.bpmnTaskId());
    assertEquals("the-task#create", publisher.userTaskEventIds().getFirst());

  }

  @Test
  @DisplayName("A user task of a process this application never deployed is passed over")
  public void aForeignUserTaskIsNotReported() {

    events
        .reportUserTask(
            aTaskOf(MODULE_ID, "SomebodyElsesProcess", "4711"), "TheTask", "the-form",
            UserTaskEventKind.CREATED);
    events
        .reportUserTask(
            aTaskOf("another-tenant", BPMN_PROCESS_ID, "4711"), "TheTask", "the-form",
            UserTaskEventKind.CREATED);

    assertTrue(publisher.userTasks().isEmpty(), publisher.userTasks().toString());

  }

  @Test
  @DisplayName("A user task of an instance without a business key names no aggregate and is passed over")
  public void aUserTaskWithoutABusinessKeyIsNotReported() {

    events
        .reportUserTask(
            aTaskOf(MODULE_ID, BPMN_PROCESS_ID, null), "TheTask", "the-form",
            UserTaskEventKind.CREATED);

    assertTrue(publisher.userTasks().isEmpty(), publisher.userTasks().toString());

  }

  @Test
  @DisplayName("A started workflow is reported as created, and carries the history event's own id")
  public void aStartedWorkflowIsReported() {

    final var event = anInstanceEvent(
        HistoryEventTypes.PROCESS_INSTANCE_START, MODULE_ID, BPMN_PROCESS_ID, "4711");

    new Camunda7WorkflowHistoryHandler(events).handleEvent(event);

    assertEquals(1, publisher.workflows().size());
    assertEquals(WorkflowEventKind.CREATED, publisher.workflowKinds().getFirst());
    assertEquals("history-event-1", publisher.workflowEventIds().getFirst());
    assertEquals("the-instance", publisher.workflows().getFirst().workflowId());
    assertEquals("4711", publisher.workflows().getFirst().workflowAggregateId());
    assertNotNull(publisher.workflowTimestamps().getFirst());

  }

  @Test
  @DisplayName("An ended workflow is completed, and cancelled where something ended it from outside")
  public void anEndedWorkflowIsCompletedOrCancelled() {

    final var completed = anInstanceEvent(
        HistoryEventTypes.PROCESS_INSTANCE_END, MODULE_ID, BPMN_PROCESS_ID, "4711");
    completed.setEndTime(new Date());
    final var cancelled = anInstanceEvent(
        HistoryEventTypes.PROCESS_INSTANCE_END, MODULE_ID, BPMN_PROCESS_ID, "4712");
    cancelled.setEndTime(new Date());
    cancelled.setDeleteReason("an operator deleted it");
    final var updated = anInstanceEvent(
        HistoryEventTypes.PROCESS_INSTANCE_UPDATE, MODULE_ID, BPMN_PROCESS_ID, "4713");

    final var handler = new Camunda7WorkflowHistoryHandler(events);
    handler.handleEvents(List.of(completed, cancelled, updated));

    assertEquals(
        List
            .of(WorkflowEventKind.COMPLETED, WorkflowEventKind.CANCELLED, WorkflowEventKind.UPDATED),
        publisher.workflowKinds());

  }

  @Test
  @DisplayName("A called process is a step of a case rather than a case, and is passed over")
  public void aCalledProcessIsNotReported() {

    final var event = anInstanceEvent(
        HistoryEventTypes.PROCESS_INSTANCE_START, MODULE_ID, BPMN_PROCESS_ID, "4711");
    event.setSuperProcessInstanceId("the-calling-instance");

    new Camunda7WorkflowHistoryHandler(events).handleEvent(event);

    assertTrue(publisher.workflows().isEmpty(), publisher.workflows().toString());

  }

  @Test
  @DisplayName("A workflow of a foreign process or without a business key is passed over")
  public void aForeignWorkflowIsNotReported() {

    final var handler = new Camunda7WorkflowHistoryHandler(events);
    handler
        .handleEvent(
            anInstanceEvent(
                HistoryEventTypes.PROCESS_INSTANCE_START, MODULE_ID, "SomebodyElsesProcess",
                "4711"));
    handler
        .handleEvent(
            anInstanceEvent(
                HistoryEventTypes.PROCESS_INSTANCE_START, MODULE_ID, BPMN_PROCESS_ID, null));

    assertTrue(publisher.workflows().isEmpty(), publisher.workflows().toString());

  }

  @Test
  @DisplayName("Anything the engine reports which is not about a process instance is left alone")
  public void otherHistoryEventsAreLeftAlone() {

    new Camunda7WorkflowHistoryHandler(events)
        .handleEvent(new HistoricTaskInstanceEventEntity());

    assertTrue(publisher.workflows().isEmpty(), publisher.workflows().toString());
    assertTrue(publisher.userTasks().isEmpty(), publisher.userTasks().toString());

  }

  @Test
  @DisplayName("The engine of this scope is the one the events name")
  public void theScopeIsTheEnginesOwn() {

    assertEquals(ADAPTER_ID, events.scope().adapterId());
    assertEquals(MODULE_ID, events.scope().tenantIdOf(MODULE_ID));
    assertEquals(BPMN_PROCESS_ID, events.scope().scopedProcessIdOf(MODULE_ID, BPMN_PROCESS_ID));

  }

  @Test
  @DisplayName("A process the deployment never mentioned resolves to nothing")
  public void anUnknownProcessResolvesToNothing() {

    assertTrue(processes.resolve(events.scope(), MODULE_ID, null).isEmpty());
    assertTrue(processes.resolve(events.scope(), null, BPMN_PROCESS_ID).isEmpty());
    // registering the same process again changes nothing, which is what a second adapter
    // deploying the same workflow module does
    processes.register(MODULE_ID, BPMN_PROCESS_ID);
    assertEquals(
        Optional.of(new Camunda7WorkflowProcesses.WorkflowProcess(MODULE_ID, BPMN_PROCESS_ID)),
        processes.resolve(events.scope(), MODULE_ID, BPMN_PROCESS_ID));

  }

  @Test
  @DisplayName("A workflow event of an instance the engine timed differently still carries a timestamp")
  public void anEventWithoutATimeIsStillTimed() {

    final var event = anInstanceEvent(
        HistoryEventTypes.PROCESS_INSTANCE_UPDATE, MODULE_ID, BPMN_PROCESS_ID, "4711");
    event.setStartTime(null);

    new Camunda7WorkflowHistoryHandler(events).handleEvent(event);

    final var reported = publisher.workflowTimestamps().getFirst();
    assertTrue(
        reported.isAfter(OffsetDateTime.now().minusMinutes(1)),
        "the timestamp was not filled in: %s".formatted(reported));

  }

}
