package io.vanillabp.cockpit.camunda7.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.camunda.bpm.engine.ProcessEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow the engine starts by itself reaches the cockpit as a new case, like a workflow the
 * application starts.
 * <p>
 * A signal, a timer or a message correlated past VanillaBP starts a process instance without a
 * business key. The engine writes its start into the history at that moment, and the cockpit has
 * no workflow aggregate to report it for. The Camunda 7 adapter then asks the application to
 * build the aggregate and writes its id into the business key. For the engine that is a change of
 * the process instance, and it would reach the cockpit as an update. The cockpit server would
 * still create the case from it, but with the time of the update as its start.
 * <p>
 * A signal is the trigger here because the test can send it the moment it wants to. A timer
 * start would fire in every context of this module which deploys the model.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: an engine outliving its test keeps its job executor running
// against a database the next class works on
@DirtiesContext
public class Camunda7BpmsStartedWorkflowTest {

  private static final Pattern TIMESTAMP = Pattern.compile("\"timestamp\":\"([^\"]+)\"");

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

  @BeforeEach
  public void forgetWhatArrivedBefore() {

    CockpitServer.forgetRequests();

  }

  /**
   * Every report about the workflow of one case, once nothing more arrives. The server is shared
   * by every test class of this module, and each class counts its ids up from one. So a report is
   * recognized by the customer of the case as well, which is unique per test.
   *
   * @param workflowId The process instance of the case
   * @param businessId The id of its workflow aggregate
   * @param customer What only this case carries
   * @return The reports in the order they arrived
   */
  private static List<CockpitServer.Request> workflowReportsOf(
      final String workflowId,
      final String businessId,
      final String customer) {

    final var thisCase = "\"businessId\":\"%s\"".formatted(businessId);
    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      if (!reportsOf(workflowId, thisCase, customer).isEmpty()) {
        break;
      }
      sleep();
    }
    CockpitServer.awaitQuiet();
    return reportsOf(workflowId, thisCase, customer);

  }

  private static List<CockpitServer.Request> reportsOf(
      final String workflowId,
      final String thisCase,
      final String customer) {

    return CockpitServer
        .received()
        .stream()
        .filter(
            request -> request.path().endsWith("/workflow/created") || request
                .path()
                .contains("/workflow/%s/".formatted(workflowId)))
        .filter(request -> request.body().contains(thisCase))
        .filter(request -> request.body().contains(customer))
        .toList();

  }

  /**
   * The process instance of a case the application started. VanillaBP starts it once the
   * transaction which saved the case has committed, so it may not be there at once.
   *
   * @param businessId The id of the workflow aggregate
   * @return The id of its process instance
   */
  private String workflowIdOf(
      final String businessId) {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var instance = engine
          .getHistoryService()
          .createHistoricProcessInstanceQuery()
          .processInstanceBusinessKey(businessId)
          .singleResult();
      if (instance != null) {
        return instance.getId();
      }
      sleep();
    }
    throw new AssertionError("The workflow of case %s never started".formatted(businessId));

  }

  private static void sleep() {

    try {
      Thread.sleep(200);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the cockpit", e);
    }

  }

  /**
   * @param report What the server received
   * @return The moment the report says its event happened
   */
  private static OffsetDateTime timestampOf(
      final CockpitServer.Request report) {

    final var matcher = TIMESTAMP.matcher(report.body());
    assertTrue(matcher.find(), "the report carries no timestamp: "
        + report.body());
    return OffsetDateTime.parse(matcher.group(1));

  }

  /**
   * Asserts that the case was reported once, as created, and with the moment its process
   * instance started.
   *
   * @param workflowId The process instance of the case
   * @param businessId The id of its workflow aggregate
   * @param customer What only this case carries
   */
  private void assertReportedOnceAsCreatedAtItsStart(
      final String workflowId,
      final String businessId,
      final String customer) {

    final var reports = workflowReportsOf(workflowId, businessId, customer);
    final var startTime = engine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceId(workflowId)
        .singleResult()
        .getStartTime()
        .toInstant();
    final var whatArrived = reports
        .stream()
        .map(report -> "%s at %s".formatted(report.path(), timestampOf(report)))
        .toList();
    final var expected = "the case should arrive once, as created, at %s when its process instance started. It arrived as %s"
        .formatted(startTime, whatArrived);

    assertEquals(1, reports.size(), expected);
    final var report = reports.getFirst();
    assertTrue(report.path().endsWith("/workflow/created"), expected);
    assertEquals(startTime, timestampOf(report).toInstant(), expected);

  }

  @Test
  @DisplayName("A workflow a signal started arrives once, as created, with the moment it started")
  public void aWorkflowTheEngineStartedArrivesAsCreated() {

    final var customer = "Signe-"
        + UUID.randomUUID();
    engine
        .getRuntimeService()
        .signalEventReceived(
            SignalStartedWorkflowService.START_SIGNAL,
            Map.of(SignalStartedWorkflowService.CUSTOMER_VARIABLE, customer));

    final var instance = engine
        .getRuntimeService()
        .createProcessInstanceQuery()
        .processDefinitionKey(SignalStartedWorkflowService.BPMN_PROCESS_ID)
        .variableValueEquals(SignalStartedWorkflowService.CUSTOMER_VARIABLE, customer)
        .singleResult();
    assertNotNull(instance, "the signal started no process instance, so this test proves nothing");
    assertNotNull(
        instance.getBusinessKey(),
        "the application did not name the case, so this test proves nothing");

    assertReportedOnceAsCreatedAtItsStart(instance.getId(), instance.getBusinessKey(), customer);

  }

  @Test
  @DisplayName("A workflow the application started arrives the same way")
  public void aWorkflowTheApplicationStartedArrivesTheSameWay() {

    final var customer = "Apollonia-"
        + UUID.randomUUID();
    final var aggregate = transactions
        .execute(status -> {
          final var created = new TestAggregate();
          created.setCustomer(customer);
          return workflowService.processes().startWorkflow(created);
        });
    final var businessId = String.valueOf(aggregate.getId());
    final var workflowId = workflowIdOf(businessId);

    assertReportedOnceAsCreatedAtItsStart(workflowId, businessId, customer);

  }

}
