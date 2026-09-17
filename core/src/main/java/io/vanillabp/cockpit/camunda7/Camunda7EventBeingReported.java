package io.vanillabp.cockpit.camunda7;

import java.util.Optional;

import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;

/**
 * What the engine handed over for the event this thread is reporting right now.
 * <p>
 * The Business Cockpit builds a report at the moment of the event. It asks
 * {@link Camunda7CockpitBridge} what the engine says about the task or the workflow, and it asks
 * by identifiers only. Inside the engine command which fires the event those identifiers cannot
 * be looked up: the task listener runs before the command is flushed, so the history of this very
 * event is not written yet, and a task the command is about to delete is still there but says
 * nothing about what was just done to it. What the engine DID hand over says all of it, and that
 * is the task of the listener and the history event of the process instance.
 * <p>
 * So the two halves of this extension pass it from one to the other on the thread they share.
 * {@link Camunda7CockpitEvents} puts the event here for as long as the report of it is being
 * built, and the bridge takes it out again. Everything in between runs on that one thread: the
 * cockpit's neutral half builds the report, calls the details provider and writes the outbox
 * entry, all inside the call which reported the event.
 * <p>
 * Asked about anything else, this answers nothing and the bridge reads the engine instead. That
 * is what a question of <code>BusinessCockpitService</code> is, and it is also what a details
 * provider asking about a second task while the first one is being reported is. The answer is
 * keyed by the identifier for exactly that reason: an event is only ever the answer about its own
 * task or its own workflow.
 */
public final class Camunda7EventBeingReported {

  /**
   * One event being reported. Exactly one of the two is set: a report is about a user task or
   * about a workflow, never about both.
   *
   * @param userTask The task the listener was called with
   * @param workflow The history event of the process instance
   */
  private record Reported(
                          DelegateTask userTask,
                          HistoricProcessInstanceEventEntity workflow) {
  }

  private final ThreadLocal<Reported> beingReported = new ThreadLocal<>();

  /**
   * Reports one user task, with the task the engine handed over available while that runs.
   *
   * @param task The task of the listener
   * @param report What reports it
   */
  public void whileReporting(
      final DelegateTask task,
      final Runnable report) {

    whileReporting(new Reported(task, null), report);

  }

  /**
   * Reports one workflow, with the history event available while that runs.
   *
   * @param event The history event of the process instance
   * @param report What reports it
   */
  public void whileReporting(
      final HistoricProcessInstanceEventEntity event,
      final Runnable report) {

    whileReporting(new Reported(null, event), report);

  }

  /**
   * @param userTaskId The task the bridge was asked about
   * @return The task of the event being reported, or empty where this thread reports no event
   *         about that task
   */
  public Optional<DelegateTask> userTask(
      final String userTaskId) {

    final var reported = beingReported.get();
    if ((reported == null) || (reported.userTask() == null) || (userTaskId == null)) {
      return Optional.empty();
    }
    return userTaskId.equals(reported.userTask().getId())
        ? Optional.of(reported.userTask())
        : Optional.empty();

  }

  /**
   * @param workflowId The workflow the bridge was asked about
   * @return The history event of the event being reported, or empty where this thread reports no
   *         event about that workflow
   */
  public Optional<HistoricProcessInstanceEventEntity> workflow(
      final String workflowId) {

    final var reported = beingReported.get();
    if ((reported == null) || (reported.workflow() == null) || (workflowId == null)) {
      return Optional.empty();
    }
    return workflowId.equals(reported.workflow().getProcessInstanceId())
        ? Optional.of(reported.workflow())
        : Optional.empty();

  }

  /**
   * The event of an enclosing report is put back rather than thrown away. Reporting one event
   * while another is being reported is not something this extension does, but a details provider
   * is application code and this class must not be the thing which makes it misbehave.
   */
  private void whileReporting(
      final Reported event,
      final Runnable report) {

    final var enclosing = beingReported.get();
    beingReported.set(event);
    try {
      report.run();
    } finally {
      if (enclosing == null) {
        beingReported.remove();
      } else {
        beingReported.set(enclosing);
      }
    }

  }

}
