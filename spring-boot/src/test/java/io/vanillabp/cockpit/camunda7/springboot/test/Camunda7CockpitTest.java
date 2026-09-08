package io.vanillabp.cockpit.camunda7.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.cockpit.camunda7.Camunda7UserTaskListener;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Business Cockpit on a real embedded Camunda 7 engine, from what the engine did to the
 * request the cockpit server receives.
 * <p>
 * Nothing on that way is faked but the cockpit server itself: the engine runs the workflow, its
 * built-in task listeners and its history handler write an outbox entry inside the engine's own
 * transaction, the entry is dispatched after that transaction committed, the engine is read
 * again, the application's details provider runs and changes the workflow aggregate, and what
 * arrives at the server is asserted.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: an engine outliving its test keeps its job executor running
// against a database the next class works on
@DirtiesContext
public class Camunda7CockpitTest {

  private static final String MODULE_ID = "c7-cockpit";

  @DynamicPropertySource
  static void cockpitServer(
      final DynamicPropertyRegistry registry) {

    registry.add("vanillabp.extensions.business-cockpit.rest.base-url", CockpitServer::baseUrl);

  }

  @Autowired
  private TestWorkflowService workflowService;

  @Autowired
  private TestAggregateRepository aggregates;

  @Autowired
  private TransactionTemplate transactions;

  @Autowired
  private ProcessEngine engine;

  @Autowired
  private ObjectProvider<BusinessCockpitBpmsBridge> bridges;

  @BeforeEach
  public void forgetWhatArrivedBefore() {

    CockpitServer.forgetRequests();

  }

  private TestAggregate aStartedWorkflow(
      final String customer) {

    return transactions
        .execute(status -> {
          final var aggregate = new TestAggregate();
          aggregate.setCustomer(customer);
          return workflowService.processes().startWorkflow(aggregate);
        });

  }

