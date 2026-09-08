package io.vanillabp.cockpit.camunda7.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda7.Camunda7Adapter;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.cockpit.camunda7.Camunda7UserTaskListener;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two configured Camunda 7 adapter ids side by side, which is what a migration between two
 * engines looks like: each engine gets the cockpit's listeners of its own, each has a bridge of
 * its own, and a workflow which runs on one of them is reported once.
 */
@SpringBootTest(classes = {
    TestApplication.class, Camunda7TwoAdapterIdsTest.SecondEngineConfiguration.class
}, properties = {
    // a database of its own: contexts are cached and live in parallel, and another context's
    // engine on the same H2 database would execute this one's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-cockpit-two-ids;DB_CLOSE_DELAY=-1", "vanillabp.prioritized-adapters=c7,c7b", "vanillabp.adapters.c7b.type=camunda7", "vanillabp.adapters.c7b.name-clash-avoidance=by-adapter", "vanillabp.adapters.c7b.data-source-name=c7bDataSource", "vanillabp.workflow-modules.c7-cockpit.adapters.c7b.resources-location=classpath*:c7-cockpit/processes"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@DirtiesContext
public class Camunda7TwoAdapterIdsTest {

  /**
   * The datasource the second engine runs on. {@code defaultCandidate = false} keeps it out of
   * by-type injection, which is the standard pattern for an additional application datasource.
   */
  @TestConfiguration
  public static class SecondEngineConfiguration {

    @Bean(defaultCandidate = false)
    public DataSource c7bDataSource() {

      return new SimpleDriverDataSource(
          new org.h2.Driver(), "jdbc:h2:mem:c7-cockpit-second-engine;DB_CLOSE_DELAY=-1");

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
  private ObjectProvider<BusinessCockpitBpmsBridge> bridges;

  @Autowired
  private ObjectProvider<Camunda7EngineHolder> engines;

  @Test
  @DisplayName("Each configured Camunda 7 adapter id gets a bridge of its own")
  public void everyAdapterIdHasItsOwnBridge() {

    final var byAdapterId = bridges
        .stream()
        .collect(
            java.util.stream.Collectors
                .toMap(BusinessCockpitBpmsBridge::adapterId, BusinessCockpitBpmsBridge::adapterType));

    assertEquals(Set.of("c7", "c7b"), byAdapterId.keySet());
    byAdapterId
        .values()
        .forEach(adapterType -> assertEquals(Camunda7Adapter.ADAPTER_TYPE, adapterType));

  }

  @Test
  @DisplayName("Each engine carries the cockpit's listeners")
  public void everyEngineCarriesTheListeners() {

    final var enginesById = engines.stream().toList();
    assertEquals(2, enginesById.size(), "two adapter ids are two engines");
    assertNotEquals(
        enginesById.get(0).getProcessEngine(),
        enginesById.get(1).getProcessEngine(),
        "both adapter ids ended up on one engine");

    enginesById
        .forEach(
            engine -> assertInstanceOf(
                Camunda7UserTaskListener.class,
                createListenersOf(engine.getProcessEngine()).getLast(),
                "the cockpit's listener is missing on the engine of '%s'"
                    .formatted(engine.getAdapterId())));

  }

  @Test
  @DisplayName("A workflow running on one engine is reported once, not once per engine")
  public void aWorkflowIsReportedOnce() {

    CockpitServer.forgetRequests();

    transactions
        .execute(status -> {
          final var aggregate = new TestAggregate();
          aggregate.setCustomer("Nora");
          return workflowService.processes().startWorkflow(aggregate);
        });

    CockpitServer.awaitRequest("/usertask/created");
    CockpitServer.awaitQuiet();

    assertEquals(
        1,
        CockpitServer.matching("/workflow/created").size(),
        "the workflow was reported more than once: %s"
            .formatted(CockpitServer.received().stream().map(CockpitServer.Request::path).toList()));
    assertEquals(
        1,
        CockpitServer.matching("/usertask/created").size(),
        "the user task was reported more than once: %s"
            .formatted(CockpitServer.received().stream().map(CockpitServer.Request::path).toList()));

  }

  private static List<TaskListener> createListenersOf(
      final ProcessEngine engine) {

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
    final var listeners = ((UserTaskActivityBehavior) deployed
        .findActivity(TestWorkflowService.BPMN_TASK_ID)
        .getActivityBehavior())
        .getTaskDefinition()
        .getBuiltinTaskListeners()
        .getOrDefault(TaskListener.EVENTNAME_CREATE, List.of());
    assertFalse(listeners.isEmpty(), "no built-in CREATE listener at all");
    return listeners;

  }

}
