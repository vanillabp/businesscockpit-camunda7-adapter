# Decision log

Decisions this repository's code points at. A number is handed out once. It is never reused and
never renumbered, so a citation stays resolvable. A decision which is overturned keeps its entry.
That entry is then marked as superseded and names the entry which replaced it.

A citation in code reads `see decision 3 in the repository's DECISIONS.md`, and it names an entry of
THIS repository only. A decision the platform shares has its own entry in
`adapter-platform-integration`, written from that side. One the Business Cockpit shares has its
entry in `business-cockpit`. A pointer into another repository is the fragile kind this log exists
to avoid.

## 1. The cockpit's task listeners are built-in, and they are added after VanillaBP's

The extension attaches a listener to `create`, `update`, `complete` and `delete` of every user
task. It attaches them the way Camunda calls built-in: not as listeners of the model, but as
listeners of the parsed task definition.

Built-in is the point. An operator who reassigns or deletes a task from a task list or from the
Camunda web application asks the engine to skip the custom listeners of the model. Those listeners
belong to the process, and the operator is stepping outside it. The cockpit is not part of that
process, it is the window onto it. A window which stops updating whenever somebody acts by hand
shows a task list nobody can trust. So the four events are taken through the built-in list, which
`skipCustomListeners` does not touch.

The listeners are contributed through the Camunda 7 adapter's `parseListenersAfter`, so they run
behind the ones VanillaBP attached itself. That order matters when a task is completed. VanillaBP's
own listener runs the `@WorkflowTask` method, which may change the workflow aggregate, and the
details provider the cockpit calls afterwards is meant to see the changed one.

Both platforms have a test for it. It reads the parsed task definition of a deployed process and
checks that all four events carry a built-in listener of the cockpit, and that it is the last one.

## 2. Every user task is reported, whether or not the application enriches it - a provider still serves nothing, see decision 13, and only a claimed process counts, see decision 14

The listeners sit on every user task of every deployed process, and every event they see is
reported. A `@UserTaskDetailsProvider` for that task decides how much the cockpit is told about it.
It never decides whether the cockpit is told at all.

Version 1 did it the other way round. It looked for a provider matching the task and dropped the
event where it found none. A user task nobody had written a method for was therefore invisible in
the cockpit, and nothing said why. The cockpit's job is to show the work there is, and a task with
no title but a name from the BPMN is more useful than a task which is not there.

An application upgrading from version 1 will see tasks appear which did not appear before. The wiki
names it, under what changes for a version 1 application.

## 3. A workflow's lifecycle is read from the engine's history, and only for root instances - a called process with an aggregate of its own counts as a root since decision 15

The extension learns that a workflow started, ended or was cancelled from a handler for the
engine's process-instance history events, installed next to the engine's own handler. The
alternative would be execution listeners written into every model. That means rewriting models
their author wrote, and it misses every workflow whose end nobody modelled.

History also answers the one question an end event cannot. A workflow which was terminated from
the outside never reaches an end event, and the engine records that as an end carrying a delete
reason. So `START` becomes created. An `END` with a delete reason becomes cancelled, and any other
`END` becomes completed. Anything else about the instance becomes an update.

Only root process instances are reported. A called process is a step of a business case rather
than a case of its own. For the same reason, the reads answering the cockpit's questions about the
workflows of an aggregate leave the called ones out.

## 4. An engine's identifiers are translated back through the deployed processes, never by parsing - only claimed processes are remembered, see decision 14

A Camunda 7 engine reports a task or a history event with the process definition key and the
tenant it stored. Both depend on the name-clash avoidance the workflow module was deployed with: a
tenant per module under `by-adapter`, a prefixed process id under `use-prefix`, neither under
`none`. Getting from there back to the workflow module and the plain BPMN process id is therefore
not a string operation.

Every process VanillaBP wires is remembered while the deployment pipeline runs, and the way back
is a lookup. For each adapter id the extension asks the adapter's own scoping helper what that
process would be called on that engine, and matches. Cutting a known prefix off a string would
work until the adapter changes how it builds one, and it would quietly mis-attribute a process id
which happens to contain the separator.

