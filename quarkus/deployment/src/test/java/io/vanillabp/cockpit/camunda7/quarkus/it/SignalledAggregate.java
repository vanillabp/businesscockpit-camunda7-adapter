package io.vanillabp.cockpit.camunda7.quarkus.it;

/**
 * The workflow aggregate of a case a signal started. It is a class of its own because VanillaBP
 * binds one workflow aggregate class to one BPMN process.
 */
public class SignalledAggregate {

  private Long id;

  private String customer;

  public Long getId() {

    return id;

  }

  public void setId(
      final Long id) {

    this.id = id;

  }

  public String getCustomer() {

    return customer;

  }

  public void setCustomer(
      final String customer) {

    this.customer = customer;

  }

}
