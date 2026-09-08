package io.vanillabp.cockpit.camunda7.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.runtime.VanillaBpCamunda7Properties;
import io.vanillabp.cockpit.camunda7.Camunda7CockpitCustomizer;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * A Quarkus application whose engine writes into a database of its own, and what that does not
 * change: the engine still runs its commands in the transaction of whoever called it, because
 * this platform builds it on the container's transaction manager. So a workflow which was rolled
 * back leaves nothing behind, here as anywhere else.
 * <p>
 * The two assertions of the rollback test belong together. That nothing arrives at the cockpit
 * server is what an application is promised, but it would also hold while an outbox entry was
 * written outside the rolled-back transaction and dropped much later, when the dispatcher asked
 * the engine about a workflow it had never committed. What has to be true for the promise to be
 * kept is that the entry was never written, and that is what the second assertion pins: the
 * platform says this engine joins the application's transaction although it was given a data
 * source name - see decision 6 in the repository's DECISIONS.md.
 * <p>
 * Both data sources of this application are configured as XA, which is what an application with
 * an engine database of its own has to do on Quarkus: the entry and the engine's work are two
 * data sources in one JTA transaction, and Agroal enlists a second one only as an XA resource.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7OwnDataSourceTest {

  private static final String ADAPTER_ID = "c7";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(
          jar -> jar
              .addAsResource("business-cockpit-own-data-source.yaml", "application.yaml")
              .addAsResource("c7-cockpit/processes/cockpit-process.bpmn")
              .addAsResource(
                  "workflow-module-descriptor/workflow-module", "META-INF/workflow-module")
              .addClass(TestAggregate.class)
              .addClass(TestAggregatePersistence.class)
              .addClass(TestWorkflowService.class)
              .addClass(CockpitServer.class))
      .overrideRuntimeConfigKey(
          "vanillabp.cockpit.rest.base-url", CockpitServer.baseUrl());

  @Inject
  TestWorkflowService workflowService;

  @Inject
  VanillaBpCamunda7Properties camunda7Properties;

  @Inject
  UserTransaction transaction;

  @Inject
  Camunda7CockpitCustomizer customizer;

  @Test
  @DisplayName("A workflow rolled back on an engine with a data source of its own is not reported")
  public void aRolledBackWorkflowIsNotReported() throws Exception {

    assertTrue(
        camunda7Properties.adapters().get(ADAPTER_ID).dataSourceName().isPresent(),
        "this engine runs on the application's data source, so the test proves nothing");

    CockpitServer.forgetRequests();

    transaction.begin();
    final var aggregate = new TestAggregate();
    aggregate.setCustomer("Rita");
    final var started = workflowService.processes().startWorkflow(aggregate);
    transaction.rollback();

    assertNotNull(started.getId(), "the workflow aggregate never got an id to look for");
    final var businessId = "\"businessId\":\"%s\"".formatted(started.getId());

    CockpitServer.awaitQuiet();
    assertEquals(
        List.of(),
        CockpitServer
            .received()
            .stream()
            .filter(request -> request.body().contains(businessId))
            .map(CockpitServer.Request::path)
            .toList(),
        "the cockpit was told about a workflow the engine rolled back");

    assertEquals(
        EventTransaction.CURRENT,
        customizer.eventTransactionOf(ADAPTER_ID),
        "the entry of an observed event would be written outside the transaction the engine works in");

  }

  @Test
  @DisplayName("A workflow which was committed is reported, engine data source or not")
  public void aCommittedWorkflowIsReported() throws Exception {

    transaction.begin();
    final var aggregate = new TestAggregate();
    aggregate.setCustomer("Sven");
    final var started = workflowService.processes().startWorkflow(aggregate);
    transaction.commit();

    assertNotNull(started.getId());
    awaitReportOf("/workflow/created", started);

  }

  /**
   * The report about one case, told apart from whatever the other Quarkus application of this
   * module reported to the same server before it was shut down.
   */
  private static String awaitReportOf(
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
      try {
        Thread.sleep(200);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for the cockpit server", e);
      }
    }
    throw new AssertionError(
        "Nothing ending in '%s' was reported for aggregate %s. Received: %s"
            .formatted(
                pathSuffix, aggregate.getId(),
                CockpitServer.received().stream().map(CockpitServer.Request::path).toList()));

  }

}