An event of a process which is not among the deployed ones is not this application's, and it is
passed over. It comes from another application on the same database, or from a process definition
of an earlier release.

## 5. The outbox entry is written in the engine's transaction, unless the engine has its own

*Superseded by decision 6.* It read the data source name as the answer to a question about
transactions, which is wrong on Quarkus.

An embedded Camunda 7 engine on the application's data source runs its listeners inside the
application's transaction. The entry reporting what the engine did is written in that same
transaction. The cockpit therefore hears about a task if and only if the workflow which created it
was committed, and nothing is reported for work which was rolled back.

An engine configured with `vanillabp.adapters.<id>.data-source-name` commits on its own, and there
is no such transaction to join, so the entry gets one of its own. That is the honest answer rather
than a correct one. The entry and the engine's work then commit separately, and a crash between the
two can leave the cockpit told about something the engine rolled back, or the other way round. The
engine's own transaction is the only place that could be fixed, and an engine which does not share
the application's data source has no such place.

## 6. Which transaction the entry is written in is answered by the platform, not by a property - the Quarkus answer superseded by decision 9

The entry reporting what the engine did belongs in the transaction that work happens in. The
cockpit then hears about a task if and only if the workflow which created it was committed, and a
rolled-back workflow leaves nothing behind. Whether such a transaction exists is decided by how a
platform ties its engine into transactions. Only the platform modules of this repository know that,
so they answer it and the platform-neutral half asks.

On Spring Boot an engine on the application's data source is built with the application's own
transaction manager, and its commands run in the application's transaction. An engine which was
given `vanillabp.adapters.<id>.data-source-name` is built with a transaction manager of its own,
and its commands commit without the application noticing. There the entry gets a transaction of
its own, which is the honest answer rather than a correct one: entry and engine work then commit
separately, and a crash between the two can leave the cockpit told about something the engine
rolled back.

On Quarkus the adapter builds the engine on the engine's JTA configuration with the container's
transaction manager. Every command therefore joins the transaction of whoever called it, including
the engine of an adapter id which names a data source of its own. A named data source decides which
database the engine writes to and nothing about who commits it. Reading that key here, as
decision 5 did, would open a second transaction for the entry while the engine's own work is
still uncommitted, and a rollback would leave the cockpit showing a task which never existed.

This costs an application which gives its Quarkus engine a database of its own. The entry and the
engine's work are then two data sources in one JTA transaction, and Agroal enlists a second data
source only as an XA resource. Both have to be configured with
`quarkus.datasource.<name>.jdbc.transactions: xa`, or writing the entry fails while the engine
works. That is the price of the guarantee, and it is named in the wiki rather than worked
around here.

## 7. An engine keeping less history than `audit` ends the boot

*Superseded by decision 8.* It named `audit` as the lowest level which works, on the assumption
that `activity` writes no task history. It does:
`org.camunda.bpm.engine.impl.history.HistoryLevelActivity` produces the task-instance events as
well as the process-instance ones, and what `audit` adds is variable and form-property history,
which the cockpit does not read.

Everything the cockpit shows about a workflow comes from the engine's history. The lifecycle is
read from the process-instance history events, and a task somebody finished before the report was
dispatched is read from the historic task instance. At history level `none` the engine writes
neither, and at `activity` it writes no task history. An application configured that way would
run with a cockpit which stays empty and says nothing about why.

So the extension asks the engine which level it ended up with and ends the boot below `audit`. The
message names the level it found, the two levels which work, and the fact that the engine's own
default is one of them. It asks through an engine plugin of its own rather than while it customizes
the configuration. The customizers run before the plugins of the application, which is where a level
is set, and `auto` names no level at all until the engine has read the one its database was created
with. The level is not a key of this extension and not one of the Camunda 7 adapter either. An
application which sets it does so through an engine plugin or a customizer of its own, which is what
the message points at.

## 8. An engine writing none of the history the cockpit reads ends the boot

*Superseded by decision 10.* The check itself stayed, and so did the reason for it. What fell away
is the task history: a report is built while the listener runs, so the cockpit never reads a
historic task instance, and an engine which writes none of that runs the cockpit fine.

