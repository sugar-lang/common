package org.sugarj.common.cleardep;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.sugarj.common.cleardep.BuildSchedule.ScheduleMode;
import org.sugarj.common.cleardep.BuildSchedule.Task;
import org.sugarj.common.cleardep.mode.Mode;
import org.sugarj.common.path.RelativePath;

public class BuildScheduleBuilder {
  
  private Set<CompilationUnit> inconsistentUnits;
  private ScheduleMode scheduleMode;
  
  public BuildScheduleBuilder(ScheduleMode mode) {
    this.scheduleMode = mode;
  }

  /**
   * Creates a BuildSchedule for the units in unitsToCompile. That means that
   * the BuildSchedule is sufficient to build all dependencies of the given
   * units and the units itself.
   * 
   * The scheduleMode specifies which modules are included in the BuildSchedule.
   * For REBUILD_ALL, all dependencies are included in the schedule, whether
   * they are inconsistent or net. For REBUILD_INCONSISTENT, only dependencies
   * are included in the build schedule, if they are inconsistent. For
   * REBUILD_INCONSISTENT_INTERFACE, the same tasks are included in the schedule
   * but information for the interfaces of the modules before building is stored
   * and may be used to determine modules which does not have to be build later.
   * 
   * @param unitsToCompile
   *          a set of units which has to be compiled
   * @param editedSourceFiles
   * @param mode
   * @param scheduleMode
   *          the mode of the schedule as described
   * @return the created BuildSchedule
   */
  public BuildSchedule createBuildSchedule(Set<CompilationUnit> unitsToCompile, Map<RelativePath, Integer> editedSourceFiles, Mode mode) {
    BuildSchedule schedule = new BuildSchedule();
    
    // Find all inconsistent modules
    this.inconsistentUnits = new HashSet<>();
    for (CompilationUnit unit : unitsToCompile) {
      this.inconsistentUnits.addAll(CompilationUnitUtils.findInconsistentUnits(unit, mode));
    }
  
    // A Map which maps compilation units to the tasks where they are in
    Map<CompilationUnit, Task> tasksForUnits = new HashMap<>();
  
    // A Set which contains all tasks which are in the graph -> easier to find
    // tasks
    Set<Task> allTasks = new HashSet<>();
  
    // Queue containing CompilationUnits which have to be processed
    Queue<CompilationUnit> dependencyQueue = new LinkedList<>();
    // The Set of CompilationUnit which are already processed -> avoid cycles
    Set<CompilationUnit> processedUnits = new HashSet<>();
  
    // Initialize the queue: put all units which has to be compiled in the queue
    for (CompilationUnit u : unitsToCompile) {
      if (this.needsToBeBuild(u)) {
        // Put the units in the queue
        dependencyQueue.add(u);
        // Create a task and register it
        Task newTask = schedule.new Task(u);
        tasksForUnits.put(u, newTask);
        allTasks.add(newTask);
      }
    }
  
    // New process all compilation units in the queue
    while (!dependencyQueue.isEmpty()) {
      CompilationUnit unit = dependencyQueue.poll();
      assert !processedUnits.contains(unit) : "Unit " + unit + " is already processed";
  
      // Get task for unit, task has to exists, because task is created before
      Task task = tasksForUnits.get(unit);
      assert task != null : "Invalid Compilation Unit without registered Task in the queue";
      assert task.containsUnits(unit) : "Task for Unit does not contain the unit";
  
      // Mark the unit as processed right now (a unit may reference itself ...)
      processedUnits.add(unit);
  
      // Store the interface of the unit only if interface has to be handled
      if (scheduleMode == BuildSchedule.ScheduleMode.REBUILD_INCONSISTENT_INTERFACE) {
        schedule.registerUnitInterface(unit);
      }
  
      // Module dependencies and circular dependencies are processed the same
      // way, because non circular dependency
      // also may close a circle in the task graph
      // This is slow rehashing everything without a real need
      Set<CompilationUnit> allDeps = new HashSet<>();
      allDeps.addAll(unit.getModuleDependencies());
      allDeps.addAll(unit.getCircularModuleDependencies());
  
      for (CompilationUnit dep : allDeps) {
  
        // Get the task for the unit (may be null)
        Task depTask = tasksForUnits.get(dep);
  
        // Then we need to do cycle detection, when the new dependency closes a
        // cycle in the build graph
        // the tasks needs to be merged (and maybe depending tasks also)
        // This is done by checkForCycleAndMerge, returns true, if a cycle was
        // detected
        boolean cyclicDep = BuildScheduleBuilder.checkForCycleAndMerge(task, depTask, dep, tasksForUnits, allTasks);
  
        // Handle the non cyclic stuff
        if (!cyclicDep) {
          if (depTask != null) {
            // The task has already been created, require this task to be build
            // before
            assert !depTask.dependsOn(task) : "Error in cycle detection, new task dependency closes cycle";
            assert depTask.containsUnits(dep) : "Task for depedency does not contains the unit";
            task.addRequiredTask(depTask);
          } else {
            // There is no task, we need to create a new one
            if (this.needsToBeBuild(dep)) {
              Task newTask = schedule.new Task(dep);
              allTasks.add(newTask);
              tasksForUnits.put(dep, newTask);
              task.addRequiredTask(newTask);
            }
          }
        }
  
        // Finally put the dependency in the queue if it has not been processed
        // already
        if (!processedUnits.contains(dep) && !dependencyQueue.contains(dep)) {
          dependencyQueue.add(dep);
        }
      }
  
    }
  
    // Find all leaf tasks in all tasks (tasks which does not require other
    // tasks)
    for (Task possibleRoot : allTasks) {
      if (possibleRoot.hasNoRequiredTasks()) {
        schedule.addLeafTask(possibleRoot);
      }
    }
  
    // At the end, we validate the graph we build
    BuildScheduleBuilder.validateBuildSchedule(allTasks);
  
    return schedule;
  }

