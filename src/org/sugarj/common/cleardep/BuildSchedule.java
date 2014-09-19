package org.sugarj.common.cleardep;

import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import org.sugarj.common.cleardep.CompilationUnit.ModuleVisitor;
import org.sugarj.common.cleardep.mode.Mode;
import org.sugarj.common.path.RelativePath;
import org.sugarj.util.Pair;

public class BuildSchedule {

  private Set<Task> leafTasks;
  private Map<CompilationUnit, Integer> unitInterfacesBeforeCompilation;

  public static enum TaskState {
    OPEN, IN_PROGESS, SUCCESS, FAILURE;
  }

  static int maxID = 0;

  public class Task {

    Set<CompilationUnit> unitsToCompile;
    Set<Task> requiredTasks;
    Set<Task> dependingTasks;
    private TaskState state;
    private int id;

    public Task() {
      this.unitsToCompile = new HashSet<>();
      this.requiredTasks = new HashSet<>();
      this.dependingTasks = new HashSet<>();
      this.state = TaskState.OPEN;
      id = maxID;
      maxID++;
    }

    public Task(CompilationUnit unit) {
      this();
      this.unitsToCompile.add(unit);
    }

    void merge(Task other) {
      if (other == this)
        return;
      this.unitsToCompile.addAll(other.unitsToCompile);
      this.dependingTasks.remove(other);
      this.requiredTasks.remove(other);
      for (Task t : other.requiredTasks) {
        t.dependingTasks.remove(other);
        if (t != this) {
          t.dependingTasks.add(this);
          this.requiredTasks.add(t);
        }
      }
      for (Task t : other.dependingTasks) {
        t.requiredTasks.remove(other);
        if (t != this) {
          t.requiredTasks.add(this);
          this.dependingTasks.add(t);
        }
      }
      other.requiredTasks.clear();
      other.dependingTasks.clear();
      other.unitsToCompile.clear();
    }

    public boolean containsUnits(CompilationUnit unit) {
      return this.unitsToCompile.contains(unit);
    }

    public boolean needsToBeBuild(Map<RelativePath, Integer> editedSourceFiles, Mode mode) {
      // Check the states of the depending tasks
      for (Task t : this.requiredTasks) {
        switch (t.getState()) {
        case OPEN:
        case IN_PROGESS:
          throw new IllegalBuildStateException("Can only determine whether a task has to be build with depending tasks are build");
        case FAILURE:
          return false; // Do not need to build if depending builds
          // failed
        case SUCCESS:
        }
      }
      // The task needs to be build iff one unit is not shallow consistent
      // or
      // not consistent or not consistent to interfaces
      for (CompilationUnit u : this.unitsToCompile) {
        if (!u.isConsistentShallow(editedSourceFiles, mode)) {
          return true;
        }
        if (!u.isConsistent(editedSourceFiles, mode) || !isConsistentToInterfaces(u)) {
          return true;
        }
      }

      return false;

    }

    public boolean isCompleted() {
      return this.state == TaskState.SUCCESS || this.state == TaskState.FAILURE;
    }

    public TaskState getState() {
      return this.state;
    }

    public void setState(TaskState state) {
      this.state = state;
    }

    public void addUnit(CompilationUnit unit) {
      this.unitsToCompile.add(unit);
    }

    public void addRequiredTask(Task task) {
      this.requiredTasks.add(task);
      task.dependingTasks.add(this);
    }

    public boolean dependsOn(Task task) {
      return this.requiredTasks.contains(task);
    }

    public boolean hasNoRequiredTasks() {
      return this.requiredTasks.isEmpty();
    }

    @Override
    public String toString() {
      String reqs = "";
      for (Task dep : this.requiredTasks) {
        reqs += dep.id + ",";
      }
      String s = "Task_" + id + "_(" + reqs + "," + this.dependingTasks.size() + ")[";
      for (CompilationUnit u : this.unitsToCompile)
        for (RelativePath p : u.getSourceArtifacts())
          s += p.getRelativePath() + ", ";
      s += "]";
      return s;
    }

  }

  public BuildSchedule() {
    this.leafTasks = new HashSet<>();
    this.unitInterfacesBeforeCompilation = new HashMap<>();
  }