A workflow's lifecycle is what the engine wrote about the process instance. A user task somebody
finished before its report was dispatched is read back from what it wrote about the task instance.
Camunda writes both from history level `activity` upwards. What `audit` adds on top is variable and
form-property history, and the cockpit reads neither. So `activity` is the lowest level an
application can run this extension on, and `none` is the only one of Camunda's own levels which
leaves the cockpit with nothing.

The candidates of a finished task are the one thing which does need more. They come from the
identity-link log, which Camunda keeps at `full` alone. Below that, such a task is reported without
candidates rather than with wrong ones. That is a task shown with less detail, not a cockpit which
stays empty, so it is no reason to end a boot.

The check asks the level instead of comparing its name against a list. A history level is an
object an application may bring along itself through `setCustomHistoryLevels`, and it answers per
event type. Asked with no entity it says whether it writes events of that type at all, which is
exactly what is being asked here. A list of names would judge a level nobody but the application
knows, and it would wave through a level called `activity` which somebody replaced with one
writing less.

Where an event the cockpit reads is missing, the boot ends. The message names the engine's level,
the events it does not write, the lowest level which writes them, and the fact that the engine's
own default already does. An application which never touched the level will therefore never see it.

## 9. The adapter answers whether the entry joins the engine's transaction, and it answers the same on both platforms

The guarantee is the one decision 6 wrote down, and it has not changed: the entry reporting what
the engine did belongs in the transaction that work happens in, so a rolled-back workflow leaves
nothing behind. What changed is who answers the question, and what the answer is on Quarkus.

The Camunda 7 adapter answers it now, as `Camunda7EngineFacts.joinsTheApplicationTransaction()`.
The answer is the same sentence on both platforms: an engine which runs on a data source of
its own does not join the caller's transaction, so the entry gets a transaction of its own.

Decision 6 said the opposite for Quarkus. It argued that the container's transaction manager
makes every command join the transaction of whoever called it, and that a named data source
decides only which database is written to. That is true about enlistment and untrue about
atomicity. A JTA transaction around two data sources which are not XA is still two commits, so
the entry and the engine's work were never committed together. What the old answer bought was
the appearance of one transaction, and the price it named was configuring both data sources as
XA. That price is gone with the answer.

What an application gives up is what the Spring Boot half of decision 6 already named as the
honest answer rather than the correct one. Entry and engine work commit separately, and a crash
between the two can leave the cockpit told about something the engine rolled back. An
application which wants them committed together puts the engine on the application's data
source, and then this answer is `true` on either platform.

## 10. The report is built at the event, out of what the engine handed over

The Business Cockpit used to build a report when the outbox entry was dispatched. It builds it at
the moment of the event now, and the report travels with the entry. That is the cockpit's own
decision, written down in `business-cockpit`. What follows here is what it means for a Camunda 7
engine.

A report is built inside the engine command which fired the event. The command has not been
flushed yet. So the history of this very event is not in the database, and a query for it answers
nothing at all: a workflow which was just started is not in the historic process instances, and a
task which is being completed answers no variables any more. The old reading through the engine's
query API therefore cannot stay where a report is built.

It does not have to. Everything a report needs is in what the engine handed over. A task listener
gets the task, its execution and the process definition that execution runs on, and that carries
the assignee, the candidates, the due and follow-up dates, the BPMN names, the business key and
every variable the task can see. The handler of a process-instance history event gets the business
key, the process definition with its name and, for a start, who started the case.

The query stays for what the application asks. `BusinessCockpitService.getUserTask` reads a task
somebody names, and `aggregateChanged` says that a case changed. No event is near either of
them, and both are about the state of now. `Camunda7CockpitBridge` therefore has two ways in, and
which one runs is decided by the caller rather than configured: while this thread reports an event
about that very task or that very workflow, the answer comes out of the event, and otherwise out of
the engine. `Camunda7EventBeingReported` is what carries it from one half to the other.

Three things get better. A completed task reports the variables it saw, which no later reading could
recover. A report which waits in the outbox, because the cockpit server is down, says what
was true when it happened rather than what is true when the server comes back. And a workflow which
was just created is reported at all: its report used to be built from a historic process instance
which does not exist yet.

