# CI compile errors

## kotlin e: lines
```
55:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:5:30 Conflicting import: imported name 'FragmentActivity' is ambiguous.
56:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:7:30 Conflicting import: imported name 'FragmentActivity' is ambiguous.
57:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:296:57 Unresolved reference 'ActivityManager'.
58:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:297:20 Unresolved reference 'ActivityManager'.
59:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:298:12 Unresolved reference 'getMemoryInfo'.
60:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:525:9 Unresolved reference 'outputCard'.
61:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:536:13 Unresolved reference 'outputCard'.
62:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:537:13 Unresolved reference 'answerText'.
63:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:539:13 Unresolved reference 'citationsContainer'.
64:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:540:13 Unresolved reference 'citationsContainer'.
65:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:542:13 Unresolved reference 'systemActionContainer'.
66:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:543:13 Unresolved reference 'systemActionContainer'.
67:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:545:19 Unresolved reference 'refineButton'.
68:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:545:47 Unresolved reference 'refineButton'.
69:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:548:9 Unresolved reference 'outputCard'.
70:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:549:9 Unresolved reference 'stopGeneratingButton'.
71:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:559:9 Unresolved reference 'outputCard'.
72:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:559:28 Unresolved reference 'stopGeneratingButton'.
73:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:1170:25 No parameter with name 'expectedBytes' found.
74:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:1171:23 Too many arguments for 'fun importModel(input: InputStream): File'.
75:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:1171:25 Cannot infer type for value parameter 'copied'. Specify it explicitly.
76:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:1171:33 Cannot infer type for value parameter 'total'. Specify it explicitly.
77:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:1172:70 Unresolved reference 'compareTo'.
78:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:1174:110 Argument type mismatch: actual type is 'Any', but 'Long' was expected.
79:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:1961:36 Unresolved reference 'estimatedTotalTokens'.
80:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:2181:74 Unresolved reference 'estimatedTotalTokens'.
81:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:2181:117 'operator' modifier is required on 'fun <T> Comparable<T>.compareTo(other: T): Int'.
82:e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:2219:37 Unresolved reference 'estimatedTotalTokens'.
```