  public void addLeafTask(Task task) {
    assert task.hasNoRequiredTasks() : "Given task is not a leaf";
    this.leafTasks.add(task);
  }

  public boolean isConsistentToInterfaces(CompilationUnit unit) {
    return unit.visit(new ModuleVisitor<Boolean>() {

      @Override
      public Boolean visit(CompilationUnit mod, Mode mode) {
        return mod.getInterfaceHash() == unitInterfacesBeforeCompilation.get(mod);
      }

      @Override
      public Boolean combine(Boolean t1, Boolean t2) {
        return t1 && t2;
      }

      @Override
      public Boolean init() {
        return true;
      }

      @Override
      public boolean cancel(Boolean t) {
        return !t;
      }
    });
  }

  /**
   * Flattens this BuildSchedule. This means to transform the acyclic dependency
   * graph of the build tasks to a list of build tasks, such that the tasks can
   * be processed in linear order and all dependencies are satisfied. This means
   * that one can loop on the tasks in the returned list and compile them
   * relying on that all non cyclic dependencies are already compiled and all
   * cyclic dependencies are included in the current task.
   * 
   * @return a list containing all tasks of this schedule satisfying the
   *         condition described.
   */
  public List<Task> flatten() {
    // A queue for the tasks to process
    Deque<Task> taskStack = new LinkedList<>();
    // We start with all leaf tasks
    taskStack.addAll(this.leafTasks);

    // The result list, which contains the tasks to build in a linear order
    List<Task> flattenedTasks = new LinkedList<>();

    // A set containing all tasks which has been seen to detect duplicates
    // (different paths in the graph from a to b)
    Set<Task> processedTasks = new HashSet<>();

    while (!taskStack.isEmpty()) {

      // Get the first stack without removing it
      Task t = taskStack.peek();

      // Remove it, if it has already been processed
      // Here we cannot prohibit duplicates in the stack because the order is
      // important
      if (processedTasks.contains(t)) {
        taskStack.pop();
        continue;
      }

      // Check whether all dependencies of the tasks has already been flattened,
      boolean depsOK = true;
      for (Task dep : t.requiredTasks) {
        if (!processedTasks.contains(dep)) {
          depsOK = false;
          // Put dep on the top of the stack -> dep will be added to the flat
          // list
          // before t is processed again. Because there are no cycles, t cannot
          // be added
          // again on the stack before dep
          taskStack.push(dep);
          break;
        }
      }

      // If all dependencies are added to list, this can be added safely
      if (depsOK) {
        // Add to list and remove t from the stack and mark it as processed
        flattenedTasks.add(t);
        taskStack.pop();
        processedTasks.add(t);
        // And all tasks which depends on t to the stack because this are the
        // next candidates to continue with
        // But do not push them on the top of the stack but add them to the
        // bottom
        for (Task next : t.dependingTasks) {
          if (!processedTasks.contains(next)) {
            taskStack.add(next);
          }
        }
      }
    }

    // Validate the schedule
    validateFlattenSchedule(flattenedTasks);
    return flattenedTasks;
  }

  void registerUnitInterface(CompilationUnit unit) {
    this.unitInterfacesBeforeCompilation.put(unit, unit.getInterfaceHash());
  }

  public static enum ScheduleMode {
    REBUILD_ALL, REBUILD_INCONSISTENT, REBUILD_INCONSISTENT_INTERFACE;
  }

  private static void validateFlattenSchedule(List<Task> flatSchedule) {
    Set<CompilationUnit> collectedUnits = new HashSet<>();
    for (int i = 0; i < flatSchedule.size(); i++) {
      Task currentTask = flatSchedule.get(i);
      // Find duplicates
      for (CompilationUnit unit : currentTask.unitsToCompile) {
        if (collectedUnits.contains(unit)) {
          throw new AssertionError("Task contained twice: " + unit);
        }
      }
      collectedUnits.addAll(currentTask.unitsToCompile);

      for (CompilationUnit unit : currentTask.unitsToCompile) {
        BuildScheduleBuilder.validateDeps("Flattened Schedule", unit, collectedUnits);

      }
    }
  }

  @Override
  public String toString() {
    String str = "BuildSchedule: " + this.leafTasks.size() + " root tasks\n";
    return str;

  }
}