The `initiator` of a user task is not prefilled at all. Camunda 7 records who started a case in the
history of the process instance and nowhere else. A start event of that instance carries it, an end
event does not repeat it, and an event about a task never had it without a second query, which is
exactly the query this decision took out. Nothing is put there instead, and this adapter leaves the
field empty. What a report finally carries is answered where the rule lives, which is the cockpit's
extension: it demands an initiator and takes the value a details provider of the application set,
or the constant for a module which knows no action a user causes. An adapter which knows nobody
writes nobody, and that is the whole of its part.

The `initiator` of a case is untouched. It is who started it, and it is reported with the case's
creation. An end reports none, and the cockpit keeps what the creation told it.

Two readings are gone with all this. A task the engine has finished with is not looked up in the
historic task instances any more, and its candidates are not replayed from the identity-link log. The report
of its end was built while the task was still there, and a question of the application is about a
task which is running. Nothing else ever read them, so an engine which writes no task history runs
this extension fine, and the boot check of decision 8 asks for the process-instance events alone.

## 11. Every wait for a report names the case it is about

Decided on 2026-10-03.

No test of this repository waits for the next report on a collecting path any more. A wait on
`/usertask/created` or `/workflow/created` names the business key of the case it is about, which is
what every report of that case carries and no other report does. Nine waits were on such a path,
and the cockpit server of the test refuses them from now on.

Why:

Every test of a module reports into one server, and the dispatch of an outbox entry outlives the
test which caused it. A report of an earlier test therefore arrives on a collecting path at any
moment, and a wait which takes the next report of its kind is satisfied by it. The test then walks
on although nothing it provoked has happened yet. Four of these nine had a `forgetRequests()`
behind them, three of them on the very next line, and that forget threw away the report the test
had really been waiting for.

The find came out of the Process-Engine-API adapter, where eight waits had the same shape. This
repository was measured afterwards and had nine.

What a wait names here:

`Camunda7CockpitTest` already had the idiom, as a local named `thisCase`:

```java
"\"businessId\":\"%s\"".formatted(aggregate.getId())
```

It is a method now, `reportsOf(aggregate)`, so the class says it once instead of nine times. The
business key answers for both paths, because the report of a user task carries the key of its case
as well.

`Camunda7TwoAdapterIdsTest` kept the started case in a variable to be able to name it. It started
one and threw the result away.

What stays:

The `forgetRequests()` calls stay where they are. A test which provokes two reports about the same
case tells them apart by waiting for the first, forgetting it and waiting again, and the
identifier alone cannot do that. Only the wait in front of such a forget was wrong.

The other `awaitAnyRequest` calls stay as they are: they wait on `/usertask/<id>/completed`,
`/workflow/<id>/cancelled` and the like, and only the case under test can report there.

Where this is referred to, each in its own words rather than by number:

- `spring-boot/.../Camunda7CockpitTest.java`: 8 waits and the `reportsOf` method
- `spring-boot/.../Camunda7TwoAdapterIdsTest.java`: 1 wait
- the decision log of `business-cockpit`: why the cockpit server refuses the wait instead
  of trusting the next test class to get it right

## 12. A workflow which gets its first name is reported as created, with the moment it started

Decided on 2026-10-04 for story 1440. Before, such a workflow reached the cockpit as an update.

Related entries: 3 (a workflow's lifecycle is read from the engine's history) and 10 (the report is
built at the event). This entry adds to 3 and changes neither of them.

A timer, a signal or a message correlated past VanillaBP starts a process instance without a
business key. The engine writes the start into its history at once, and the history event carries
no business key. The extension has no workflow aggregate to report it for, so it skips it.

The Camunda 7 adapter then asks the application to build the aggregate, in the listener of the
start event, and writes the aggregate's id into the business key. The engine writes that as a
change of the process instance. The extension reported it as `UPDATED`, with the clock of that
moment as its time.

