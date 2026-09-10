package io.vanillabp.cockpit.camunda7.quarkus.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.cockpit.camunda7.quarkus.Camunda7CockpitProducer;

/**
 * What the Camunda 7 half of the Business Cockpit extension has to say at build time.
 * <p>
 * It produces no VanillaBP build item: an extension announces itself by the beans it produces,
 * unlike a BPMS adapter. The engine customizer among those beans is already on the unremovable
 * list of the Camunda 7 adapter's own deployment module, so nothing has to be said about it
 * here either.
 */
class Camunda7CockpitProcessor {

  private static final String FEATURE = "vanillabp-business-cockpit-camunda7";

  /**
   * @param featureProducer Where the feature is announced, so that a booting application lists
   *          the extension
   * @return The producer class, as a bean nothing may remove
   */
  @BuildStep
  AdditionalBeanBuildItem registerProducer(
      final BuildProducer<FeatureBuildItem> featureProducer) {

    featureProducer.produce(new FeatureBuildItem(FEATURE));
    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(Camunda7CockpitProducer.class)
        .setUnremovable()
        .build();

  }

}
