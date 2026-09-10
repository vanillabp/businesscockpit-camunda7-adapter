package io.vanillabp.cockpit.camunda7.quarkus;

import java.util.List;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;

import io.quarkus.arc.Unremovable;
import io.vanillabp.camunda7.Camunda7Adapter;
import io.vanillabp.camunda7.Camunda7ProcessingContext;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import io.vanillabp.camunda7.quarkus.runtime.VanillaBpCamunda7Properties;
import io.vanillabp.cockpit.camunda7.Camunda7CockpitBridge;
import io.vanillabp.cockpit.camunda7.Camunda7CockpitCustomizer;
import io.vanillabp.cockpit.camunda7.Camunda7CockpitWiring;
import io.vanillabp.cockpit.camunda7.Camunda7WorkflowProcesses;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Registers the Camunda 7 half of the Business Cockpit extension on Quarkus - the twin of the
 * Spring Boot module's auto-configuration, doing the same things with CDI.
 * <p>
 * The producers are <code>&#64;Singleton</code> rather than
 * <code>&#64;ApplicationScoped</code>: what they produce has no no-argument constructor and is
 * therefore not client-proxyable.
 */
@ApplicationScoped
public class Camunda7CockpitProducer {

  /**
   * @return Which BPMN processes of which workflow modules this application deployed, shared
   *         by the wiring service filling it and by everything translating an engine's
   *         identifiers back
   */
  @Produces
  @Singleton
  @Unremovable
  public Camunda7WorkflowProcesses businessCockpitCamunda7WorkflowProcesses() {

    return new Camunda7WorkflowProcesses();

  }

  /**
   * @param processes The deployed processes
   * @return This extension's place in VanillaBP's deployment pipeline, taken for a workflow
   *         module which runs on Camunda 7 and for no other
   */
  @Produces
  @Singleton
  @Unremovable
  public ExtensionWiringService<BpmnModelInstance, Camunda7ProcessingContext> businessCockpitCamunda7WiringService(
      final Camunda7WorkflowProcesses processes) {

    return new Camunda7CockpitWiring(processes);

  }

  /**
   * The listeners and the history handler of every Camunda 7 engine this application builds.
   *
   * @param processes The deployed processes
   * @param scoping VanillaBP's name-clash avoidance
   * @param properties The Camunda 7 adapter's own configuration
   * @param publisher Where an observed event is reported. It is resolved on the first event
   *          rather than now: this bean is asked for while an engine is being built, and the
   *          extension it would return is built from those engines
   * @return The customizer
   */
  @Produces
  @Singleton
  @Unremovable
  public Camunda7CockpitCustomizer businessCockpitCamunda7EngineCustomizer(
      final Camunda7WorkflowProcesses processes,
      final NameClashAvoidanceSupport scoping,
      final VanillaBpCamunda7Properties properties,
      final Instance<BusinessCockpitEventPublisher> publisher) {

    return new Camunda7CockpitCustomizer(
        processes, scoping, new Camunda7QuarkusSettings(properties), publisher::get);

  }

  /**
   * One bridge per configured Camunda 7 adapter id, because during a migration each engine
   * holds workflows of its own and the cockpit addresses a workflow by the engine holding it.
   * <p>
   * They are produced as one list rather than as one bean each: how many there are is decided
   * by the configuration, which a producer method cannot express. The cockpit's neutral half
   * collects both shapes, the same way VanillaBP's own Quarkus integration collects the
   * adapter deployment services of a BPMS adapter.
   * <p>
   * WHICH adapter ids those are is {@code MigrationAdapterProperties#adapterIdsOfType}, the same
   * answer the Spring Boot half reads through the platform's registrar support. Filtering the
   * configured types is not that answer: an id named in <code>prioritized-adapters</code> needs
   * no section of its own, and an application which configured nothing at all has the id the
   * classpath derives - so a migration setup and a single-dependency application are exactly the
   * two cases where an extension answering it itself registers no bridge while the adapter
   * registers fine.
   *
   * @param properties VanillaBP's resolved configuration, which names the configured adapters
   * @param engines The engines the Camunda 7 adapter built
   * @param customizer The customizer, which owns the scope of each engine
   * @param processes The deployed processes
   * @return One bridge per configured Camunda 7 adapter id
   */
  @Produces
  @Singleton
  @Unremovable
  public List<BusinessCockpitBpmsBridge> businessCockpitCamunda7Bridges(
      final MigrationAdapterProperties properties,
      final Camunda7QuarkusEngineRegistry engines,
      final Camunda7CockpitCustomizer customizer,
      final Camunda7WorkflowProcesses processes) {

    return properties
        .adapterIdsOfType(Camunda7Adapter.ADAPTER_TYPE)
        .stream()
        .<BusinessCockpitBpmsBridge>map(
            adapterId -> new Camunda7CockpitBridge(
                customizer.scopeOf(adapterId), processes, engines
                    .engineFor(adapterId)
                    .getProcessEngine()))
        .toList();

  }

}
