package io.vanillabp.cockpit.camunda7.quarkus.it;

import java.util.Map;

import io.vanillabp.spi.cockpit.workflow.PrefilledWorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetailsProvider;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A workflow nobody starts through VanillaBP. A broadcast signal starts it, and this service
 * builds the workflow aggregate of the new case.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = SignalledAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = SignalStartedWorkflowService.BPMN_PROCESS_ID))
public class SignalStartedWorkflowService {

  /** The BPMN process which only a broadcast signal starts. */
  public static final String BPMN_PROCESS_ID = "SignalStartedProcess";

  /** The name of the signal which starts that process. */
  public static final String START_SIGNAL = "cockpitStartSignal";

  /** The variable the signal carries the customer of the new case in. */
  public static final String CUSTOMER_VARIABLE = "customer";

  /**
   * Builds the workflow aggregate of a case the signal started. Nobody named that case, so this
   * method does, and the Camunda 7 adapter writes the name into the business key afterwards.
   *
   * @param customer What the signal carried
   * @return The new case, which gets its id when it is saved
   */
  @WorkflowStartedByBpms
  public SignalledAggregate aCaseTheSignalStarted(
      @TaskParam(CUSTOMER_VARIABLE) final String customer) {

    final var aggregate = new SignalledAggregate();
    aggregate.setCustomer(customer);
    return aggregate;

  }

  /**
   * Puts the customer into what the cockpit is told about the case, so that a test can tell
   * this case apart from the cases of other test classes.
   *
   * @param aggregate The workflow aggregate
   * @param prefilled What the engine knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider
  public WorkflowDetails workflowDetails(
      final SignalledAggregate aggregate,
      final PrefilledWorkflowDetails prefilled) {

    prefilled.setDetails(Map.of(CUSTOMER_VARIABLE, aggregate.getCustomer()));
    return prefilled;

  }

}
