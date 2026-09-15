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
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * A Quarkus application whose engine writes into a database of its own, and what a workflow it
 * rolled back leaves behind.
 * <p>
 * The engine writes to a resource the application's own persistence does not take part in. Two
 * data sources in one JTA transaction are still two commits, so the outbox entry reporting what
 * the engine did cannot ride the engine's commit however the container enlists them. The entry
 * therefore gets a transaction of its own, and that is the honest answer rather than a correct
 * one: entry and engine work commit separately, and the entry of a workflow the engine rolled
 * back is written and stays. What keeps such a workflow out of the cockpit is the dispatch. It
 * asks the engine about the workflow, the engine holds none, and an event about a workflow
 * nobody knows is dropped rather than sent.
 * <p>
 * Whether an engine shares the caller's transaction is the Camunda 7 adapter's answer, read off
 * the engine that adapter built. This test pins what it answers for an engine with a data source
 * of its own, because that answer is what decides where the entry goes.
 * <p>
 * Neither data source of this application is configured as XA, and that is part of what this test
 * says. XA was the price of the old answer, which had the entry riding the engine's JTA
 * transaction: a second data source is enlisted there only as an XA resource, so without it the
 * entry failed while the engine worked. The entry has a transaction of its own now, so it enlists
 * nothing of the engine's and needs nothing declared. An application may still declare its data
 * sources as XA for reasons of its own; it changes nothing here.
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
  VanillaBpCamunda7Properties camunda7Properties;

  @Inject
  UserTransaction transaction;

  @Inject
  Camunda7CockpitCustomizer customizer;

  /**
   * Nothing about the rolled-back workflow reaches the cockpit server. The entry reporting it
   * was written all the same, in a transaction of its own, which is what the second assertion
   * names and why the first one is about the server rather than about the store.
   */
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
        EventTransaction.NEW,
        customizer.eventTransactionOf(ADAPTER_ID),
        """
            the engine of this adapter id was given a data source of its own, so its transaction \
            never reaches the store the outbox entry is written into - an entry sharing it would \
            be an entry whose commit says nothing about the engine's""");

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
