package io.vanillabp.cockpit.camunda7.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda7.engine.Camunda7EngineCustomizer;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An application whose engine keeps the least history the cockpit can work with.
 * <p>
 * Camunda's <code>activity</code> writes what happened to a process instance and what happened to
 * a task, which is everything the cockpit reads; what the level above it adds is variable and
 * form-property history, and none of that is asked for here. So an application which lowered its
 * engine to it gets a cockpit which works, and this test walks the whole way to prove it: a
 * workflow starts, its task appears, both are reported, both are reported again when the task is
 * completed, and the finished task is still readable afterwards.
 * <p>
 * What such an engine cannot answer is who was a candidate for a task it has finished with, since
 * the identity-link log belongs to <code>full</code>. That is asserted here as well, because it is
 * the one thing an application gives up by going this low.
 */
@SpringBootTest(classes = {
    TestApplication.class, Camunda7HistoryActivityTest.AnEngineKeepingTheLeastThatWorks.class
}, properties = {
    // a database of its own: the contexts of this repository's tests are cached and live in
    // parallel, and another context's engine on the same H2 database would run this one's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-cockpit-history-activity;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@DirtiesContext
public class Camunda7HistoryActivityTest {

  private static final String MODULE_ID = "c7-cockpit";

  /**
   * How an application asks for a history level of its own: the level is neither a key of this
   * extension nor one of the Camunda 7 adapter, and a customizer is one of the two ways to the
   * engine configuration the message of the boot check names.
   */
  @TestConfiguration
  public static class AnEngineKeepingTheLeastThatWorks {

    @Bean
    public Camunda7EngineCustomizer theLeastThatWorks() {

      return new Camunda7EngineCustomizer() {

        @Override
        public void customize(
            final String adapterId,
            final ProcessEngineConfigurationImpl configuration) {

          configuration.setHistory(ProcessEngineConfiguration.HISTORY_ACTIVITY);

        }

      };

    }

  }

  @DynamicPropertySource
  static void cockpitServer(
      final DynamicPropertyRegistry registry) {

    registry.add("vanillabp.extensions.business-cockpit.rest.base-url", CockpitServer::baseUrl);

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

  /**
   * The report of this test's own workflow. The cockpit server is shared by every test class of
   * this module, so a report is told from another one's by what it carries rather than by being
   * the only one which arrived.
   *
   * @param pathSuffix What the path of the report ends with
   * @param carrying What its body has to contain
   * @return The body of the report
   */
  private static String awaitReport(
      final String pathSuffix,
      final String carrying) {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var mine = CockpitServer
          .matching(pathSuffix)
          .stream()
          .map(CockpitServer.Request::body)
          .filter(body -> body.contains(carrying))
          .findFirst();
      if (mine.isPresent()) {
        return mine.get();
      }
      sleep();
    }
    throw new AssertionError(
        "No report ending in '%s' carrying '%s' arrived".formatted(pathSuffix, carrying));

  }

  private static void sleep() {

    try {
      Thread.sleep(200);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting", e);
    }

  }

  @Test
  @DisplayName("The engine of this application was built with the lowest level the cockpit works on")
  public void theEngineKeepsTheLeastThatWorks() {

    assertEquals(
        ProcessEngineConfiguration.HISTORY_ACTIVITY,
        ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
            .getHistoryLevel()
            .getName());

  }

  @Test
  @DisplayName("A workflow and its user task are reported from start to end")
  public void aWorkflowAndItsTaskAreReportedAllTheWay() {

    final var aggregate = aStartedWorkflow("Pina");
    final var userTaskId = userTaskIdOf(aggregate);
    final var businessId = "\"businessId\":\"%s\"".formatted(aggregate.getId());
    final var workflowId = workflowIdOf(aggregate);

    final var workflow = awaitReport("/workflow/created", businessId);
    assertTrue(workflow.contains("\"customer\":\"Pina\""), workflow);

    final var userTask = awaitReport("/usertask/created", businessId);
    assertTrue(userTask.contains("\"event\":\"CREATED\""), userTask);
    assertTrue(
        userTask.contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        userTask);
    // the details provider ran, which means the task's variables were readable at this level
    assertTrue(userTask.contains("\"customer\":\"Pina\""), userTask);

    engine.getTaskService().complete(userTaskId);

    CockpitServer.awaitRequest("/usertask/%s/completed".formatted(userTaskId));
    CockpitServer.awaitRequest("/workflow/%s/completed".formatted(workflowId));

  }

  @Test
  @DisplayName("A finished task is read from history, and it has no candidates to report")
  public void aFinishedTaskIsReadWithoutItsCandidates() {

    final var aggregate = aStartedWorkflow("Rudi");
    final var userTaskId = userTaskIdOf(aggregate);

    // while the task runs the engine holds its identity links, so the candidate is there to be
    // read; once the task is gone only the log would know, and this level writes none
    engine.getTaskService().addCandidateGroup(userTaskId, "auditors");
    assertEquals(
        List.of("auditors"),
        bridge()
            .prefilledUserTaskDetails(referenceOf(aggregate, userTaskId))
            .orElseThrow()
            .candidateGroups());

    engine.getTaskService().complete(userTaskId);

    final var prefill = bridge().prefilledUserTaskDetails(referenceOf(aggregate, userTaskId));
    assertTrue(prefill.isPresent(), "the finished task was not found in history");
    assertEquals("Approve the order", prefill.get().bpmnTaskName());
    assertEquals(String.valueOf(aggregate.getId()), prefill.get().businessId());
    assertEquals(List.of(), prefill.get().candidateGroups());

  }

  private UserTaskReference referenceOf(
      final TestAggregate aggregate,
      final String userTaskId) {

    return new UserTaskReference(
        "c7", MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(aggregate.getId()), workflowIdOf(
            aggregate), userTaskId, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_TASK_ID);

  }

}
