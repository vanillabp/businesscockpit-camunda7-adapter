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
 * An application whose engine keeps every detail, which is the only level at which Camunda 7
 * logs the identity links a task had.
 * <p>
 * Who was allowed to work on a task is part of what the cockpit shows, and for a task the engine
 * has already finished with there is nothing left to ask but that log. It records an addition and
 * a removal as two entries rather than a state, so reading it means replaying it - and a
 * candidate somebody took away again must not come back through the replay.
 */
@SpringBootTest(classes = {
    TestApplication.class, Camunda7HistoryFullTest.AnEngineKeepingEveryDetail.class
}, properties = {
    // a database of its own: the contexts of this repository's tests are cached and live in
    // parallel, and another context's engine on the same H2 database would run this one's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-cockpit-history-full;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@DirtiesContext
public class Camunda7HistoryFullTest {

  private static final String MODULE_ID = "c7-cockpit";

  /**
   * How an application asks for a history level of its own: the level is neither a key of this
   * extension nor one of the Camunda 7 adapter, and a customizer is one of the two ways to the
   * engine configuration the message of the boot check names.
   */
  @TestConfiguration
  public static class AnEngineKeepingEveryDetail {

    @Bean
    public Camunda7EngineCustomizer everyDetail() {

      return new Camunda7EngineCustomizer() {

        @Override
        public void customize(
            final String adapterId,
            final ProcessEngineConfigurationImpl configuration) {

          configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);

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

  private UserTaskReference referenceOf(
      final TestAggregate aggregate,
      final String userTaskId) {

    final var workflowId = engine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregate.getId()))
        .singleResult()
        .getId();
    return new UserTaskReference(
        "c7", MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(aggregate
            .getId()), workflowId, userTaskId, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_TASK_ID);

  }

  @Test
  @DisplayName("The engine of this application was built with the level the log needs")
  public void theEngineKeepsEveryDetail() {

    assertEquals(
        ProcessEngineConfiguration.HISTORY_FULL,
        ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
            .getHistoryLevel()
            .getName());

  }

  @Test
  @DisplayName("A finished task reports the candidates it had, and not the one it lost")
  public void theCandidatesOfAFinishedTaskAreReplayedFromTheLog() {

    final var aggregate = aStartedWorkflow("Pia");
    final var userTaskId = userTaskIdOf(aggregate);

    final var tasks = engine.getTaskService();
    tasks.addCandidateGroup(userTaskId, "auditors");
    tasks.addCandidateUser(userTaskId, "nina");
    // given and taken away again, which the log holds as two entries: the cockpit shows who may
    // work on a task, and somebody who was taken off it may not
    tasks.addCandidateGroup(userTaskId, "trainees");
    tasks.deleteCandidateGroup(userTaskId, "trainees");

    final var reference = referenceOf(aggregate, userTaskId);
    tasks.complete(userTaskId);

    final var prefill = bridge().prefilledUserTaskDetails(reference);

    assertTrue(prefill.isPresent(), "the finished task was not found in history");
    assertEquals(List.of("auditors"), prefill.get().candidateGroups());
    assertEquals(List.of("nina"), prefill.get().candidateUsers());

  }

}
