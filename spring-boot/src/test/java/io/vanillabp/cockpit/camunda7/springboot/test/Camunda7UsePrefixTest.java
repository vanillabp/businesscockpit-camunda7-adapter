package io.vanillabp.cockpit.camunda7.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow module deployed with prefixed identifiers, which is the name-clash avoidance
 * <code>use-prefix</code>.
 * <p>
 * The engine then knows the process under a name the application never wrote, and it knows no
 * tenant which would tell one workflow module from another. Both are things the cockpit must
 * not inherit: it reports the process id the application wrote, and it answers a question about
 * one workflow module with the tasks of that module alone.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // a database of its own: the contexts of this repository's tests are cached and live in
    // parallel, and another context's engine on the same H2 database would run this one's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-cockpit-use-prefix;DB_CLOSE_DELAY=-1", "vanillabp.adapters.c7.name-clash-avoidance=use-prefix"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@DirtiesContext
public class Camunda7UsePrefixTest {

  private static final String MODULE_ID = "c7-cockpit";

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

  private TestAggregate aStartedWorkflow(
      final String customer) {

    return transactions
        .execute(status -> {
          final var aggregate = new TestAggregate();
          aggregate.setCustomer(customer);
          return workflowService.processes().startWorkflow(aggregate);
        });

  }

  private BusinessCockpitBpmsBridge bridge() {

    return bridges
        .stream()
        .filter(candidate -> "c7".equals(candidate.adapterId()))
        .findFirst()
        .orElseThrow();

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

  /**
   * The report about this test's own case, told apart from whatever a context of another test
   * class reports to the same server while this one waits.
   */
  private String awaitReportOf(
      final String pathSuffix,
      final TestAggregate aggregate) {

    final var businessId = "\"businessId\":\"%s\"".formatted(aggregate.getId());
    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var reported = CockpitServer
          .matching(pathSuffix)
          .stream()
          .map(CockpitServer.Request::body)
          .filter(body -> body.contains(businessId))
          .findFirst();
      if (reported.isPresent()) {
        return reported.get();
      }
      sleep();
    }
    throw new AssertionError(
        "Nothing ending in '%s' was reported for aggregate %s. Received: %s"
            .formatted(
                pathSuffix, aggregate.getId(),
                CockpitServer.received().stream().map(CockpitServer.Request::path).toList()));

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
  @DisplayName("A process the engine knows under a prefixed name is reported under the plain one")
  public void aPrefixedProcessIsReportedUnderItsPlainId() {

    final var aggregate = aStartedWorkflow("Petra");
    userTaskIdOf(aggregate);

    // the engine really deployed it under a name the application never wrote, and under no
    // tenant: this mode is the one where an id has to be translated back rather than passed on
    final var deployed = engine
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .latestVersion()
        .list()
        .stream()
        .filter(definition -> definition.getKey().endsWith(TestWorkflowService.BPMN_PROCESS_ID))
        .findFirst()
        .orElseThrow();
    assertNotEquals(
        TestWorkflowService.BPMN_PROCESS_ID,
        deployed.getKey(),
        "the process was deployed under its plain id, so this test proves nothing");
    assertNull(deployed.getTenantId(), "use-prefix deployed a tenant");

    final var bpmnProcessId = "\"bpmnProcessId\":\"%s\"".formatted(TestWorkflowService.BPMN_PROCESS_ID);
    final var workflow = awaitReportOf("/workflow/created", aggregate);
    assertTrue(workflow.contains(bpmnProcessId), workflow);
    final var userTask = awaitReportOf("/usertask/created", aggregate);
    assertTrue(userTask.contains(bpmnProcessId), userTask);

  }

  @Test
  @DisplayName("The tasks of an aggregate are answered for the asking workflow module alone")
  public void aTaskIsAnsweredForItsOwnWorkflowModuleOnly() {

    final var aggregate = aStartedWorkflow("Quirin");
    final var userTaskId = userTaskIdOf(aggregate);
    final var aggregateId = String.valueOf(aggregate.getId());

    final var ownTasks = bridge()
        .userTasksOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId, List.of());
    assertEquals(1, ownTasks.size(), ownTasks.toString());
    assertEquals(userTaskId, ownTasks.getFirst().userTaskId());

    // no tenant separates the workflow modules in this mode, so the engine answers this query
    // with the very same task - and a module which did not deploy that process must not get it
    assertEquals(
        List.of(),
        bridge()
            .userTasksOfAggregate(
                "another-module", TestWorkflowService.BPMN_PROCESS_ID, aggregateId, List.of()),
        "a task of another workflow module was answered");
    assertTrue(
        bridge()
            .userTaskOfAggregate(
                "another-module", TestWorkflowService.BPMN_PROCESS_ID, aggregateId, userTaskId)
            .isEmpty(),
        "a task of another workflow module was answered");

  }

}
