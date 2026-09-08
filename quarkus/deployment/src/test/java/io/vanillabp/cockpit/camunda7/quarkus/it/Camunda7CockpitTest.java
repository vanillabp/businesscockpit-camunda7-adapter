package io.vanillabp.cockpit.camunda7.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import io.vanillabp.cockpit.camunda7.Camunda7UserTaskListener;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * The Camunda 7 half of the Business Cockpit inside a booted Quarkus application: the extension
 * is enabled, its listeners sit on the deployed user tasks, what a workflow does reaches the
 * cockpit server, and the engine answers what the cockpit reads back.
 * <p>
 * It runs the same way through as the Spring Boot test of this repository, and it exists because
 * a platform-neutral half being right says nothing about a platform's glue ever calling it.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7CockpitTest {

  private static final String ADAPTER_ID = "c7";

  private static final String MODULE_ID = "c7-cockpit";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(
          jar -> jar
              .addAsResource("business-cockpit.yaml", "application.yaml")
              .addAsResource("c7-cockpit/processes/cockpit-process.bpmn")
              .addAsResource(
                  "workflow-module-descriptor/workflow-module", "META-INF/workflow-module")
              .addClass(TestAggregate.class)
              .addClass(TestAggregatePersistence.class)
              .addClass(TestWorkflowService.class)
              // the test class runs in the application's class loader, so the server it asks
              // about what arrived has to be reachable from there as well
              .addClass(CockpitServer.class))
      .overrideRuntimeConfigKey(
          "vanillabp.extensions.business-cockpit.rest.base-url", CockpitServer.baseUrl());

  @Inject
  TestWorkflowService workflowService;

  @Inject
  TestAggregatePersistence aggregates;

  @Inject
  Camunda7QuarkusEngineRegistry engines;

  @Inject
  List<BusinessCockpitBpmsBridge> bridges;

  @Inject
  UserTransaction transaction;

  private ProcessEngine engine() {

    return engines.engineFor(ADAPTER_ID).getProcessEngine();

  }

  private BusinessCockpitBpmsBridge bridge() {

    return bridges
        .stream()
        .filter(candidate -> ADAPTER_ID.equals(candidate.adapterId()))
        .findFirst()
        .orElseThrow();

  }

  private TestAggregate aStartedWorkflow(
      final String customer) throws Exception {

    transaction.begin();
    try {
      final var aggregate = new TestAggregate();
      aggregate.setCustomer(customer);
      final var started = workflowService.processes().startWorkflow(aggregate);
      transaction.commit();
      return started;
    } catch (final RuntimeException e) {
      transaction.rollback();
      throw e;
    }

  }

  private String userTaskIdOf(
      final TestAggregate aggregate) {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var task = engine()
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

    return engine()
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
  @DisplayName("The cockpit's task listeners are built-in and run behind VanillaBP's own")
  public void theCockpitListenersRunLastAndAreBuiltIn() {

    final var definitionId = engine()
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey(TestWorkflowService.BPMN_PROCESS_ID)
        .latestVersion()
        .singleResult()
        .getId();
    final var deployed = (ProcessDefinitionEntity) engine()
        .getRepositoryService()
        .getProcessDefinition(definitionId);
    final var taskDefinition = ((UserTaskActivityBehavior) deployed
        .findActivity(TestWorkflowService.BPMN_TASK_ID)
        .getActivityBehavior()).getTaskDefinition();

    Stream
        .of(
            TaskListener.EVENTNAME_CREATE, TaskListener.EVENTNAME_UPDATE,
            TaskListener.EVENTNAME_COMPLETE, TaskListener.EVENTNAME_DELETE)
        .forEach(eventName -> {
          final var listeners = taskDefinition
              .getBuiltinTaskListeners()
              .getOrDefault(eventName, List.of());
          assertFalse(listeners.isEmpty(), "no built-in listener for '%s'".formatted(eventName));
          assertInstanceOf(
              Camunda7UserTaskListener.class,
              listeners.getLast(),
              "the cockpit's listener is not the last one for '%s': %s"
                  .formatted(eventName, listeners));
        });

  }

  @Test
  @DisplayName("A started workflow and its user task reach the cockpit, enriched by the application")
  public void aStartedWorkflowReachesTheCockpit() throws Exception {

    CockpitServer.forgetRequests();

    final var started = aStartedWorkflow("Anna");
    userTaskIdOf(started);

    final var workflow = CockpitServer.awaitRequest("/workflow/created");
    assertTrue(workflow.body().contains("\"customer\":\"Anna\""), workflow.body());
    assertTrue(
        workflow.body().contains("\"businessId\":\"%s\"".formatted(started.getId())),
        workflow.body());

    final var userTask = CockpitServer.awaitRequest("/usertask/created");
    assertTrue(userTask.body().contains("\"customer\":\"Anna\""), userTask.body());
    assertTrue(
        userTask
            .body()
            .contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        userTask.body());
    assertTrue(userTask.body().contains("Approve the order"), userTask.body());

    assertEquals(
        TestWorkflowService.APPROVE_NOTE,
        aggregates.byId(started.getId()).getNote(),
        "the details provider did not change the workflow aggregate");

  }

  @Test
  @DisplayName("Completing the user task reports the task and the workflow as completed")
  public void completingTheUserTaskIsReported() throws Exception {

    final var aggregate = aStartedWorkflow("Bert");
    final var userTaskId = userTaskIdOf(aggregate);
    final var workflowId = workflowIdOf(aggregate);

    engine().getTaskService().complete(userTaskId);

    CockpitServer.awaitRequest("/usertask/%s/completed".formatted(userTaskId));
    CockpitServer.awaitRequest("/workflow/%s/completed".formatted(workflowId));

  }

  @Test
  @DisplayName("Deleting the workflow reports the task and the workflow as cancelled")
  public void cancellingTheWorkflowIsReported() throws Exception {

    final var aggregate = aStartedWorkflow("Cleo");
    final var userTaskId = userTaskIdOf(aggregate);
    final var workflowId = workflowIdOf(aggregate);

    engine().getRuntimeService().deleteProcessInstance(workflowId, "the test cancelled it");

    CockpitServer.awaitRequest("/usertask/%s/cancelled".formatted(userTaskId));
    CockpitServer.awaitRequest("/workflow/%s/cancelled".formatted(workflowId));

  }

  @Test
  @DisplayName("An operator changing the task through the engine API alone still produces an update")
  public void aManualOperationIsReported() throws Exception {

    final var aggregate = aStartedWorkflow("Dora");
    final var userTaskId = userTaskIdOf(aggregate);

    engine().getTaskService().setAssignee(userTaskId, "anna");

    final var updated = CockpitServer.awaitRequest("/usertask/%s/updated".formatted(userTaskId));
    assertTrue(updated.body().contains("\"assignee\":\"anna\""), updated.body());

  }

  @Test
  @DisplayName("A multi-instance user task carries the item, the index and the total")
  public void aMultiInstanceUserTaskCarriesItsContext() throws Exception {

    transaction.begin();
    final var aggregate = new TestAggregate();
    aggregate.setCustomer("Jonas");
    aggregate.setSigners(List.of("anna", "bert", "cleo"));
    aggregates.save(aggregate);
    transaction.commit();

    // started through the engine rather than through VanillaBP: the multi-instance process is a
    // secondary process of this aggregate, and what is under test is what the listener reports
    engine()
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
          .received()
          .stream()
          .filter(request -> request.path().endsWith("/usertask/created"))
          .map(CockpitServer.Request::body)
          .filter(
              body -> body
                  .contains(
                      "\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.SIGN_TASK_DEFINITION)))
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
  @DisplayName("The engine answers what the cockpit reads back about a case")
  public void theEngineAnswersWhatTheCockpitReadsBack() throws Exception {

    final var aggregate = aStartedWorkflow("Mira");
    final var userTaskId = userTaskIdOf(aggregate);
    final var workflowId = workflowIdOf(aggregate);
    final var aggregateId = String.valueOf(aggregate.getId());

    assertEquals(ADAPTER_ID, bridge().adapterId());
    assertEquals("camunda7", bridge().adapterType());

    final var userTasks = bridge()
        .userTasksOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId, List.of());
    assertEquals(1, userTasks.size(), userTasks.toString());
    assertEquals(userTaskId, userTasks.getFirst().userTaskId());

    final var workflows = bridge()
        .workflowsOfAggregate(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId);
    assertEquals(1, workflows.size(), workflows.toString());
    assertEquals(workflowId, workflows.getFirst().workflowId());
    assertTrue(bridge().prefilledWorkflowDetails(workflows.getFirst()).isPresent());

    assertTrue(
        bridge()
            .userTaskOfAggregate(
                MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId, userTaskId)
            .isPresent());

    // and a task the engine has finished with is read from its history rather than dropped
    engine().getTaskService().complete(userTaskId);
    final var prefill = bridge()
        .prefilledUserTaskDetails(
            new UserTaskReference(
                ADAPTER_ID, MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId, workflowId, userTaskId, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_TASK_ID));
    assertTrue(prefill.isPresent(), "the finished task was not found in history");
    assertEquals("Approve the order", prefill.get().bpmnTaskName());

  }

  @Test
  @DisplayName("What the engine never knew is answered as empty, not as a failure")
  public void whatTheEngineDoesNotKnowIsEmpty() throws Exception {

    final var aggregate = aStartedWorkflow("Nils");
    userTaskIdOf(aggregate);
    final var aggregateId = String.valueOf(aggregate.getId());

    assertTrue(
        bridge()
            .prefilledUserTaskDetails(
                new UserTaskReference(
                    ADAPTER_ID, MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId, workflowIdOf(
                        aggregate), "no-such-task", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_TASK_ID))
            .isEmpty());
    assertTrue(
        bridge()
            .prefilledWorkflowDetails(
                new WorkflowReference(
                    ADAPTER_ID, MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId, "no-such-workflow"))
            .isEmpty());
    assertTrue(
        bridge()
            .userTaskOfAggregate(
                MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId, "no-such-task")
            .isEmpty());
    assertEquals(
        List.of(),
        bridge()
            .workflowsOfAggregate(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, "no-such-case"));

  }

  @Test
  @DisplayName("The workflow module registers itself at the cockpit server")
  public void theWorkflowModuleIsRegistered() {

    final var registration = CockpitServer.awaitRegistration();
    assertTrue(registration.path().endsWith("/workflow-module/%s".formatted(MODULE_ID)), registration.path());

  }

}
