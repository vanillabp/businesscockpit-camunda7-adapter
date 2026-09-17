package io.vanillabp.cockpit.camunda7.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Which details provider serves a case and its user task is decided by the version Camunda
 * counted for the deployed process, inside a booted Quarkus application.
 * <p>
 * It runs the same way through as the Spring Boot test of this repository. The workflow service
 * has two providers for its user task and two for its workflow, one of each pair for version 1
 * and the other for every version after it, and the test deploys a second generation of the
 * model to reach the second of each pair.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7ProcessVersionTest {

  private static final String ADAPTER_ID = "c7";

  private static final String MODULE_ID = "c7-cockpit";

  /** Where the model this application deploys is read from, to deploy a second one like it. */
  private static final String MODEL = "c7-cockpit/processes/cockpit-process.bpmn";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(
          jar -> jar
              .addAsResource("business-cockpit-versions.yaml", "application.yaml")
              .addAsResource(MODEL)
              .addAsResource(
                  "workflow-module-descriptor/workflow-module", "META-INF/workflow-module")
              .addClass(TestAggregate.class)
              .addClass(TestAggregatePersistence.class)
              .addClass(TestWorkflowService.class)
              // this test class is initialized twice, once while the application is built
              // and again inside the class loader of the running application. The copy
              // inside that application needs the server class as well, or the assertions
              // run against a class nobody loaded there
              .addClass(CockpitServer.class))
      .overrideRuntimeConfigKey(
          "vanillabp.cockpit.rest.base-url", CockpitServer.baseUrl());

  @Inject
  TestWorkflowService workflowService;

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

  /**
   * Another application on the same cockpit server may have reported a case carrying the same
   * business id as one of these: every test application of this module counts its aggregates from
   * 1 in a database of its own. So what arrived before this test is forgotten, and what is
   * asserted afterwards is this test's own.
   */
  @BeforeEach
  public void forgetWhatArrivedBefore() {

    CockpitServer.forgetRequests();

  }

  /**
   * A case whose workflow is running. VanillaBP starts a workflow in two phases, so the aggregate
   * is saved before the engine has anything, and a test reading the engine has to wait for it.
   *
   * @param customer What the case is called
   * @return The saved case, whose id is the business key of its workflow
   */
  private TestAggregate aStartedWorkflow(
      final String customer) throws Exception {

    transaction.begin();
    final TestAggregate started;
    try {
      final var aggregate = new TestAggregate();
      aggregate.setCustomer(customer);
      started = workflowService.processes().startWorkflow(aggregate);
      transaction.commit();
    } catch (final RuntimeException e) {
      transaction.rollback();
      throw e;
    }
    awaitTheUserTaskOf(started);
    return started;

  }

  /** Waits until the engine holds the user task of that case. */
  private void awaitTheUserTaskOf(
      final TestAggregate aggregate) {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var task = engine()
          .getTaskService()
          .createTaskQuery()
          .processInstanceBusinessKey(String.valueOf(aggregate.getId()))
          .singleResult();
      if (task != null) {
        return;
      }
      sleepShortly();
    }
    throw new AssertionError(
        "The user task of aggregate %s never appeared".formatted(aggregate.getId()));

  }

  private static void sleepShortly() {

    try {
      Thread.sleep(200);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting", e);
    }

  }

  /**
   * A second generation of the same model, deployed the way a release deploys one: same BPMN
   * process id, same tenant, one changed name. Camunda counts it as the next version of every
   * process in the file, and a case started afterwards runs on it.
   */
  private void deployASecondGeneration() {

    final String model;
    try (var resource = Thread
        .currentThread()
        .getContextClassLoader()
        .getResourceAsStream(MODEL)) {
      model = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("The model of this test application could not be read", e);
    }
    engine()
        .getRepositoryService()
        .createDeployment()
        .tenantId(MODULE_ID)
        .name("a second generation of the model")
        .addString(
            "cockpit-process.bpmn",
            model.replace("Approve the order", "Approve the order once more"))
        .deploy();

  }

  /** The version Camunda holds the newest deployment of the test's process under. */
  private String latestVersionOfTheModel() {

    return String
        .valueOf(
            engine()
                .getRepositoryService()
                .createProcessDefinitionQuery()
                .processDefinitionKey(TestWorkflowService.BPMN_PROCESS_ID)
                .latestVersion()
                .singleResult()
                .getVersion());

  }

  /**
   * What the cockpit server was told about one case. The server is shared with the other
   * applications of this module, so the business id every report carries is what tells them
   * apart.
   *
   * @param pathSuffix What the report's path has to end with
   * @param aggregate The case the report is about
   * @return The body of that report
   */
  private static String reportAbout(
      final String pathSuffix,
      final TestAggregate aggregate) {

    final var businessId = "\"businessId\":\"%s\"".formatted(aggregate.getId());
    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var reported = CockpitServer
          .received()
          .stream()
          .filter(request -> request.path().endsWith(pathSuffix))
          .map(CockpitServer.Request::body)
          .filter(body -> body.contains(businessId))
          .findFirst();
      if (reported.isPresent()) {
        return reported.get();
      }
      sleepShortly();
    }
    throw new AssertionError(
        "Nothing ending in '%s' was reported for aggregate %s. Received: %s"
            .formatted(
                pathSuffix, aggregate.getId(),
                CockpitServer.received().stream().map(CockpitServer.Request::path).toList()));

  }

  @Test
  @DisplayName("A second generation of the model moves the case and its task to the other provider")
  public void theDeployedVersionPicksTheProvider() throws Exception {

    final var onTheFirst = aStartedWorkflow("Vera");
    assertEquals("1", latestVersionOfTheModel(), "this application deployed more than one model");

    final var firstCase = reportAbout("/workflow/created", onTheFirst);
    assertTrue(firstCase.contains(TestWorkflowService.WORKFLOW_OF_THE_FIRST), firstCase);
    assertFalse(firstCase.contains(TestWorkflowService.WORKFLOW_OF_THE_LATER), firstCase);
    final var firstTask = reportAbout("/usertask/created", onTheFirst);
    assertTrue(firstTask.contains(TestWorkflowService.APPROVE_OF_THE_FIRST), firstTask);
    assertFalse(firstTask.contains(TestWorkflowService.APPROVE_OF_THE_LATER), firstTask);

    deployASecondGeneration();
    assertEquals("2", latestVersionOfTheModel(), "the second generation was not deployed");

    final var onTheSecond = aStartedWorkflow("Wanda");

    final var laterCase = reportAbout("/workflow/created", onTheSecond);
    assertTrue(laterCase.contains(TestWorkflowService.WORKFLOW_OF_THE_LATER), laterCase);
    assertFalse(laterCase.contains(TestWorkflowService.WORKFLOW_OF_THE_FIRST), laterCase);
    final var laterTask = reportAbout("/usertask/created", onTheSecond);
    assertTrue(laterTask.contains(TestWorkflowService.APPROVE_OF_THE_LATER), laterTask);
    assertFalse(laterTask.contains(TestWorkflowService.APPROVE_OF_THE_FIRST), laterTask);

  }

  @Test
  @DisplayName("Every reference the bridge answers carries the version Camunda counted")
  public void whatIsReadBackCarriesTheCountedVersion() throws Exception {

    final var aggregate = aStartedWorkflow("Yara");
    final var counted = latestVersionOfTheModel();
    final var aggregateId = String.valueOf(aggregate.getId());

    final var workflows = bridge()
        .workflowsOfAggregate(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId);
    assertEquals(1, workflows.size(), workflows.toString());
    assertEquals(counted, workflows.getFirst().processVersion());

    final var userTasks = bridge()
        .userTasksOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId, List.of());
    assertEquals(1, userTasks.size(), userTasks.toString());
    assertEquals(counted, userTasks.getFirst().processVersion());

    assertEquals(
        counted,
        bridge()
            .userTaskOfAggregate(
                MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId,
                userTasks.getFirst().userTaskId())
            .orElseThrow()
            .processVersion());

  }

}
