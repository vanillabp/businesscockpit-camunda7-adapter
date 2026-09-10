package io.vanillabp.cockpit.camunda7;

import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.task.TaskDefinition;
import org.camunda.bpm.engine.impl.util.xml.Element;

/**
 * Attaches the Business Cockpit's task listeners while the engine parses a user task.
 * <p>
 * The listeners are added as <b>built-in</b> ones, which is what keeps them out of reach of
 * <code>skipCustomListeners</code>: an operator who reassigns or deletes a task from the
 * Camunda web application asks the engine to skip the custom listeners of the model, and the
 * cockpit still has to learn that the task changed - a task list showing an assignee nobody
 * can correct is worse than no task list. It is also why all four events are taken rather than
 * only the two VanillaBP itself needs.
 * <p>
 * The listener carries the element id and the form key it was parsed with, so a runtime event
 * needs neither a model lookup nor a registry of its own.
 * <p>
 * Why built-in and why after VanillaBP's own is decision 1 in the repository's DECISIONS.md, and
 * that every user task gets them whether or not the application enriches it is decision 2 in the
 * repository's DECISIONS.md.
 */
public class Camunda7UserTaskParseListener extends AbstractBpmnParseListener {

  /**
   * The events the cockpit is attached to. The order is the order the engine fires them in,
   * and it is the order they are added in so that a reader of a parsed definition sees the
   * same sequence.
   */
  static final String[] EVENT_NAMES = {
      TaskListener.EVENTNAME_CREATE, TaskListener.EVENTNAME_UPDATE, TaskListener.EVENTNAME_COMPLETE, TaskListener.EVENTNAME_DELETE
  };

  private final Camunda7CockpitEvents events;

  /**
   * @param events Where the listeners report what they observed
   */
  public Camunda7UserTaskParseListener(
      final Camunda7CockpitEvents events) {

    this.events = events;

  }

  @Override
  public void parseUserTask(
      final Element userTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    if (!(activity.getActivityBehavior() instanceof final UserTaskActivityBehavior behavior)) {
      return;
    }
    final var taskDefinition = behavior.getTaskDefinition();
    final var listener = new Camunda7UserTaskListener(
        events, activity.getId(), taskDefinitionOf(taskDefinition, activity.getId()));
    for (final var eventName : EVENT_NAMES) {
      taskDefinition.addBuiltInTaskListener(eventName, listener);
    }

  }

  /**
   * What the cockpit calls this task: the form key the modeller wrote, which is what a
   * <code>&#64;UserTaskDetailsProvider</code> names and what the cockpit shows a form for. A
   * task without a form key is still a task somebody has to see, so it falls back to its
   * element id - the same fallback the VanillaBP Camunda 7 adapter makes for its own wiring.
   */
  private static String taskDefinitionOf(
      final TaskDefinition taskDefinition,
      final String bpmnTaskId) {

    final var formKey = taskDefinition.getFormKey();
    if (formKey == null) {
      return bpmnTaskId;
    }
    final var written = formKey.getExpressionText();
    return (written == null) || written.isBlank()
        ? bpmnTaskId
        : written;

  }

}