`Camunda7BpmsStartedWorkflowTest` measured it on Spring Boot before the change. A signal start
arrived once, as `/workflow/<id>/updated`, 11 milliseconds after the instance started. A start
through `ProcessService` arrived once, as `/workflow/created`, at the moment the instance started.

The cockpit server creates a case from a change it never saw created, which is decision 36 in
`business-cockpit`. So the case was there, but it began at the time of the change.

A start without a business key is remembered, with the moment it started. The next update of that
instance which carries a business key is reported as `CREATED`, at the remembered moment. So the
case arrives once, as created, like a case the application started.

The memory lives as long as the engine command which started the instance. A listener on the
command context drops the entry when the command ends or fails. The name arrives in that same
command, so nothing more is needed. A start which never gets a name is no case of the cockpit. An
update in a later command is a real update and stays `UPDATED`.

Why not the other way, which waits with the start until the command ends? It would report the
start late and still have to keep the update from being reported. That is the same memory, plus a
report which is no longer built at its event, against decision 10.

A modeller can put `camunda:asyncBefore` on a start event. The engine then writes the start in one
transaction, and the listener of the start event runs later in a job. The name arrives in a
different command, after the memory was dropped. Such a workflow still arrives as `UPDATED`, and
the server creates the case at the time of the change, as before. Nothing in VanillaBP sets
`asyncBefore` on a start event, so the case needs a model which asks for it.

## 13. A details provider does not serve a user task, so a task only the cockpit shows is marked as served elsewhere

This entry adds to decision 2.

### What changed around this adapter

The platform now asks every task of a claimed process for a `@WorkflowTask` method or for the line
`implemented-externally=true`. Without either, the start ends. The platform states this rule in the
decision log of `adapter-platform-integration`. Before it, the Camunda 7 adapter let a user task
without a method pass and named it once at INFO, and nothing was refused. The test applications of
this repository relied on that. Their user tasks have a `@UserTaskDetailsProvider` and no method,
and after the platform change none of them started.

### What this adapter does about it

A `@UserTaskDetailsProvider` does not count as serving a task (the maintainer, 2026-10-08). The
provider tells the cockpit what to show about a task. It does not do the work the task stands for,
and the start cannot know that a person works the task off in a task list. So decision 2 stays as it
is: every user task is reported, with or without a provider. But a user task of a claimed process
which has no method needs the line, the same as in any other application.

The test applications write the line at the task, by element id, one line per user task. That is the
narrowest place the key has. A line at the workflow or at the workflow module would also work, but
it would quietly cover a user task added to a test model later. The start would then no longer tell
anybody that the new task has neither a method nor a line. The element id is used rather than the
form key because one test process has a user task without a form key, and because the element id is
where the configuration of a task is going.

No test of this repository checked that an unserved user task passes. The test for a user task
without a form key now marks that task by its element id, and still checks that the cockpit is told
about it. The rule itself is tested in the platform.

The wiki now says this next to its link to decision 2: a provider decides what the cockpit shows,
and the line says that no method of the application serves the task.

## 14. Only a process a `@WorkflowService` claims gets a listener and is reported

This entry adds to decisions 2 and 4.

The platform repository `adapter-platform-integration` sorts the processes a Camunda 7 engine runs
into three kinds:

- A: a `@WorkflowService` of the application claims the process, as its `bpmnProcess` or as one of
  its `secondaryBpmnProcesses`.
- B: the workflow module deploys the process, but no `@WorkflowService` claims it. The platform
  allows this only when the process is marked with
  `vanillabp.workflow-modules.<wm>.workflows.<id>.implemented-externally=true`.
- C: the process is foreign. It is only in the engine, for example deployed through the Camunda API.

The platform rule is that VanillaBP leaves B and C as they were modelled: no listener and no change
to the BPMN, and nothing reacts to them. This extension follows the same rule:

- `Camunda7CockpitWiring.wireBpmn` remembers a process only if it is A. The core calls `wireBpmn`
  for B too, so the extension asks itself, through
  `WorkflowTaskWiring.isClaimedByAWorkflowService(workflowModuleId, bpmnProcessId)`. That is the one
  question all three cockpit adapters ask. The extension gets `WorkflowTaskWiring` as a bean on Spring
  Boot and on Quarkus, and it builds no answer of its own out of the election or out of
  `resolveWorkflowAggregateIdName`.
