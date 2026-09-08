package io.vanillabp.cockpit.camunda7.springboot;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import io.vanillabp.camunda7.Camunda7ProcessingContext;
import io.vanillabp.camunda7.engine.Camunda7EngineCustomizer;
import io.vanillabp.camunda7.springboot.VanillaBpCamunda7Properties;
import io.vanillabp.cockpit.camunda7.Camunda7CockpitCustomizer;
import io.vanillabp.cockpit.camunda7.Camunda7CockpitWiring;
import io.vanillabp.cockpit.camunda7.Camunda7WorkflowProcesses;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;

/**
 * Registers the Camunda 7 half of the Business Cockpit extension on Spring Boot.
 * <p>
 * Nothing here decides anything: what the extension does with an engine is decided in the
 * platform-neutral module of this repository, and this class does what only Spring can do -
 * find the beans and put the extension's own where VanillaBP and the Camunda 7 adapter collect
 * them.
 * <p>
 * It runs after VanillaBP's own auto-configuration, named rather than referenced, because an
 * extension does not compile against a platform integration.
 */
@AutoConfiguration(afterName = "io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration")
@ConditionalOnBean(MigrationAdapterProperties.class)
@EnableConfigurationProperties(VanillaBpCamunda7Properties.class)
@Import(Camunda7CockpitBeanRegistrar.class)
public class Camunda7CockpitAutoConfiguration {

  /**
   * @return Which BPMN processes of which workflow modules this application deployed, shared
   *         by the wiring service filling it and by everything translating an engine's
   *         identifiers back
   */
  @Bean
  public Camunda7WorkflowProcesses businessCockpitCamunda7WorkflowProcesses() {

    return new Camunda7WorkflowProcesses();

  }

  /**
   * @param processes The deployed processes
   * @return This extension's place in VanillaBP's deployment pipeline, taken for a workflow
   *         module which runs on Camunda 7 and for no other
   */
  @Bean
  public ExtensionWiringService<BpmnModelInstance, Camunda7ProcessingContext> businessCockpitCamunda7WiringService(
      final Camunda7WorkflowProcesses processes) {

    return new Camunda7CockpitWiring(processes);

  }

  /**
   * The listeners and the history handler of every Camunda 7 engine this application builds.
   * <p>
   * The type is the concrete one rather than {@link Camunda7EngineCustomizer}, because the
   * bridge of each adapter id takes its scope from here and the adapter collects customizers
   * by their interface either way.
   *
   * @param processes The deployed processes
   * @param scoping VanillaBP's name-clash avoidance
   * @param properties The Camunda 7 adapter's own configuration
   * @param publisher Where an observed event is reported. It is resolved on the first event
   *          rather than now: this bean is asked for while an engine is being built, and the
   *          extension it would return is built from those engines
   * @return The customizer
   */
  @Bean
  public Camunda7CockpitCustomizer businessCockpitCamunda7EngineCustomizer(
      final Camunda7WorkflowProcesses processes,
      final NameClashAvoidanceSupport scoping,
      final VanillaBpCamunda7Properties properties,
      final ObjectProvider<BusinessCockpitEventPublisher> publisher) {

    return new Camunda7CockpitCustomizer(
        processes, scoping, new Camunda7SpringSettings(properties), publisher::getObject);

  }

}