  /**
   * This function checks whether adding a dependency from task to depTask
   * creates a cycle in the task graph. If there would be a cycle, the cycle is
   * avoided by merging tasks together. Task and depTasks are merged, but
   * probably more tasks transitively.
   * 
   * If depTask == null this method checks whether creating a new task for dep
   * would create a cycle. If yes, dep is inserted into task. Otherwise nothing
   * is done.
   * 
   * If the method returns true a cycle was detected, corrected and the
   * dependency is inserted. If it returns false, the dependency from task to
   * depTask is not created.
   * 
   * @param task
   *          the task which needs a dependency on dep which is in depTask, if
   *          depTask != null
   * @param depTask
   *          if not null, depTask is the task of dep, if null, no task for dep
   *          has been created
   * @param dep
   *          the unit on which task needs to depend
   * @param tasksForUnits
   *          the map from tasks to units. The map is modified if the function
   *          modifies the tasks to keep a consistent state
   * @param allTasks
   *          a set of all created task, this function may remove tasks when
   *          tasks are merged
   * @return true if a cycle was found and corrected, otherwise false
   */
  static boolean checkForCycleAndMerge(Task task, Task depTask, CompilationUnit dep, Map<CompilationUnit, Task> tasksForUnits, Set<Task> allTasks) {
    // Easy case: task == depTask
    // is of course recursive
    if (depTask == task) {
      if (dep != null) {
        task.addUnit(dep);
      }
      return true;
    }
  
    // First, we have to check whether there is a cyclic dependency between task
    // and depTask
    // We know, that task depends on depTask, so we need to check the other way
    // around
    // depTask depends on task if any unit in depTask depends on any unit in
    // task
    boolean cyclicDep = false;
  
    // We have to check in depTask and for dep, because dep may not be in
    // depTask
    if (depTask != null) {
      unitloop: for (CompilationUnit c : task.unitsToCompile) {
        for (CompilationUnit c2 : depTask.unitsToCompile) {
          if (c2.dependsOnTransitively(c)) {
            cyclicDep = true;
            break unitloop;
          }
        }
      }
    }
  
    if (dep != null) {
      for (CompilationUnit c : task.unitsToCompile) {
        if (dep != null && dep.dependsOnTransitively(c)) {
          cyclicDep = true;
          break;
        }
      }
    }
  
    // If there is no cycle, we do not need to do more
    if (!cyclicDep) {
      return false;
    }
  
    // Otherwise we need to merge, this is a bit tricky
    if (depTask == null) {
      // We start with the easy case: depTask does not have a task
      // thus we can insert it in the current and are done
      tasksForUnits.put(dep, task);
      task.addUnit(dep);
    } else {
      // there is a task for dep: we merge depTask into task
      // First, we need to merge both tasks, this merges the units to compile,
      // the dependent and required modules
  
      // But before, we need to assign the new task for the units in depTask
      for (CompilationUnit c : depTask.unitsToCompile) {
        tasksForUnits.put(c, task);
      }
  
      // Now merge and remove the old task we do not need anymore
      task.merge(depTask);
      allTasks.remove(depTask);
  
      // And at least we need to search for cycles transitively because merging
      // the required and dependent modules may create other circles
      // So search and merge recursively
  
      // We need to copy the list of tasks because they may be modified by
      // merging
      // I use arraylists because they are cheaper and the set does not contain
      // duplicates
      for (Task old : new ArrayList<>(task.requiredTasks)) {
        assert old != task : "There is a wrong cycle in the graph";
        for (Task t : new ArrayList<>(old.requiredTasks)) {
          checkForCycleAndMerge(old, t, null, tasksForUnits, allTasks);
        }
      }
  
      for (Task old : new HashSet<>(task.dependingTasks)) {
        assert old != task : "There is a wrong cycle in the graph";
        for (Task t : new HashSet<>(old.dependingTasks)) {
          checkForCycleAndMerge(t, old, null, tasksForUnits, allTasks);
        }
      }
    }
    return true;
  
  }