  private String userTaskIdOf(
      final TestAggregate aggregate) {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var task = engine
          .getTaskService()
          .createTaskQuery()
          .processInstanceBusinessKey(String.valueOf(aggregate.getId()))
          .singleResult();
      if (task != null) {
        return task.getId();
      }
      sleep();
    }
    throw new AssertionError(
        "The user task of aggregate %s never appeared".formatted(aggregate.getId()));

  }

  private String workflowIdOf(
      final TestAggregate aggregate) {

    return engine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregate.getId()))
        .singleResult()
        .getId();

  }

  private static void sleep() {

    try {
      Thread.sleep(200);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the engine", e);
    }

  }

  @Test
  @DisplayName("A started workflow and its user task reach the cockpit, enriched by the application")
  public void aStartedWorkflowReachesTheCockpit() {

    final var aggregate = aStartedWorkflow("Anna");
    userTaskIdOf(aggregate);

    final var workflow = CockpitServer.awaitRequest("/workflow/created");
    assertTrue(workflow.body().contains("\"customer\":\"Anna\""), workflow.body());
    assertTrue(
        workflow.body().contains("\"businessId\":\"%s\"".formatted(aggregate.getId())),
        workflow.body());
    assertTrue(workflow.body().contains(TestWorkflowService.BPMN_PROCESS_ID), workflow.body());

    final var userTask = CockpitServer.awaitRequest("/usertask/created");
    assertTrue(userTask.body().contains("\"customer\":\"Anna\""), userTask.body());
    assertTrue(userTask.body().contains("\"event\":\"CREATED\""), userTask.body());
    assertTrue(
        userTask.body().contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        userTask.body());
    assertTrue(userTask.body().contains("\"candidateGroups\":[\"approvers\"]"), userTask.body());
    // the BPMN name is what the cockpit falls back to when nothing else produced a title
    assertTrue(userTask.body().contains("Approve the order"), userTask.body());

    // the details provider ran on the real aggregate and its change was saved
    assertEquals(
        TestWorkflowService.APPROVE_NOTE,
        aggregates.findById(aggregate.getId()).orElseThrow().getNote());

  }

  @Test
  @DisplayName("Completing the user task reports the task and the workflow as completed")
  public void completingTheUserTaskIsReported() {

    final var aggregate = aStartedWorkflow("Bert");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequest("/usertask/created");

    final var workflowId = workflowIdOf(aggregate);

    engine.getTaskService().complete(userTaskId);

    assertNotNull(
        CockpitServer.awaitRequest("/usertask/%s/completed".formatted(userTaskId)));
    assertNotNull(
        CockpitServer.awaitRequest("/workflow/%s/completed".formatted(workflowId)));

  }

  @Test
  @DisplayName("Deleting the workflow reports the task and the workflow as cancelled")
  public void cancellingTheWorkflowIsReported() {

    final var aggregate = aStartedWorkflow("Cleo");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequest("/usertask/created");

    final var workflowId = workflowIdOf(aggregate);
    engine.getRuntimeService().deleteProcessInstance(workflowId, "the test cancelled it");

    CockpitServer.awaitRequest("/usertask/%s/cancelled".formatted(userTaskId));
    CockpitServer.awaitRequest("/workflow/%s/cancelled".formatted(workflowId));

  }

  @Test
  @DisplayName("An operator changing the task through the engine API alone still produces an update")
  public void aManualOperationIsReported() {

    final var aggregate = aStartedWorkflow("Dora");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequest("/usertask/created");
    CockpitServer.forgetRequests();

    // nothing of VanillaBP is involved here: this is what a task list or the Camunda web
    // application does, and the cockpit has to hear about it
    engine.getTaskService().setAssignee(userTaskId, "anna");

    final var updated = CockpitServer.awaitRequest("/usertask/%s/updated".formatted(userTaskId));
    assertTrue(updated.body().contains("\"assignee\":\"anna\""), updated.body());

  }

  @Test
  @DisplayName("An application reporting a changed aggregate updates its workflow")
  public void aggregateChangedUpdatesTheWorkflow() {

    final var aggregate = aStartedWorkflow("Emil");
    userTaskIdOf(aggregate);
    CockpitServer.awaitRequest("/workflow/created");
    final var workflowId = workflowIdOf(aggregate);
    CockpitServer.forgetRequests();

    transactions
        .executeWithoutResult(status -> {
          final var attached = aggregates.findById(aggregate.getId()).orElseThrow();
          attached.setCustomer("Emil the second");
          aggregates.save(attached);
          workflowService.businessCockpit().aggregateChanged(attached);
        });

    final var updated = CockpitServer.awaitRequest("/workflow/%s/updated".formatted(workflowId));
    assertTrue(updated.body().contains("Emil the second"), updated.body());

  }

  @Test
  @DisplayName("An application reporting a changed aggregate updates the named user task")
  public void aggregateChangedUpdatesTheNamedUserTask() {

    final var aggregate = aStartedWorkflow("Frida");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequest("/usertask/created");
    CockpitServer.forgetRequests();

    transactions
        .executeWithoutResult(status -> {
          final var attached = aggregates.findById(aggregate.getId()).orElseThrow();
          attached.setCustomer("Frida the second");
          aggregates.save(attached);
          workflowService.businessCockpit().aggregateChanged(attached, userTaskId);
        });

    final var updated = CockpitServer.awaitRequest("/usertask/%s/updated".formatted(userTaskId));
    assertTrue(updated.body().contains("Frida the second"), updated.body());

  }

  @Test
  @DisplayName("An application can read one user task of its own case, and no other")
  public void getUserTaskAnswersOnlyForItsOwnAggregate() {

    final var aggregate = aStartedWorkflow("Gustl");
    final var userTaskId = userTaskIdOf(aggregate);
    final var otherAggregate = aStartedWorkflow("Heidi");
    userTaskIdOf(otherAggregate);

    final var userTask = transactions
        .execute(
            status -> workflowService
                .businessCockpit()
                .getUserTask(aggregates.findById(aggregate.getId()).orElseThrow(), userTaskId));
    assertTrue(userTask.isPresent(), "the case's own task was not answered");
    assertEquals(userTaskId, userTask.get().getId());
    assertEquals(TestWorkflowService.BPMN_TASK_ID, userTask.get().getBpmnTaskId());
    assertEquals(TestWorkflowService.TASK_DEFINITION, userTask.get().getTaskDefinition());

    final var foreign = transactions
        .execute(
            status -> workflowService
                .businessCockpit()
                .getUserTask(
                    aggregates.findById(otherAggregate.getId()).orElseThrow(), userTaskId));
    assertTrue(foreign.isEmpty(), "a task of another case was answered");

  }

  @Test
  @DisplayName("Reading a user task reports nothing to the cockpit")
  public void readingAUserTaskReportsNothing() {

    final var aggregate = aStartedWorkflow("Ida");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequest("/usertask/created");
    CockpitServer.forgetRequests();

    transactions
        .execute(
            status -> workflowService
                .businessCockpit()
                .getUserTask(aggregates.findById(aggregate.getId()).orElseThrow(), userTaskId));

    CockpitServer.awaitQuiet();
    assertEquals(
        List.of(),
        CockpitServer.matching("/usertask/%s/updated".formatted(userTaskId)),
        "reading a task reported it as changed");

  }

  private BusinessCockpitBpmsBridge bridge() {

    return bridges
        .stream()
        .filter(candidate -> "c7".equals(candidate.adapterId()))
        .findFirst()
        .orElseThrow();

  }

  private UserTaskReference referenceOf(
      final TestAggregate aggregate,
      final String userTaskId) {

    return new UserTaskReference(
        "c7", MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(aggregate.getId()), workflowIdOf(
            aggregate), userTaskId, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_TASK_ID);

  }

  @Test
  @DisplayName("A multi-instance user task carries the item, the index and the total")
  public void aMultiInstanceUserTaskCarriesItsContext() {

    final var aggregate = transactions
        .execute(status -> {
          final var fresh = new TestAggregate();
          fresh.setCustomer("Jonas");
          fresh.setSigners(List.of("anna", "bert", "cleo"));
          return aggregates.save(fresh);
        });
    // started through the engine rather than through VanillaBP: the multi-instance process is a
    // secondary process of this aggregate, and what is under test is what the listener reports
    engine
        .getRuntimeService()
        .createProcessInstanceByKey(TestWorkflowService.MULTI_INSTANCE_PROCESS_ID)
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregate.getId()))
        .execute();

    final var bodies = awaitSignedTasks();
    assertTrue(
        bodies.stream().anyMatch(body -> body.contains("\"signer\":\"anna\"")), bodies.toString());
    assertTrue(
        bodies.stream().anyMatch(body -> body.contains("\"signer\":\"cleo\"")), bodies.toString());
    bodies.forEach(body -> assertTrue(body.contains("\"total\":\"3\""), body));

  }

  /**
   * The reports of the multi-instance task, told apart from whatever else a workflow of another
   * test may report while this one waits.
   */
  private List<String> awaitSignedTasks() {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var signed = CockpitServer
          .matching("/usertask/created")
          .stream()
          .map(CockpitServer.Request::body)
          .filter(
              body -> body
                  .contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.SIGN_TASK_DEFINITION)))
          .toList();
      if (signed.size() >= 3) {
        return signed;
      }
      sleep();
    }
    throw new AssertionError(
        "Fewer than three signing tasks were reported: %s"
            .formatted(CockpitServer.received().stream().map(CockpitServer.Request::path).toList()));

  }

  @Test
  @DisplayName("A task the engine has finished with is read from its history")
  public void aFinishedTaskIsReadFromHistory() {

    final var aggregate = aStartedWorkflow("Klara");
    final var userTaskId = userTaskIdOf(aggregate);
    final var reference = referenceOf(aggregate, userTaskId);
    engine.getTaskService().complete(userTaskId);

    final var prefill = bridge().prefilledUserTaskDetails(reference);

    assertTrue(prefill.isPresent(), "the finished task was not found in history");
    assertEquals("Approve the order", prefill.get().bpmnTaskName());
    assertEquals(String.valueOf(aggregate.getId()), prefill.get().businessId());
    assertEquals(List.of(), prefill.get().candidateUsers());

  }

  @Test
  @DisplayName("The workflows of an aggregate include the one which already ended")
  public void theWorkflowsOfAnAggregateIncludeTheFinishedOne() {

    final var aggregate = aStartedWorkflow("Lena");
    final var userTaskId = userTaskIdOf(aggregate);
    final var workflowId = workflowIdOf(aggregate);
    engine.getTaskService().complete(userTaskId);

    final var workflows = bridge()
        .workflowsOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(aggregate.getId()));

    assertEquals(1, workflows.size(), workflows.toString());
    assertEquals(workflowId, workflows.getFirst().workflowId());
    assertTrue(
        bridge()
            .prefilledWorkflowDetails(workflows.getFirst())
            .isPresent(),
        "the finished workflow was not readable");

  }

  @Test
  @DisplayName("The user tasks of an aggregate are answered without naming any of them")
  public void theUserTasksOfAnAggregateAreAnsweredWithoutNamingThem() {

    final var aggregate = aStartedWorkflow("Mira");
    final var userTaskId = userTaskIdOf(aggregate);

    final var userTasks = bridge()
        .userTasksOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(aggregate.getId()),
            List.of());

    assertEquals(1, userTasks.size(), userTasks.toString());
    assertEquals(userTaskId, userTasks.getFirst().userTaskId());
    assertEquals(TestWorkflowService.TASK_DEFINITION, userTasks.getFirst().taskDefinition());
    assertEquals("c7", bridge().adapterId());
    assertEquals("camunda7", bridge().adapterType());

  }

  @Test
  @DisplayName("What the engine never knew is answered as empty, not as a failure")
  public void whatTheEngineDoesNotKnowIsEmpty() {

    final var aggregate = aStartedWorkflow("Nils");
    userTaskIdOf(aggregate);

    assertTrue(
        bridge().prefilledUserTaskDetails(referenceOf(aggregate, "no-such-task")).isEmpty());
    assertTrue(
        bridge()
            .prefilledWorkflowDetails(
                new WorkflowReference(
                    "c7", MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String
                        .valueOf(aggregate.getId()), "no-such-workflow"))
            .isEmpty());
    assertTrue(
        bridge()
            .userTaskOfAggregate(
                MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(aggregate.getId()),
                "no-such-task")
            .isEmpty());
    assertEquals(
        List.of(),
        bridge()
            .workflowsOfAggregate(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, "no-such-case"));

  }

  @Test
  @DisplayName("The cockpit's task listeners are built-in and run behind VanillaBP's own")
  public void theCockpitListenersRunLastAndAreBuiltIn() {

    final var definitionId = engine
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey(TestWorkflowService.BPMN_PROCESS_ID)
        .latestVersion()
        .singleResult()
        .getId();
    final var deployed = (ProcessDefinitionEntity) engine
        .getRepositoryService()
        .getProcessDefinition(definitionId);
    final var behavior = (UserTaskActivityBehavior) deployed
        .findActivity(TestWorkflowService.BPMN_TASK_ID)
        .getActivityBehavior();
    final var taskDefinition = behavior.getTaskDefinition();

    // built-in rather than custom: an operator asking the engine to skip the custom listeners
    // of the model still produces the events the cockpit needs
    Stream
        .of(
            TaskListener.EVENTNAME_CREATE, TaskListener.EVENTNAME_UPDATE,
            TaskListener.EVENTNAME_COMPLETE, TaskListener.EVENTNAME_DELETE)
        .forEach(eventName -> {
          final var listeners = taskDefinition
              .getBuiltinTaskListeners()
              .getOrDefault(eventName, List.of());
          assertFalse(
              listeners.isEmpty(), "no built-in listener for '%s'".formatted(eventName));
          assertInstanceOf(
              Camunda7UserTaskListener.class,
              listeners.getLast(),
              "the cockpit's listener is not the last one for '%s': %s"
                  .formatted(eventName, listeners));
        });

    // and VanillaBP wired the task before the cockpit did, which is what a details provider
    // reading an aggregate a completed task just changed depends on
    final var created = taskDefinition
        .getBuiltinTaskListeners()
        .getOrDefault(TaskListener.EVENTNAME_CREATE, List.of());
    assertTrue(created.size() > 1, "VanillaBP's own CREATE listener is missing: %s".formatted(created));
    assertFalse(
        created.getFirst() instanceof Camunda7UserTaskListener,
        "the cockpit's listener runs first: %s".formatted(created));

  }

  @Test
  @DisplayName("The workflow module registers itself at the cockpit server")
  public void theWorkflowModuleIsRegistered() {

    final var registration = CockpitServer.awaitRegistration();
    assertTrue(registration.path().endsWith("/workflow-module/c7-cockpit"), registration.path());
    assertTrue(registration.body().contains("approvers"), registration.body());
    assertFalse(registration.body().isBlank());

  }

}
