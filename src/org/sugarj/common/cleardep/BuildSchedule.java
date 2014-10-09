package org.sugarj.common.cleardep;

import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sugarj.common.cleardep.mode.Mode;
import org.sugarj.common.path.RelativePath;

public class BuildSchedule {

  private Set<Task> leafTasks;

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
      assert other.validateEdges();
      this.unitsToCompile.addAll(other.unitsToCompile);
      this.dependingTasks.remove(other);
      this.requiredTasks.remove(other);

      for (Task t : other.requiredTasks) {
        if (t != this) {
          boolean success = t.dependingTasks.remove(other);
          assert success;
          t.dependingTasks.add(this);
          this.requiredTasks.add(t);
        }
      }
      for (Task t : other.dependingTasks) {
        if (t != this) {
          boolean success = t.requiredTasks.remove(other);
          assert success;
          t.requiredTasks.add(this);
          this.dependingTasks.add(t);
        }
      }
      other.requiredTasks.clear();
      other.dependingTasks.clear();
      other.unitsToCompile.clear();
      assert !this.requiredTasks.contains(this) : "Task requires itself";
      assert !this.dependingTasks.contains(this) : "Task depends on itself";
      assert this.validateEdges();
    }

    private boolean validateEdges() {
      for (Task t : this.requiredTasks) {
        if (!t.dependingTasks.contains(this)) {
          return false;
        }

      }
      for (Task t : this.dependingTasks) {
        if (!t.requiredTasks.contains(this)) {
          return false;
        }
      }
      return true;
    }

    public boolean containsUnits(CompilationUnit unit) {
      return this.unitsToCompile.contains(unit);
    }

    public boolean needsToBeBuild(Map<RelativePath, Integer> editedSourceFiles, Mode mode) {
      // Check the states of the depending tasks
      for (Task t : this.requiredTasks) {
        switch (t.getState()) {
        case OPEN:
          break;
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
        if (!u.isConsistentToDependencyInterfaces()) {
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

    public Set<CompilationUnit> getUnitsToCompile() {
      return unitsToCompile;
    }

    public void setState(TaskState state) {
      this.state = state;
    }

    public void addUnit(CompilationUnit unit) {
      this.unitsToCompile.add(unit);
    }

    public void addRequiredTask(Task task) {
      if (task == this) {
        throw new IllegalArgumentException("Cannot require itself");
      }
      this.requiredTasks.add(task);
      task.dependingTasks.add(this);
      assert this.validateEdges();
    }

    public boolean requires(Task task) {
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
      String deps = "";
      for (Task dep : this.dependingTasks) {
        deps += dep.id + ",";
      }
      String s = "Task_" + id + "_(" + reqs + "|" + deps + ")[";
      for (CompilationUnit u : this.unitsToCompile)
        for (RelativePath p : u.getSourceArtifacts())
          s += p.getRelativePath() + ", ";
      s += "]";
      return s;
    }

    @Override
    public int hashCode() {
      return this.id;
    }

  }

  public BuildSchedule() {
    this.leafTasks = new HashSet<>();
  }

  public void addLeafTask(Task task) {
    assert task.hasNoRequiredTasks() : "Given task is not a leaf";
    this.leafTasks.add(task);
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
    assert validateFlattenSchedule(flattenedTasks);
    return flattenedTasks;
  }

  public static enum ScheduleMode {
    REBUILD_ALL, REBUILD_INCONSISTENT;
  }

  private static boolean validateFlattenSchedule(List<Task> flatSchedule) {
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
        // BuildScheduleBuilder.validateDeps("Flattened Schedule", unit,
        // collectedUnits);

      }
    }
    return true;
  }

  @Override
  public String toString() {
    String str = "BuildSchedule: " + this.leafTasks.size() + " root tasks\n";
    return str;

  }
}
