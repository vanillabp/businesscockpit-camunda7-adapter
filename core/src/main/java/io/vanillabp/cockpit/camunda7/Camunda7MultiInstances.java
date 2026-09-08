package io.vanillabp.cockpit.camunda7;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.bpmn.behavior.MultiInstanceActivityBehavior;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.camunda.bpm.model.xml.ModelInstance;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import io.vanillabp.camunda7.deployment.Camunda7DeploymentService;
import io.vanillabp.integration.extension.spi.handler.HandlerMultiInstance;

/**
 * The multi-instance context of a user task, for the
 * <code>&#64;MultiInstanceElement</code>, <code>&#64;MultiInstanceIndex</code> and
 * <code>&#64;MultiInstanceTotal</code> parameters a details provider may declare.
 * <p>
 * Camunda 7 keeps the item, the index and the total in variables of the executions the task
 * hangs below, so the answer is found by walking from the task's execution up through its
 * parents and its calling processes and collecting every multi-instance activity on the way.
 * That walk needs the execution tree, which lives inside an engine command, and it is the one
 * thing about a task the engine's query API cannot answer.
 * <p>
 * A task which is in no multi-instance activity yields an empty map, and so does a task the
 * engine no longer holds: a details provider of a completed task then sees no multi-instance
 * parameters, which is the same thing it sees for a task that never had any.
 */
public final class Camunda7MultiInstances {

  /**
   * The attribute naming the variable each instance gets its item in. It is read
   * namespace-generically rather than through the typed getter of the Camunda model API: the
   * Camunda 7 forks renamed those getters along with their packages, and an attribute read by
   * namespace and name survives that rename.
   */
  private static final String ELEMENT_VARIABLE_ATTRIBUTE = "elementVariable";

  private Camunda7MultiInstances() {
  }

  /**
   * @param engine The engine holding the execution
   * @param executionId The execution the user task runs in
   * @return The multi-instance activities the task is nested in, keyed by BPMN element id and
   *         outermost first
   */
  public static Map<String, HandlerMultiInstance> of(
      final ProcessEngine engine,
      final String executionId) {

    if (executionId == null) {
      return Map.of();
    }
    return ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
        .getCommandExecutorTxRequired()
        .execute(
            commandContext -> collect(
                commandContext.getExecutionManager().findExecutionById(executionId)));

  }

  private static Map<String, HandlerMultiInstance> collect(
      final ExecutionEntity execution) {

    if (execution == null) {
      return Map.of();
    }
    final var element = execution.getBpmnModelElementInstance();
    if (element == null) {
      return Map.of();
    }
    final var model = element.getModelInstance();

    final var innermostFirst = new ArrayList<Map.Entry<String, HandlerMultiInstance>>();
    var current = execution;
    while (current != null) {
      multiInstanceOf(model, current).ifPresent(innermostFirst::add);
      current = current.getSuperExecution() != null
          ? current.getSuperExecution()
          : current.getParent();
    }

    final var outermostFirst = new LinkedHashMap<String, HandlerMultiInstance>();
    for (var index = innermostFirst.size() - 1; index >= 0; index--) {
      outermostFirst
          .putIfAbsent(innermostFirst.get(index).getKey(), innermostFirst.get(index).getValue());
    }
    return Map.copyOf(outermostFirst);

  }

  private static Optional<Map.Entry<String, HandlerMultiInstance>> multiInstanceOf(
      final ModelInstance model,
      final ExecutionEntity execution) {

    final var element = currentElementOf(model, execution);
    if (!(element instanceof final Activity activity)) {
      return Optional.empty();
    }
    if (!(activity
        .getLoopCharacteristics() instanceof final MultiInstanceLoopCharacteristics loop)) {
      return Optional.empty();
    }
    final var index = execution.getVariable(MultiInstanceActivityBehavior.LOOP_COUNTER);
    final var total = execution.getVariable(MultiInstanceActivityBehavior.NUMBER_OF_INSTANCES);
    if (!(index instanceof final Integer itemNo) || !(total instanceof final Integer totalCount)) {
      return Optional.empty();
    }
    final var elementVariable = loop
        .getAttributeValueNs(Camunda7DeploymentService.CAMUNDA_NS, ELEMENT_VARIABLE_ATTRIBUTE);
    final var item = elementVariable == null
        ? null
        : execution.getVariable(elementVariable);
    return Optional
        .of(
            Map
                .entry(
                    activity.getId(),
                    new HandlerMultiInstance(item, itemNo.intValue(), totalCount.intValue())));

  }

  /**
   * The BPMN element an execution stands on. An execution of an embedded subprocess has none
   * of its own, and its activity instance id then names the element as
   * <code>&lt;element id&gt;:&lt;instance id&gt;</code>.
   */
  private static ModelElementInstance currentElementOf(
      final ModelInstance model,
      final ExecutionEntity execution) {

    if (execution.getBpmnModelElementInstance() != null) {
      return execution.getBpmnModelElementInstance();
    }
    final var activityInstanceId = execution.getActivityInstanceId();
    if (activityInstanceId == null) {
      return null;
    }
    final var elementMarker = activityInstanceId.indexOf(':');
    return elementMarker == -1
        ? null
        : model.getModelElementById(activityInstanceId.substring(0, elementMarker));

  }

}
