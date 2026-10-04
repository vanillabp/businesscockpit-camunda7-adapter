package io.vanillabp.cockpit.camunda7.quarkus.it;

import java.util.function.Consumer;

import org.jboss.shrinkwrap.api.spec.JavaArchive;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;

/**
 * The application every test class of this module boots, with a database of its own.
 * <p>
 * The embedded engine, the outbox and VanillaBP's table of delivered user tasks
 * <code>VANILLABP_TASK_DELIVERY</code> all live in the application's database. The in-memory
 * database stays open until the test JVM ends, and nothing drops those tables when the next test
 * class boots. In one shared database, an open record another class left behind for the same
 * aggregate id names a workflow of a case the next class never started. That makes a test red
 * depending on the order the classes run in.
 * <p>
 * So the database is named after the test class. Every configuration file of this module builds
 * the URL from {@link #DATABASE_NAME_KEY} and has no default for it, so a test class which boots
 * the application some other way does not start, instead of sharing a database. A configuration
 * with two data sources appends a word to the name of each, as
 * <code>business-cockpit-own-data-source.yaml</code> does for the application and the engine.
 */
public final class TestApplication {

  /** The key the configuration files read the name of the database from. */
  public static final String DATABASE_NAME_KEY = "c7-cockpit.test.database-name";

  private TestApplication() {
  }

  /**
   * @param testClass     The test class which boots the application
   * @param configuration The configuration file of the test, which becomes the application's
   *                      <code>application.yaml</code>
   * @return The application, with a database named after the test class. A test adds the address
   *         of the cockpit server and what else is special about it.
   */
  public static QuarkusExtensionTest forTestClass(
      final Class<?> testClass,
      final String configuration) {

    return forTestClass(testClass, configuration, jar -> {
    });

  }

  /**
   * @param testClass     The test class which boots the application
   * @param configuration The configuration file of the test, which becomes the application's
   *                      <code>application.yaml</code>
   * @param more          What this test class adds to the application, like a workflow
   *                      service of a scenario of its own
   * @return The application, with a database named after the test class
   */
  public static QuarkusExtensionTest forTestClass(
      final Class<?> testClass,
      final String configuration,
      final Consumer<JavaArchive> more) {

    return new QuarkusExtensionTest()
        .withApplicationRoot(
            jar -> more.accept(jar
                .addAsResource(configuration, "application.yaml")
                .addAsResource("c7-cockpit/processes/cockpit-process.bpmn")
                .addAsResource(
                    "workflow-module-descriptor/workflow-module", "META-INF/workflow-module")
                .addClass(TestAggregate.class)
                .addClass(TestAggregatePersistence.class)
                .addClass(TestWorkflowService.class)
                // the test class is initialized twice, once while the application is built and
                // again inside the class loader of the running application. Its static field
                // calls this class, and its assertions use the server class, so the copy inside
                // that application needs both
                .addClass(TestApplication.class)
                .addClass(CockpitServer.class)))
        .overrideConfigKey(DATABASE_NAME_KEY, testClass.getSimpleName());

  }

}
