package io.vanillabp.cockpit.camunda7.springboot.test;

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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which details provider serves a case and its user task is decided by the version Camunda
 * counted for the deployed process.
 * <p>
 * The workflow service of this application has two providers for its user task and two for its
 * workflow. One of each pair serves version 1 and the other serves every version after it, so
 * nothing but the version can tell them apart. What the cockpit server receives says which one
 * ran.
 * <p>
 * The test deploys a second generation of the model itself, the way a release does, and starts
 * a case on it. That is the half a single deployment cannot show: a version the application
 * reports has to follow the deployment rather than being the same number forever.
 */
@SpringBootTest(classes = TestApplication.class,
    properties = {
        // an engine and an outbox of its own. This class deploys a second generation of the
        // model, and every other class of this repository tests what one deployment does
        "spring.datasource.url=jdbc:h2:mem:c7-cockpit-versions;DB_CLOSE_DELAY=-1"
    })
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: an engine outliving its test keeps its job executor running
// against a database the next class works on
@DirtiesContext
public class Camunda7ProcessVersionTest {

  private static final String MODULE_ID = "c7-cockpit";

  /** Where the model this application deploys is read from, to deploy a second one like it. */
  private static final String MODEL = "c7-cockpit/processes/cockpit-process.bpmn";

  @DynamicPropertySource
  static void cockpitServer(
      final DynamicPropertyRegistry registry) {

    registry.add("vanillabp.cockpit.rest.base-url", CockpitServer::baseUrl);

  }

  @Autowired
  private TestWorkflowService workflowService;

  @Autowired
  private TransactionTemplate transactions;

  @Autowired
  private ProcessEngine engine;

  @Autowired
  private ObjectProvider<BusinessCockpitBpmsBridge> bridges;

  private BusinessCockpitBpmsBridge bridge() {

    return bridges
        .stream()
        .filter(candidate -> "c7".equals(candidate.adapterId()))
        .findFirst()
        .orElseThrow();

  }

  /**
   * Another application on the same cockpit server may have reported a case carrying the same
   * business id as one of these: every test class here counts its aggregates from 1 in a database
   * of its own. So what arrived before this test is forgotten, and what is asserted afterwards is
   * this test's own.
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
      final String customer) {

    final var aggregate = transactions
        .execute(status -> {
          final var fresh = new TestAggregate();
          fresh.setCustomer(customer);
          return workflowService.processes().startWorkflow(fresh);
        });
    awaitTheUserTaskOf(aggregate);
    return aggregate;

  }

  /** Waits until the engine holds the user task of that case. */
  private void awaitTheUserTaskOf(
      final TestAggregate aggregate) {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var task = engine
          .getTaskService()
          .createTaskQuery()
          .processInstanceBusinessKey(String.valueOf(aggregate.getId()))
          .singleResult();
      if (task != null) {
        return;
      }
      try {
        Thread.sleep(200);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for the engine", e);
      }
    }
    throw new AssertionError(
        "The user task of aggregate %s never appeared".formatted(aggregate.getId()));

  }

  /**
   * A second generation of the same model, deployed the way a release deploys one: same BPMN
   * process id, same tenant, one changed name. Camunda counts it as the next version of every
   * process in the file, and a case started afterwards runs on it.
   */
  private void deployASecondGeneration() {

    final String model;
    try {
      model = new String(
          new ClassPathResource(MODEL).getContentAsByteArray(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("The model of this test application could not be read", e);
    }
    engine
        .getRepositoryService()
        .createDeployment()
        .tenantId(MODULE_ID)
        .name("a second generation of the model")
        .addString(
            "cockpit-process.bpmn",
            model.replace("Approve the order", "Approve the order once more"))
        .deploy();

  }

  /**
   * What the cockpit server was told about one case, told apart from what it was told about the
   * others by the business id every report carries.
   *
   * @param pathSuffix What the report's path has to end with
   * @param aggregate The case the report is about
   * @return The body of that report
   */
  private static String reportAbout(
      final String pathSuffix,
      final TestAggregate aggregate) {

    return CockpitServer
        .awaitRequest(pathSuffix, "\"businessId\":\"%s\"".formatted(aggregate.getId()))
        .body();

  }

  /** The version Camunda holds the newest deployment of the test's process under. */
  private String latestVersionOfTheModel() {

    return String
        .valueOf(
            engine
                .getRepositoryService()
                .createProcessDefinitionQuery()
                .processDefinitionKey(TestWorkflowService.BPMN_PROCESS_ID)
                .latestVersion()
                .singleResult()
                .getVersion());

  }

  @Test
  @DisplayName("A second generation of the model moves the case and its task to the other provider")
  public void theDeployedVersionPicksTheProvider() {

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
  public void whatIsReadBackCarriesTheCountedVersion() {

    final var aggregate = aStartedWorkflow("Yara");
    final var counted = latestVersionOfTheModel();

    final var workflows = bridge()
        .workflowsOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(aggregate.getId()));
    assertEquals(1, workflows.size(), workflows.toString());
    assertEquals(counted, workflows.getFirst().processVersion());

    final var userTasks = bridge()
        .userTasksOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(aggregate.getId()),
            List.of());
    assertEquals(1, userTasks.size(), userTasks.toString());
    assertEquals(counted, userTasks.getFirst().processVersion());

    assertEquals(
        counted,
        bridge()
            .userTaskOfAggregate(
                MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(aggregate.getId()),
                userTasks.getFirst().userTaskId())
            .orElseThrow()
            .processVersion());

  }

}
