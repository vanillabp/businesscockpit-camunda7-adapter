package io.vanillabp.cockpit.camunda7.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.history.HistoryLevel;
import org.camunda.bpm.engine.impl.history.event.HistoryEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.engine.Camunda7EngineCustomizers;
import io.vanillabp.cockpit.camunda7.Camunda7CockpitCustomizer;
import io.vanillabp.cockpit.camunda7.Camunda7EngineSettings;
import io.vanillabp.cockpit.camunda7.Camunda7WorkflowProcesses;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the extension contributes to an engine which is about to be built, and what it refuses to
 * be built with.
 * <p>
 * The refusal is the interesting half, and the reason these tests build a real engine instead of
 * asking a configuration double: an application sets the history level in an engine plugin, and a
 * plugin has its say long after this extension was asked to customize the configuration. Whether
 * the check ever fires is therefore a question about the order the engine runs its own hooks in,
 * which only the engine can answer.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7EngineCustomizingTest {

  private static final String ADAPTER_ID = "c7";

  private static Camunda7CockpitCustomizer aCustomizer() {

    return new Camunda7CockpitCustomizer(
        new Camunda7WorkflowProcesses(), new NameClashAvoidanceService(new MigrationAdapterProperties()), new Camunda7EngineSettings() {

          @Override
          public String configuredTenantId(
              final String adapterId) {

            return null;

          }

          @Override
          public boolean joinsTheApplicationTransaction(
              final String adapterId) {

            return true;

          }

        }, RecordingPublisher::new);

  }

  /**
   * An engine as the Camunda 7 adapter builds it: on its own in-memory database, with the
   * plugins of the application, and with what this extension contributes applied the way the
   * adapter's engine holders apply it.
   *
   * @param database A name of its own per test, so a test never meets what another one left
   * @param plugins What the application contributes, e.g. the level it wants
   * @return The configuration, ready to be built
   */
  private static ProcessEngineConfigurationImpl anEngineOf(
      final String database,
      final ProcessEnginePlugin... plugins) {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName(database);
    configuration.setJdbcUrl("jdbc:h2:mem:%s".formatted(database));
    configuration.setJobExecutorActivate(false);
    configuration.getProcessEnginePlugins().addAll(List.of(plugins));
    Camunda7EngineCustomizers.apply(ADAPTER_ID, configuration, List.of(aCustomizer()));
    return configuration;

  }

  /** What an application which wants a history level of its own writes. */
  private record AnApplicationWanting(String historyLevel) implements ProcessEnginePlugin {

    @Override
    public void preInit(
        final ProcessEngineConfigurationImpl configuration) {

      configuration.setHistory(historyLevel);

    }

    @Override
    public void postInit(
        final ProcessEngineConfigurationImpl configuration) {
      // nothing, the level is set before the engine reads it
    }

    @Override
    public void postProcessEngineBuild(
        final ProcessEngine engine) {
      // nothing, the level is set before the engine reads it
    }

  }

  /**
   * What an application which brings a history level of its own writes. Camunda lets one be
   * registered and named, which is why the extension asks a level what it writes instead of
   * recognizing the four levels shipped with the engine by name.
   */
  private record AnApplicationBringing(HistoryLevel level) implements ProcessEnginePlugin {

    @Override
    public void preInit(
        final ProcessEngineConfigurationImpl configuration) {

      configuration.setCustomHistoryLevels(List.of(level));
      configuration.setHistory(level.getName());

    }

    @Override
    public void postInit(
        final ProcessEngineConfigurationImpl configuration) {
      // nothing, the level is set before the engine reads it
    }

    @Override
    public void postProcessEngineBuild(
        final ProcessEngine engine) {
      // nothing, the level is set before the engine reads it
    }

  }

  /**
   * A level writing what the engine did to a process instance and nothing about its tasks. An
   * id of its own, because the engine stores the id of the level its database was created with.
   */
  private record OnlyWhatHappenedToTheProcessInstance() implements HistoryLevel {

    @Override
    public int getId() {

      return 42;

    }

    @Override
    public String getName() {

      return "process-instances-only";

    }

    @Override
    public boolean isHistoryEventProduced(
        final HistoryEventType eventType,
        final Object entity) {

      // 'process-instance' for a start and an end, 'process-instance-update' for an update
      return eventType.getEntityType().startsWith("process-instance");

    }

  }

  @Test
  @DisplayName("A plugin switching the history off ends the boot")
  public void anEngineWritingNoHistoryEndsTheBoot() {

    final var configuration = anEngineOf(
        "cockpit-history-none", new AnApplicationWanting("none"));

    final var failure = assertThrows(
        IllegalStateException.class, configuration::buildProcessEngine);

    assertTrue(failure.getMessage().contains("none"), failure.getMessage());
    assertTrue(failure.getMessage().contains(ADAPTER_ID), failure.getMessage());
    // what is missing, and the lowest level which writes it
    assertTrue(failure.getMessage().contains("PROCESS_INSTANCE_START"), failure.getMessage());
    assertTrue(failure.getMessage().contains("TASK_INSTANCE_CREATE"), failure.getMessage());
    assertTrue(failure.getMessage().contains("activity"), failure.getMessage());
    // the boot ends while the configuration is being initialized, so there is no half-built
    // engine left running against the database
    assertNull(configuration.getProcessEngine());

  }

  @Test
  @DisplayName("A level of its own which writes nothing about tasks ends the boot")
  public void anEngineWritingNoTaskHistoryEndsTheBoot() {

    final var configuration = anEngineOf(
        "cockpit-history-own", new AnApplicationBringing(new OnlyWhatHappenedToTheProcessInstance()));

    final var failure = assertThrows(
        IllegalStateException.class, configuration::buildProcessEngine);

    assertTrue(failure.getMessage().contains("process-instances-only"), failure.getMessage());
    assertTrue(failure.getMessage().contains("TASK_INSTANCE_CREATE"), failure.getMessage());
    // the lifecycle half of what the cockpit reads is written, so it is not among the complaints
    assertFalse(failure.getMessage().contains("PROCESS_INSTANCE_START"), failure.getMessage());
    assertNull(configuration.getProcessEngine());

  }

  @Test
  @DisplayName("The lowest level writing what the cockpit reads is built")
  public void anEngineWritingActivityHistoryIsBuilt() {

    final var engine = anEngineOf("cockpit-history-activity", new AnApplicationWanting("activity"))
        .buildProcessEngine();

    try {
      assertEquals(
          "activity",
          ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
              .getHistoryLevel()
              .getName());
    } finally {
      engine.close();
    }

  }

  @Test
  @DisplayName("A plugin setting a history level which keeps everything is built")
  public void enoughHistoryIsBuilt() {

    final var engine = anEngineOf("cockpit-history-full", new AnApplicationWanting("full"))
        .buildProcessEngine();

    try {
      assertEquals(
          "full",
          ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
              .getHistoryLevel()
              .getName());
    } finally {
      engine.close();
    }

  }

  @Test
  @DisplayName("The level an engine reads from its own database is judged once it has read it")
  public void anAutomaticLevelIsJudgedAfterTheEngineResolvedIt() {

    final var configuration = anEngineOf("cockpit-history-auto");
    // 'auto' names no level until the engine has looked into its database, which is after every
    // plugin has run - a fresh database answers with the engine's default
    configuration.setHistory("auto");

    final var engine = configuration.buildProcessEngine();

    try {
      assertEquals(
          "audit",
          ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
              .getHistoryLevel()
              .getName());
    } finally {
      engine.close();
    }

  }

  @Test
  @DisplayName("Every engine gets the cockpit's parse listener and its history handler")
  public void everyEngineGetsTheListeners() {

    final var customizer = aCustomizer();

    assertEquals(1, customizer.parseListenersAfter(ADAPTER_ID).size());
    assertEquals(List.of(), customizer.parseListenersBefore(ADAPTER_ID));
    assertNotNull(customizer.historyEventHandler(ADAPTER_ID));
    assertEquals(ADAPTER_ID, customizer.scopeOf(ADAPTER_ID).adapterId());
    assertEquals(EventTransaction.CURRENT, customizer.eventTransactionOf(ADAPTER_ID));

  }

}