  static void validateBuildSchedule(Set<Task> allTasks) {
    for (Task task : allTasks) {
      Set<CompilationUnit> reachableUnits = new HashSet<>();
      Deque<Task> taskStack = new LinkedList<>();
      Set<Task> seenTasks = new HashSet<>();
      taskStack.addAll(task.requiredTasks);
      reachableUnits.addAll(task.unitsToCompile);
      Map<Task, Task> preds = new HashMap<>();
      for (Task r : task.requiredTasks) {
        preds.put(r, task);
      }
      while (!taskStack.isEmpty()) {
        Task t = taskStack.pop();
        if (t == task) {
          Task tmp = preds.get(t);
          List<Task> path = new LinkedList<>();
          path.add(t);
          int max = 0;
          while (max < 10 && tmp != task) {
            path.add(tmp);
            tmp = preds.get(tmp);
            max++;
          }
          if (max == 10) {
            throw new AssertionError("Graph contains a cycle with wrong " + path);
          }
          path.add(task);
          throw new AssertionError("Graph contains a cycle with " + path);
        }
        seenTasks.add(t);
        reachableUnits.addAll(t.unitsToCompile);
        for (Task r : t.requiredTasks)
          if (!seenTasks.contains(r)) {
            taskStack.push(r);
            preds.put(r, t);
          }
      }
      for (CompilationUnit unit : task.unitsToCompile) {
        BuildScheduleBuilder.validateDeps("BuildSchedule", unit, reachableUnits);
      }
    }
  }

  static void validateDeps(String prefix, CompilationUnit unit, Set<CompilationUnit> allDeps) {
    for (CompilationUnit dep : unit.getModuleDependencies()) {
      if (!allDeps.contains(dep)) {
        throw new AssertionError(prefix + ": Schedule violates dependency: " + unit + " on " + dep);
      }
    }
    for (CompilationUnit dep : unit.getCircularModuleDependencies()) {
      if (!allDeps.contains(dep)) {
        throw new AssertionError(prefix + ": Schedule violates circular dependency: " + unit + " on " + dep);
      }
    }
  }

  boolean needsToBeBuild(CompilationUnit unit) {
    return scheduleMode == BuildSchedule.ScheduleMode.REBUILD_ALL || !this.inconsistentUnits.contains(unit);
  }

}
