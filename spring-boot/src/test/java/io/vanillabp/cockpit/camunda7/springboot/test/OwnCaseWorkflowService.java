package io.vanillabp.cockpit.camunda7.springboot.test;

import java.util.Map;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.cockpit.BusinessCockpitService;
import io.vanillabp.spi.cockpit.usertask.PrefilledUserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetailsProvider;
import io.vanillabp.spi.cockpit.workflow.PrefilledWorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetailsProvider;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;

/**
 * A process which another case calls through a call activity. It has a workflow aggregate of its
 * own, so it is a case of its own. The engine starts it, and this service builds its aggregate.
 */
@Service
@WorkflowService(workflowAggregateClass = OwnCaseAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = OwnCaseWorkflowService.BPMN_PROCESS_ID))
public class OwnCaseWorkflowService {

  /** The BPMN process of the case of its own. */
  public static final String BPMN_PROCESS_ID = "OwnCaseProcess";

  /** The form key of its user task. */
  public static final String TASK_DEFINITION = "checkOwnCase";

  /** The detail both providers put the customer in, so a test finds the reports of this case. */
  public static final String OWN_CASE = "ownCase";

  private final BusinessCockpitService<OwnCaseAggregate> businessCockpitService;

  public OwnCaseWorkflowService(
      final BusinessCockpitService<OwnCaseAggregate> businessCockpitService) {

    this.businessCockpitService = businessCockpitService;

  }

  /**
   * @return The cockpit service, so that a test can report a change of a case
   */
  public BusinessCockpitService<OwnCaseAggregate> businessCockpit() {

    return businessCockpitService;

  }

  /**
   * Builds the aggregate of a case which the call activity started.
   *
   * @param callersId The id of the calling case, which the call activity hands over
   * @return The new case, which gets its id when it is saved
   */
  @WorkflowStartedByBpms
  public OwnCaseAggregate aCaseOfItsOwn(
      @TaskParam("callersId") final String callersId) {

    final var aggregate = new OwnCaseAggregate();
    aggregate.setCustomer(customerOf(callersId));
    return aggregate;

  }

  /**
   * @param callersId The id of the calling case
   * @return The customer of the case which that caller started
   */
  public static String customerOf(
      final String callersId) {

    return "own-"
        + callersId;

  }

  /**
   * @param aggregate The workflow aggregate
   * @param prefilled What the engine knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider
  public WorkflowDetails workflowDetails(
      final OwnCaseAggregate aggregate,
      final PrefilledWorkflowDetails prefilled) {

    prefilled.setDetails(Map.of(OWN_CASE, aggregate.getCustomer()));
    return prefilled;

  }

  /**
   * @param aggregate The workflow aggregate
   * @param prefilled What the engine knew about the task
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION)
  public UserTaskDetails check(
      final OwnCaseAggregate aggregate,
      final PrefilledUserTaskDetails prefilled) {

    prefilled.setDetails(Map.of(OWN_CASE, aggregate.getCustomer()));
    return prefilled;

  }

}
