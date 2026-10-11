package io.vanillabp.cockpit.camunda7.springboot.test;

import java.util.List;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

/**
 * The business case of the test: what the workflow is about, and what a details provider
 * changes.
 * <p>
 * It has a version attribute, as every application with a details provider which writes should
 * have. The provider runs while a report is dispatched, in the transaction of that dispatch. So it
 * reads this case long before it writes it back. If the application changes the same case in
 * that time, its change would be lost without a word, because the dispatch writes every field it
 * holds, including the old values. With the version attribute, the later of the two writers gets
 * a conflict instead. The application answers it by repeating its transaction. The dispatch
 * answers it by leaving its outbox entry unfinished, so the report goes out again from a fresh
 * reading of the case. Nobody is promised when that happens.
 * <p>
 * {@code signers} is sent to the BPMS because a model reads it: the multi-instance task of
 * {@code MultiInstanceProcess} loops over that list. Only a boolean and a text mean the same in
 * every expression language. So a list is only sent after it is declared under
 * {@code declared-aggregate-values}, and {@code application.yaml} declares it for every workflow
 * of this aggregate.
 */
@Entity
public class TestAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * The persistence increments it on every write, and a second writer runs into it. It stays in
   * the application: no model of this workflow reads it, and a BPMN model could not decide
   * anything on this number.
   */
  @NoSyncWithBPMS
  @Version
  private Long version;

  private String customer;

  private String note;

  @ElementCollection
  private List<String> signers;

  public Long getId() {

    return id;

  }

  public void setId(
      final Long id) {

    this.id = id;

  }

  public Long getVersion() {

    return version;

  }

  public void setVersion(
      final Long version) {

    this.version = version;

  }

  public String getCustomer() {

    return customer;

  }

  public void setCustomer(
      final String customer) {

    this.customer = customer;

  }

  public List<String> getSigners() {

    return signers;

  }

  public void setSigners(
      final List<String> signers) {

    this.signers = signers;

  }

  public String getNote() {

    return note;

  }

  public void setNote(
      final String note) {

    this.note = note;

  }

}
