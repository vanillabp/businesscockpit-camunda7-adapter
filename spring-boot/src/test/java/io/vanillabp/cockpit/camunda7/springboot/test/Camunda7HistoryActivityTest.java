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
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An application whose engine keeps the least history the cockpit can work with.
 * <p>
  * Camunda's <code>activity</code> writes what happened to a process instance, which is what the
  * cockpit reads. What the levels above it add is task, variable and form-property history, and
  * none of that is asked for. So an application which lowered its engine to it gets a cockpit
  * which works. This test walks the whole way to prove it: a workflow starts, its task appears,
  * both are reported, and both are reported again when the task is completed.
 * <p>
  * A task carries as much at this level as at any other. Everything the cockpit shows about it
  * comes off the task the engine handed to the listener, and the candidates of a task do too.
  * That is asserted here, because at this level there is no history of a task to fall back on.
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
   * the only one which arrived. What tells them apart is the case, not its id: every context of
   * this module runs on a database of its own and counts its cases up from one, so an id alone
   * names a case per context.
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
    final var thisCase = "\"customer\":\"Pina\"";
    final var businessId = "\"businessId\":\"%s\"".formatted(aggregate.getId());
    final var workflowId = workflowIdOf(aggregate);

    final var workflow = awaitReport("/workflow/created", thisCase);
    assertTrue(workflow.contains(businessId), workflow);

    final var userTask = awaitReport("/usertask/created", thisCase);
    assertTrue(userTask.contains(businessId), userTask);
    assertTrue(userTask.contains("\"event\":\"CREATED\""), userTask);
    assertTrue(
        userTask.contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        userTask);
    // the details provider ran, which means the task's variables were readable at this level
    assertTrue(userTask.contains(thisCase), userTask);

    engine.getTaskService().complete(userTaskId);

    CockpitServer.awaitAnyRequest("/usertask/%s/completed".formatted(userTaskId));
    CockpitServer.awaitAnyRequest("/workflow/%s/completed".formatted(workflowId));

  }

  @Test
  @DisplayName("A task keeps its candidates at this level, and its end is reported without any task history")
  public void aCompletedTaskIsReportedWithoutAnyTaskHistory() {

    final var aggregate = aStartedWorkflow("Rudi");
    final var userTaskId = userTaskIdOf(aggregate);
    final var thisCase = "\"customer\":\"Rudi\"";

    // while the task runs the engine holds its identity links, and a question of the
    // application reads them there
    engine.getTaskService().addCandidateGroup(userTaskId, "auditors");
    assertEquals(
        List.of("auditors"),
        bridge()
            .prefilledUserTaskDetails(referenceOf(aggregate, userTaskId))
            .orElseThrow()
            .candidateGroups());

    engine.getTaskService().complete(userTaskId);

    // the report of the completion was built while the task was still there. Afterwards there
    // is nothing left to read: this level writes no task history, and the engine holds no
    // finished task
    final var completed = awaitReport("/usertask/%s/completed".formatted(userTaskId), thisCase);
    assertTrue(completed.contains("\"event\":\"COMPLETED\""), completed);
    assertTrue(
        bridge().prefilledUserTaskDetails(referenceOf(aggregate, userTaskId)).isEmpty(),
        "a task the engine has finished with was answered from somewhere");

  }

  private UserTaskReference referenceOf(
      final TestAggregate aggregate,
      final String userTaskId) {

    return new UserTaskReference(
        "c7", MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.DEPLOYED_VERSION, String
            .valueOf(aggregate.getId()), workflowIdOf(
                aggregate), userTaskId, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_TASK_ID);

  }

}
