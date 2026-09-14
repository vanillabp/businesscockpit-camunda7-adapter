package io.vanillabp.cockpit.camunda7.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
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

import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow module which was given a Camunda tenant of its own.
 * <p>
 * A tenant name may be written for one workflow module and for the whole adapter, and the module
 * wins where both are there. Which of the two a module ends up in is decided when the adapter
 * deploys it, and everything the cockpit sends to that engine afterwards has to spell the same
 * name: a query under the wrong tenant answers nothing, and an event of a process stored under
 * one name is never recognized while the extension looks for another.
 * <p>
 * The name here is written at the workflow module and nowhere else, which is the level an
 * extension reading the adapter's section alone cannot see.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // a database of its own: the contexts of this repository's tests are cached and live in
    // parallel, and another context's engine on the same H2 database would run this one's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-cockpit-module-tenant;DB_CLOSE_DELAY=-1", "vanillabp.workflow-modules.c7-cockpit.adapters.c7.tenant-id=a-tenant-of-its-own"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@DirtiesContext
public class Camunda7ModuleTenantTest {

  private static final String MODULE_ID = "c7-cockpit";

  private static final String CONFIGURED_TENANT = "a-tenant-of-its-own";

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

  @Test
  @DisplayName("The engine deployed the module under the name the module configured")
  public void theModuleWasDeployedUnderItsOwnTenant() {

    assertEquals(
        CONFIGURED_TENANT,
        engine
            .getRepositoryService()
            .createProcessDefinitionQuery()
            .processDefinitionKey(TestWorkflowService.BPMN_PROCESS_ID)
            .latestVersion()
            .singleResult()
            .getTenantId(),
        "the adapter deployed under another name, so this test proves nothing");

  }

  @Test
  @DisplayName("What the cockpit reads back about the module is found under that name")
  public void theCockpitReadsBackUnderTheModulesTenant() {

    final var aggregate = aStartedWorkflow("Wanda");
    final var userTaskId = userTaskIdOf(aggregate);
    final var aggregateId = String.valueOf(aggregate.getId());

    final var userTasks = bridge()
        .userTasksOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId, List.of());
    assertEquals(1, userTasks.size(), userTasks.toString());
    assertEquals(userTaskId, userTasks.getFirst().userTaskId());

    final var workflows = bridge()
        .workflowsOfAggregate(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId);
    assertEquals(1, workflows.size(), workflows.toString());

  }

  @Test
  @DisplayName("What the engine reports is recognized although it carries that name")
  public void whatTheEngineReportsIsRecognized() {

    final var aggregate = aStartedWorkflow("Willi");
    userTaskIdOf(aggregate);
    // named by its customer rather than by its id: every context of this module runs on a
    // database of its own and counts its cases up from one, so an id alone names a case per
    // context and the cockpit server is shared by all of them
    final var thisCase = "\"customer\":\"Willi\"";
    final var businessId = "\"businessId\":\"%s\"".formatted(aggregate.getId());

    final var workflow = CockpitServer.awaitRequest("/workflow/created", thisCase);
    assertTrue(workflow.body().contains(businessId), workflow.body());
    assertTrue(
        workflow.body().contains("\"workflowModuleId\":\"%s\"".formatted(MODULE_ID)),
        workflow.body());

    final var userTask = CockpitServer.awaitRequest("/usertask/created", thisCase);
    assertTrue(userTask.body().contains(businessId), userTask.body());
    assertTrue(
        userTask.body().contains("\"workflowModuleId\":\"%s\"".formatted(MODULE_ID)),
        userTask.body());

  }

}
