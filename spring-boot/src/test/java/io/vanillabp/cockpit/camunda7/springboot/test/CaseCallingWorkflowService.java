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
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * A case which calls two processes at once: a step which works on this aggregate, and a process
 * with an aggregate of its own ({@link OwnCaseWorkflowService}).
 */
@Service
@WorkflowService(workflowAggregateClass = CaseCallingAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = CaseCallingWorkflowService.BPMN_PROCESS_ID),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = CaseCallingWorkflowService.CALLED_STEP_PROCESS_ID))
public class CaseCallingWorkflowService {

  /** The calling process. */
  public static final String BPMN_PROCESS_ID = "CaseCallingProcess";

  /** The step it calls, which works on the same workflow aggregate. */
  public static final String CALLED_STEP_PROCESS_ID = "CalledStepProcess";

  /** The form key of the user task of that step. */
  public static final String STEP_TASK_DEFINITION = "doTheStep";

  /** What both providers write the customer into, so a test finds the reports of this case. */
  public static final String CALLER = "caller";

  private final ProcessService<CaseCallingAggregate> processService;

  private final BusinessCockpitService<CaseCallingAggregate> businessCockpitService;

  public CaseCallingWorkflowService(
      final ProcessService<CaseCallingAggregate> processService,
      final BusinessCockpitService<CaseCallingAggregate> businessCockpitService) {

    this.processService = processService;
    this.businessCockpitService = businessCockpitService;

  }

  /**
   * @return The process service, so that a test can start a case
   */
  public ProcessService<CaseCallingAggregate> processes() {

    return processService;

  }

  /**
   * @return The cockpit service, so that a test can report a change of a case
   */
  public BusinessCockpitService<CaseCallingAggregate> businessCockpit() {

    return businessCockpitService;

  }

  /**
   * @param aggregate The workflow aggregate
   * @param prefilled What the engine knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider
  public WorkflowDetails workflowDetails(
      final CaseCallingAggregate aggregate,
      final PrefilledWorkflowDetails prefilled) {

    prefilled.setDetails(Map.of(CALLER, aggregate.getCustomer()));
    return prefilled;

  }

  /**
   * @param aggregate The workflow aggregate, which is the CALLING case's
   * @param prefilled What the engine knew about the task
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = STEP_TASK_DEFINITION)
  public UserTaskDetails doTheStep(
      final CaseCallingAggregate aggregate,
      final PrefilledUserTaskDetails prefilled) {

    prefilled.setDetails(Map.of(CALLER, aggregate.getCustomer()));
    return prefilled;

  }

}