## first failure context
```
e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:1171:25 Cannot infer type for value parameter 'copied'. Specify it explicitly.
e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:1171:33 Cannot infer type for value parameter 'total'. Specify it explicitly.
e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:1172:70 Unresolved reference 'compareTo'.
e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:1174:110 Argument type mismatch: actual type is 'Any', but 'Long' was expected.
e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:1961:36 Unresolved reference 'estimatedTotalTokens'.
e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:2181:74 Unresolved reference 'estimatedTotalTokens'.
e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:2181:117 'operator' modifier is required on 'fun <T> Comparable<T>.compareTo(other: T): Int'.
e: file:///home/runner/work/Pockagent/Pockagent/app/src/main/java/com/example/pocketagent/MainActivity.kt:2219:37 Unresolved reference 'estimatedTotalTokens'.

> Task :app:compileDebugKotlin FAILED
> Task :app:buildCMakeDebug[x86_64]
gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_2-1795598511272.json

[Incubating] Problems report is available at: file:///home/runner/work/Pockagent/Pockagent/build/reports/problems/problems-report.html

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:compileDebugKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.btapi.BuildToolsApiCompilationWork
   > Compilation error. See log for more details

* Try:
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

* Exception is:
org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':app:compileDebugKotlin'.
	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.lambda$executeIfValid$1(ExecuteActionsTaskExecuter.java:130)
	at org.gradle.internal.Try$Failure.ifSuccessfulOrElse(Try.java:293)
	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.executeIfValid(ExecuteActionsTaskExecuter.java:128)
	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.execute(ExecuteActionsTaskExecuter.java:116)
	at org.gradle.api.internal.tasks.execution.ProblemsTaskPathTrackingTaskExecuter.execute(ProblemsTaskPathTrackingTaskExecuter.java:41)
	at org.gradle.api.internal.tasks.execution.FinalizePropertiesTaskExecuter.execute(FinalizePropertiesTaskExecuter.java:46)
	at org.gradle.api.internal.tasks.execution.ResolveTaskExecutionModeExecuter.execute(ResolveTaskExecutionModeExecuter.java:51)
	at org.gradle.api.internal.tasks.execution.SkipTaskWithNoActionsExecuter.execute(SkipTaskWithNoActionsExecuter.java:57)
	at org.gradle.api.internal.tasks.execution.SkipOnlyIfTaskExecuter.execute(SkipOnlyIfTaskExecuter.java:74)
	at org.gradle.api.internal.tasks.execution.CatchExceptionTaskExecuter.execute(CatchExceptionTaskExecuter.java:36)
	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.executeTask(EventFiringTaskExecuter.java:77)
	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.call(EventFiringTaskExecuter.java:55)
	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.call(EventFiringTaskExecuter.java:52)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter.execute(EventFiringTaskExecuter.java:52)
	at org.gradle.execution.plan.LocalTaskNodeExecutor.execute(LocalTaskNodeExecutor.java:42)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$InvokeNodeExecutorsAction.execute(DefaultTaskExecutionGraph.java:331)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$InvokeNodeExecutorsAction.execute(DefaultTaskExecutionGraph.java:318)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.lambda$execute$0(DefaultTaskExecutionGraph.java:314)
	at org.gradle.internal.operations.CurrentBuildOperationRef.with(CurrentBuildOperationRef.java:85)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.execute(DefaultTaskExecutionGraph.java:314)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.execute(DefaultTaskExecutionGraph.java:303)
	at org.gradle.execution.plan.DefaultPlanExecutor$ExecutorWorker.execute(DefaultPlanExecutor.java:459)
	at org.gradle.execution.plan.DefaultPlanExecutor$ExecutorWorker.run(DefaultPlanExecutor.java:376)
	at org.gradle.internal.concurrent.ExecutorPolicy$CatchAndRecordFailures.onExecute(ExecutorPolicy.java:64)
	at org.gradle.internal.concurrent.AbstractManagedExecutor$1.run(AbstractManagedExecutor.java:48)
Caused by: org.gradle.workers.internal.DefaultWorkerExecutor$WorkExecutionException: A failure occurred while executing org.jetbrains.kotlin.compilerRunner.btapi.BuildToolsApiCompilationWork
	at org.gradle.workers.internal.DefaultWorkerExecutor$WorkItemExecution.waitForCompletion(DefaultWorkerExecutor.java:287)
	at org.gradle.internal.work.DefaultAsyncWorkTracker.lambda$waitForItemsAndGatherFailures$2(DefaultAsyncWorkTracker.java:130)
	at org.gradle.internal.Factories$1.create(Factories.java:31)
	at org.gradle.internal.work.DefaultWorkerLeaseService.withoutLocks(DefaultWorkerLeaseService.java:335)
	at org.gradle.internal.work.DefaultWorkerLeaseService.withoutLocks(DefaultWorkerLeaseService.java:318)
	at org.gradle.internal.work.DefaultWorkerLeaseService.withoutLock(DefaultWorkerLeaseService.java:323)
	at org.gradle.internal.work.DefaultAsyncWorkTracker.waitForItemsAndGatherFailures(DefaultAsyncWorkTracker.java:126)
	at org.gradle.internal.work.DefaultAsyncWorkTracker.waitForItemsAndGatherFailures(DefaultAsyncWorkTracker.java:92)
	at org.gradle.internal.work.DefaultAsyncWorkTracker.waitForAll(DefaultAsyncWorkTracker.java:78)
	at org.gradle.internal.work.DefaultAsyncWorkTracker.waitForCompletion(DefaultAsyncWorkTracker.java:66)
	at org.gradle.api.internal.tasks.execution.TaskExecution$3.run(TaskExecution.java:252)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$1.execute(DefaultBuildOperationRunner.java:30)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$1.execute(DefaultBuildOperationRunner.java:27)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.run(DefaultBuildOperationRunner.java:48)
	at org.gradle.api.internal.tasks.execution.TaskExecution.executeAction(TaskExecution.java:229)
	at org.gradle.api.internal.tasks.execution.TaskExecution.executeActions(TaskExecution.java:212)
	at org.gradle.api.internal.tasks.execution.TaskExecution.executeWithPreviousOutputFiles(TaskExecution.java:195)
	at org.gradle.api.internal.tasks.execution.TaskExecution.execute(TaskExecution.java:162)
	at org.gradle.internal.execution.steps.ExecuteStep.executeInternal(ExecuteStep.java:105)
	at org.gradle.internal.execution.steps.ExecuteStep.access$000(ExecuteStep.java:44)
	at org.gradle.internal.execution.steps.ExecuteStep$1.call(ExecuteStep.java:59)
	at org.gradle.internal.execution.steps.ExecuteStep$1.call(ExecuteStep.java:56)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
	at org.gradle.internal.execution.steps.ExecuteStep.execute(ExecuteStep.java:56)
	at org.gradle.internal.execution.steps.ExecuteStep.execute(ExecuteStep.java:44)
	at org.gradle.internal.execution.steps.CancelExecutionStep.execute(CancelExecutionStep.java:42)
	at org.gradle.internal.execution.steps.TimeoutStep.executeWithoutTimeout(TimeoutStep.java:75)
	at org.gradle.internal.execution.steps.TimeoutStep.execute(TimeoutStep.java:55)
	at org.gradle.internal.execution.steps.PreCreateOutputParentsStep.execute(PreCreateOutputParentsStep.java:50)
--
	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.call(EventFiringTaskExecuter.java:52)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter.execute(EventFiringTaskExecuter.java:52)
	at org.gradle.execution.plan.LocalTaskNodeExecutor.execute(LocalTaskNodeExecutor.java:42)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$InvokeNodeExecutorsAction.execute(DefaultTaskExecutionGraph.java:331)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$InvokeNodeExecutorsAction.execute(DefaultTaskExecutionGraph.java:318)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.lambda$execute$0(DefaultTaskExecutionGraph.java:314)
	at org.gradle.internal.operations.CurrentBuildOperationRef.with(CurrentBuildOperationRef.java:85)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.execute(DefaultTaskExecutionGraph.java:314)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.execute(DefaultTaskExecutionGraph.java:303)
	at org.gradle.execution.plan.DefaultPlanExecutor$ExecutorWorker.execute(DefaultPlanExecutor.java:459)
	at org.gradle.execution.plan.DefaultPlanExecutor$ExecutorWorker.run(DefaultPlanExecutor.java:376)
```