- `Camunda7UserTaskParseListener` attaches the cockpit's listeners only to user tasks of a remembered
  process. It looks the process up by the tenant and the process definition key the engine stores,
  the same lookup decision 4 describes for events. So the listener and the report always agree on
  which processes count, also for a process of another workflow module of the same application.
  Where the module deploys into a tenant, a foreign process without a tenant does not match, even
  when it has the id of a claimed process.
- `Camunda7CockpitEvents` reports nothing about a process it cannot look up. A process of B or C is
  never remembered, so its tasks and its workflows are never reported, also when the process
  instance carries a business key.

Before this change, every process of the module was remembered and every parsed user task got the
listeners. A process of B which ran with a business key appeared in the cockpit as a case. Its
aggregate id was the business key, but no aggregate existed and no details provider could serve it.
The Camunda 8 cockpit adapter never showed such a process, so the same model gave two answers
depending on the BPMS.

A process a call activity starts is A when a `@WorkflowService` names it as a secondary process. Its
user tasks get the listeners and are reported, as before.

Decision 2 says that every user task is reported. That now means every user task of a process of
kind A. Decision 4 says that a process which is not among the remembered ones is passed over. That
now includes B.

## 15. A called process with a workflow aggregate of its own is a case of its own

Decision 3 said that only root process instances are reported. That is true only for a called
process which shares the workflow aggregate of its caller. A called process with an aggregate of its
own is a business case of its own. The core starts it as a workflow of its own and builds its
aggregate, the Camunda 7 adapter writes that aggregate's id into the business key, and the cockpit
now shows it as a case too. This follows decision 61 of `business-cockpit` (story 1472):
notifications form one group per case, and only a process with its own aggregate forms a group of its
own.

**The case of an instance** is the highest instance above it which can be reached by calls that
share the aggregate. The walk goes up the call hierarchy and stops at the first caller which does not
share it. In a chain Order → Shipping → Packing, where Packing works on Shipping's aggregate and
Shipping has its own, the case of Packing is Shipping.

**The core decides which processes share an aggregate.** The extension asks
`WorkflowTaskWiring#workflowsShareTheWorkflowAggregate` with the plain process ids. The Camunda 7
adapter asks the same question for its call activities (`Camunda7CallActivities`). The extension
builds no check of its own, and it does not use `BpmsInitiatedStartInvoker#startsAWorkflowOfItsOwn`:
that answer is `true` for a process nobody declares and says nothing about the caller. A process the
application does not claim is unknown to the core and shares nothing. So a call from such a process
starts a case of its own. VanillaBP does not design for processes it did not deploy, so this is
accepted.

What changes:

- The history handler reports the start and the end of a called instance which does not share its
  caller's aggregate. A called instance is started without a business key, so its start is reported
  once the adapter wrote the name, as decision 12 says.
- A user task names the case as `workflowId`, and the instance it sits in as `subWorkflowId` only
  where that is another one.
- `workflowsOfAggregate` keeps a called instance which does not share its caller's aggregate.
- `userTasksOfAggregate` and `userTaskOfAggregate` answer only tasks of a process which works on the
  aggregate asked about. The business key alone does not say that: two aggregate classes may count
  their ids alike, and then a task of a case of its own carried the same key as the caller's case.

**Two ways to walk.** A report at an event walks the executions the engine holds in its running
command, because an instance started in the same command is not in the history yet. A read outside
of such a command walks the history, which still knows an instance which ended. The class is
`Camunda7BusinessCases`. Where the running command does not hold the caller of a history event,
the instance is taken as a step, which is what every called process was before.

`Camunda7BusinessCasesTest` shows both walks. `Camunda7CalledProcessCaseTest` shows it on an engine:
one case calls a step on its own aggregate and a process with an aggregate of its own at the same
time. It also checks the three calls of `BusinessCockpitService` which read VanillaBP's note of the
start first (`aggregateChanged` twice and `getUserTask`), for both kinds of case.
