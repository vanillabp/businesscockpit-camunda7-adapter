# Decision log

Decisions this repository's code points at. A number is handed out once and never reused or
renumbered, so a citation stays resolvable; a decision which gets overturned keeps its entry,
marked as superseded and naming the entry which replaced it.

A citation in code reads `see decision 3 in the repository's DECISIONS.md`, and it names an entry of
THIS repository only. A decision which the platform shares has its own entry in
`adapter-platform-integration`, written from that side, and one the Business Cockpit shares has its
entry in `business-cockpit`; a pointer into another repository is the fragile kind this log exists
to avoid.

## 1. The cockpit's task listeners are built-in, and they are added after VanillaBP's

The extension attaches a listener to `create`, `update`, `complete` and `delete` of every user
task, and it attaches them the way Camunda calls built-in: not as listeners of the model, but as
listeners of the parsed task definition.

Built-in is the point. An operator who reassigns or deletes a task from a task list or from the
Camunda web application asks the engine to skip the custom listeners of the model, because those
belong to the process and the operator is stepping outside it. The cockpit is not part of that
process, it is the window onto it, and a window which stops updating whenever somebody acts
manually shows a task list nobody can trust. So the four events are taken through the built-in
list, which `skipCustomListeners` does not touch.

The listeners are contributed through the Camunda 7 adapter's `parseListenersAfter`, so they run
behind the ones VanillaBP attached itself. That order matters when a task is completed: VanillaBP's
own listener runs the `@WorkflowTask` method, which may change the workflow aggregate, and the
details provider the cockpit invokes afterwards is meant to see the changed one.

Both platforms have a test which reads the parsed task definition of a deployed process and
insists that all four events carry a built-in listener of the cockpit, and that it is the last
one of them.

## 2. Every user task is reported, whether or not the application enriches it

The listeners sit on every user task of every deployed process, and every event they see is
reported. Whether the application declares a `@UserTaskDetailsProvider` for that task decides only
how much the cockpit is told about it, never whether it is told at all.

Version 1 did it the other way round: it looked for a provider matching the task and dropped the
event where it found none, so a user task nobody had written a method for was invisible in the
cockpit and nothing said why. The cockpit's job is to show the work there is, and a task with no
title but a name from the BPMN is more useful than a task which is not there.

The consequence for an application upgrading from version 1 is that tasks appear which did not
appear before. It is named in the wiki, under what changes for a version 1 application.

## 3. A workflow's lifecycle is read from the engine's history, and only for root instances

The extension learns that a workflow started, ended or was cancelled from a handler for the
engine's process-instance history events, installed next to the engine's own handler. The
alternative would be execution listeners written into every model, which means rewriting models
their author wrote and missing every workflow whose end nobody modelled.

History also answers the one question an end event cannot: a workflow which was terminated from
the outside never reaches an end event, and the engine records that as an end carrying a delete
reason. So `START` becomes created, an `END` with a delete reason becomes cancelled, any other
`END` becomes completed, and anything else about the instance becomes an update.

Only root process instances are reported. A called process is a step of a business case rather
than a case of its own, which is also why the reads answering the cockpit's questions about the
workflows of an aggregate leave the called ones out.

## 4. An engine's identifiers are translated back through the deployed processes, never by parsing

A Camunda 7 engine reports a task or a history event with the process definition key and the
tenant it stored, and both depend on the name-clash avoidance the workflow module was deployed
with: a tenant per module under `by-adapter`, a prefixed process id under `use-prefix`, neither
under `none`. Getting from there back to the workflow module and the plain BPMN process id is
therefore not a string operation.

Every process VanillaBP wires is remembered while the deployment pipeline runs, and the way back
is a lookup: for each adapter id the extension asks the adapter's own scoping helper what that
process would be called on that engine, and matches. Cutting a known prefix off a string would
work until the adapter changes how it builds one, and it would silently mis-attribute a process id
which happens to contain the separator.

An event of a process which is not among the deployed ones is not this application's, and it is
passed over: another application on the same database, or a process definition of an earlier
release.

## 5. The outbox entry is written in the engine's transaction, unless the engine has its own

*Superseded by decision 6.* It read the data source name as the answer to a question about
transactions, which is wrong on Quarkus.

An embedded Camunda 7 engine on the application's data source runs its listeners inside the
application's transaction. The entry reporting what the engine did is written in that same
transaction, so the cockpit hears about a task if and only if the workflow which created it was
committed, and nothing is reported for work which was rolled back.

An engine configured with `vanillabp.adapters.<id>.data-source-name` commits on its own and there
is no such transaction to join, so the entry gets one of its own. That is the honest answer rather
than a correct one: the entry and the engine's work then commit separately, and a crash between
the two can leave the cockpit told about something the engine rolled back, or the other way round.
The engine's own transaction is the only place that could be fixed, and an engine which does not
share the application's data source has no such place.

## 6. Which transaction the entry is written in is answered by the platform, not by a property

The entry reporting what the engine did belongs in the transaction that work happens in: the
cockpit then hears about a task if and only if the workflow which created it was committed, and a
rolled-back workflow leaves nothing behind. Whether such a transaction exists is decided by how a
platform ties its engine into transactions, and only the platform modules of this repository know
that, so they answer it and the platform-neutral half asks.

On Spring Boot an engine on the application's data source is built with the application's own
transaction manager and its commands run in the application's transaction; an engine which was
given `vanillabp.adapters.<id>.data-source-name` is built with a transaction manager of its own,
and its commands commit without the application noticing. There the entry gets a transaction of
its own, which is the honest answer rather than a correct one: entry and engine work then commit
separately, and a crash between the two can leave the cockpit told about something the engine
rolled back.

On Quarkus the adapter builds the engine on the engine's JTA configuration with the container's
transaction manager, so every command joins the transaction of whoever called it - including the
engine of an adapter id which names a data source of its own. A named data source decides which
database the engine writes to and nothing about who commits it. Reading that key here, as
decision 5 did, would open a second transaction for the entry while the engine's own work is
still uncommitted, and a rollback would leave the cockpit showing a task which never existed.

What that costs an application which gives its Quarkus engine a database of its own: the entry
and the engine's work are then two data sources in one JTA transaction, and Agroal enlists a
second data source only as an XA resource. Both have to be configured with
`quarkus.datasource.<name>.jdbc.transactions: xa`, or writing the entry fails while the engine
works. That is the price of the guarantee, and it is named in the wiki rather than worked
around here.

## 7. An engine keeping less history than `audit` ends the boot

Everything the cockpit shows about a workflow comes from the engine's history: the lifecycle is
read from the process-instance history events, and a task somebody finished before the report was
dispatched is read from the historic task instance. At history level `none` the engine writes
neither, at `activity` it writes no task history, and an application configured that way would
run with a cockpit which stays empty and says nothing about why.

So the extension reads the level the engine is about to be built with and ends the boot below
`audit`, naming the level it found, the two levels which work and the fact that the engine's own
default is one of them. The level is not a key of this extension and not one of the Camunda 7
adapter either: an application which sets it does so through an engine plugin or a customizer of
its own, which is what the message points at.
