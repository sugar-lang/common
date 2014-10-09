package org.sugarj.common.cleardep;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

import org.sugarj.common.AppendingIterable;
import org.sugarj.common.FileCommands;
import org.sugarj.common.cleardep.mode.DoCompileMode;
import org.sugarj.common.cleardep.mode.Mode;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;
import org.sugarj.util.Pair;

/**
 * Dependency management for modules.
 * 
 * @author Sebastian Erdweg
 */
abstract public class CompilationUnit extends PersistableEntity {

  public static final long serialVersionUID = -5713504273621720673L;

  public CompilationUnit() { /* for deserialization only */
  }

  // Exactly one of `compiledCompilationUnit` and `editedCompilationUnit` is not
  // null.
  protected CompilationUnit compiledCompilationUnit;
  protected CompilationUnit editedCompilationUnit;

  protected Synthesizer syn;

  protected Integer interfaceHash;
  protected Path targetDir;
  // Need to declare as HashMap, because HashMap allows null values, general Map
  // does not guarantee an keys with null value would be lost
  // But HashMap is incompatible with unmodified maps which are stored in persisted units
  // So this is not safe
  protected Map<RelativePath, Integer> sourceArtifacts;
  protected Map<CompilationUnit, Integer> moduleDependencies;
  protected Map<CompilationUnit, Integer> circularModuleDependencies;
  protected Map<Path, Integer> externalFileDependencies;
  protected Map<Path, Integer> generatedFiles;

  // **************************
  // Methods for initialization
  // **************************

  @SuppressWarnings("unchecked")
  final protected static <E extends CompilationUnit> E create(Class<E> cl, Stamper stamper, Path compileDep, Path compileTarget, Path editedDep, Path editedTarget, Set<RelativePath> sourceFiles, Map<RelativePath, Integer> editedSourceFiles, Mode mode, Synthesizer syn) throws IOException {
    E compileE;
    try {
      compileE = PersistableEntity.read(cl, stamper, compileDep);
    } catch (IOException e) {
      e.printStackTrace();
      compileE = null;
    }
    if (compileE == null)
      compileE = PersistableEntity.create(cl, stamper, compileDep);
    compileE.targetDir = compileTarget;

    E editedE;
    if (compileE.editedCompilationUnit != null)
      editedE = (E) compileE.editedCompilationUnit;
    else {
      editedE = PersistableEntity.create(cl, stamper, editedDep);
      compileE.editedCompilationUnit = editedE;
    }

    if (editedE.compiledCompilationUnit == null)
      editedE.compiledCompilationUnit = compileE;
    editedE.targetDir = editedTarget;

    E e = DoCompileMode.isDoCompile(mode) ? compileE : editedE;
    e.init();
    e.syn = syn;
    if (syn != null)
      syn.markSynthesized(e);

    for (RelativePath sourceFile : sourceFiles) {
      Integer editedStamp = editedSourceFiles == null ? null : editedSourceFiles.get(sourceFile);
      if (editedStamp != null)
        e.addSourceArtifact(sourceFile, editedStamp);
      else
        e.addSourceArtifact(sourceFile);
    }

    return e;
  }

  @SuppressWarnings("unchecked")
  final protected static <E extends CompilationUnit> Pair<E, Boolean> read(Class<E> cl, Stamper stamper, Path compileDep, Path editedDep, Map<RelativePath, Integer> editedSourceFiles, Mode mode) throws IOException {
    E compileE = PersistableEntity.read(cl, stamper, compileDep);
    if (compileE != null && compileE.isConsistent(editedSourceFiles, mode))
      // valid compile is good for compilation and parsing
      return Pair.create(compileE, true);

    E editedE;
    if (compileE != null && compileE.editedCompilationUnit != null)
      editedE = (E) compileE.editedCompilationUnit;
    else
      editedE = PersistableEntity.read(cl, stamper, editedDep);

    // valid edit is good for compilation after lifting
    if (DoCompileMode.isDoCompile(mode) && editedE != null && editedE.isConsistent(editedSourceFiles, mode)) {
      editedE.liftEditedToCompiled();
      return Pair.create((E) editedE.compiledCompilationUnit, true);
    }

    // valid edit is good for parsing
    if (!DoCompileMode.isDoCompile(mode) && editedE != null && editedE.isConsistent(editedSourceFiles, mode))
      return Pair.create(editedE, true);

    return Pair.create(DoCompileMode.isDoCompile(mode) ? compileE : editedE, false);
  }

  protected void copyContentTo(CompilationUnit compiled) {
    compiled.sourceArtifacts.putAll(sourceArtifacts);

    for (Entry<CompilationUnit, Integer> entry : moduleDependencies.entrySet()) {
      CompilationUnit dep = entry.getKey();
      if (dep.compiledCompilationUnit == null)
        compiled.moduleDependencies.put(dep, entry.getValue());
      else
        compiled.addModuleDependency(dep.compiledCompilationUnit);
    }

    for (Entry<CompilationUnit, Integer> entry : circularModuleDependencies.entrySet()) {
      CompilationUnit dep = entry.getKey();
      if (dep.compiledCompilationUnit == null)
        compiled.circularModuleDependencies.put(dep, entry.getValue());
      else
        compiled.addCircularModuleDependency(dep.compiledCompilationUnit);
    }

    for (Path p : externalFileDependencies.keySet())
      compiled.addExternalFileDependency(FileCommands.tryCopyFile(targetDir, compiled.targetDir, p));

    for (Path p : generatedFiles.keySet())
      compiled.addGeneratedFile(FileCommands.tryCopyFile(targetDir, compiled.targetDir, p));
  }

  protected void liftEditedToCompiled() throws IOException {
    ModuleVisitor<Void> liftVisitor = new ModuleVisitor<Void>() {
      @Override
      public Void visit(CompilationUnit mod, Mode mode) {
        if (mod.compiledCompilationUnit == null)
          return null;
        // throw new IllegalStateException("compiledCompilationUnit of " + mod +
        // " must not be null");

        CompilationUnit edited = mod;
        CompilationUnit compiled = mod.compiledCompilationUnit;
        compiled.init();
        edited.copyContentTo(compiled);

        try {
          compiled.write();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }

        return null;
      }

      @Override
      public Void combine(Void t1, Void t2) {
        return null;
      }

      @Override
      public Void init() {
        return null;
      }

      @Override
      public boolean cancel(Void t) {
        return false;
      }
    };

    if (editedCompilationUnit != null)
      editedCompilationUnit.visit(liftVisitor);
    else
      this.visit(liftVisitor);
  }

  @Override
  protected void init() {
    sourceArtifacts = new HashMap<>();
    moduleDependencies = new HashMap<>();
    circularModuleDependencies = new HashMap<>();
    externalFileDependencies = new HashMap<>();
    generatedFiles = new HashMap<>();
    this.interfaceHash = null;
  }

  // *******************************
  // Methods for adding dependencies
  // *******************************

  protected void addSourceArtifact(RelativePath file) {
    addSourceArtifact(file, stamper.stampOf(file));
  }

  protected void addSourceArtifact(RelativePath file, int stampOfFile) {
    sourceArtifacts.put(file, stampOfFile);
  }

  public void addExternalFileDependency(Path file) {
    addExternalFileDependency(file, stamper.stampOf(file));
  }

  public void addExternalFileDependency(Path file, int stampOfFile) {
    externalFileDependencies.put(file, stampOfFile);
  }

  public void addExternalFileDependencyLate(Path file) {
    addExternalFileDependencyLate(file, stamper.stampOf(file));
  }

  public void addExternalFileDependencyLate(Path file, int stampOfFile) {
    try {
      externalFileDependencies.put(file, stampOfFile);
    } catch (UnsupportedOperationException e) {
      externalFileDependencies = new HashMap<>(externalFileDependencies);
      externalFileDependencies.put(file, stampOfFile);
    }
  }

  public void addGeneratedFile(Path file) {
    addGeneratedFile(file, stamper.stampOf(file));
  }

  public void addGeneratedFile(Path file, int stampOfFile) {
    generatedFiles.put(file, stampOfFile);
  }

  /**
   * @deprecated use {{@link #addModuleDependency(CompilationUnit)} instead
   */
  @Deprecated
  public void addCircularModuleDependency(CompilationUnit mod) {
    if (!mod.dependsOnTransitivelyNoncircularly(this)) {
      throw new AssertionError("Circular depedency from " + this + " to " + mod + " does not close a circle");
    }
    circularModuleDependencies.put(mod, mod.getInterfaceHash());
  }

  public void addModuleDependency(CompilationUnit mod) {
    if (mod == this) {
      return;
    }
    if (mod.dependsOnTransitivelyNoncircularly(this)) {
      this.circularModuleDependencies.put(mod, mod.getInterfaceHash());
    } else {
      this.moduleDependencies.put(mod, mod.getInterfaceHash());
    }
  }
  
  /**
   * Removes a module dependency. Note: THIS METHOD IS NOT SAFE AND LEADS TO INCONSISTENT STATES.
   * Therefore it is protected. The Problem is that removing a module dependency may change the
   * spanning tree at another non local point.
   * It is necessary to repair the dependency tree after removing a dependency
   * This is not done after removing a module dependency because it requires to rebuild all dependencies
   * which can be done after multiple removals once.
   * @param mod
   * @see BuildScheduleBuilder#repairGraph(Set)
   */
  protected void removeModuleDependency(CompilationUnit mod) {
    // Just remove from both maps because mod is exactly in one
    // But queriing is not cheaper
    this.moduleDependencies.remove(mod);
    this.circularModuleDependencies.remove(mod);
  }

  public void updateModuleDependencyInterface(CompilationUnit mod) {
    if (mod == null) {
      throw new NullPointerException("Cannot handle null unit");
    }
    if (this.moduleDependencies.containsKey(mod)) {
      this.moduleDependencies.put(mod, mod.getInterfaceHash());
    } else if (this.circularModuleDependencies.containsKey(mod)) {
      this.circularModuleDependencies.put(mod, mod.getInterfaceHash());
    } else {
      throw new IllegalArgumentException("Given CompilationUnit " + mod + " is not a dependency of this module");
    }
  }

  public void moveCircularModulDepToNonCircular(CompilationUnit mod) {
    if (mod == null) {
      throw new NullPointerException("Cannot handle null unit");
    }
    if (!this.circularModuleDependencies.containsKey(mod)) {
      throw new IllegalArgumentException("Given CompilationUnit is not a circular Dependency");
    }
    Integer value = this.circularModuleDependencies.get(mod);
    this.circularModuleDependencies.remove(mod);
    this.moduleDependencies.put(mod, value);
  }

  public void moveModuleDepToCircular(CompilationUnit mod) {
    if (mod == null) {
      throw new NullPointerException("Cannot handle null unit");
    }
    if (!this.moduleDependencies.containsKey(mod)) {
      throw new IllegalArgumentException("Given CompilationUnit is not a non circular dependency");
    }
    Integer value = this.moduleDependencies.get(mod);
    this.moduleDependencies.remove(mod);
    this.circularModuleDependencies.put(mod, value);
  }

  // *********************************
  // Methods for querying dependencies
  // *********************************

  public boolean isParsedCompilationUnit() {
    return compiledCompilationUnit != null;
  }

  public boolean dependsOnNoncircularly(CompilationUnit other) {
    return getModuleDependencies().contains(other);
  }

  public boolean dependsOnTransitivelyNoncircularly(CompilationUnit other) {
    if (dependsOnNoncircularly(other))
      return true;
    for (CompilationUnit mod : getModuleDependencies())
      if (mod.dependsOnTransitivelyNoncircularly(other))
        return true;
    return false;
  }

  public boolean dependsOn(CompilationUnit other) {
    return getModuleDependencies().contains(other) || getCircularModuleDependencies().contains(other);
  }

  public boolean dependsOnTransitively(CompilationUnit other) {
    if (dependsOn(other))
      return true;
    for (CompilationUnit mod : getModuleDependencies())
      if (mod.dependsOnTransitively(other))
        return true;
    return false;
  }

  public Set<RelativePath> getSourceArtifacts() {
    return sourceArtifacts.keySet();
  }

  public Set<CompilationUnit> getModuleDependencies() {
    return moduleDependencies.keySet();
  }

  public Set<CompilationUnit> getCircularModuleDependencies() {
    return circularModuleDependencies.keySet();
  }
  
  public Iterable<CompilationUnit> getCircularAndNonCircularModuleDependencies() {
    return new AppendingIterable<>(Arrays.asList(this.getModuleDependencies(), this.getCircularModuleDependencies()));
  }

  public Set<Path> getExternalFileDependencies() {
    return externalFileDependencies.keySet();
  }

  public Set<Path> getGeneratedFiles() {
    return generatedFiles.keySet();
  }

  public Set<Path> getCircularFileDependencies() throws IOException {
    Set<Path> dependencies = new HashSet<Path>();
    Set<CompilationUnit> visited = new HashSet<>();
    LinkedList<CompilationUnit> queue = new LinkedList<>();
    queue.add(this);

    while (!queue.isEmpty()) {
      CompilationUnit res = queue.pop();
      visited.add(res);

      for (Path p : res.generatedFiles.keySet())
        if (!dependencies.contains(p) && FileCommands.exists(p))
          dependencies.add(p);
      for (Path p : res.externalFileDependencies.keySet())
        if (!dependencies.contains(p) && FileCommands.exists(p))
          dependencies.add(p);

      for (CompilationUnit nextDep : res.getModuleDependencies())
        if (!visited.contains(nextDep) && !queue.contains(nextDep))
          queue.addFirst(nextDep);
      for (CompilationUnit nextDep : res.getCircularModuleDependencies())
        if (!visited.contains(nextDep) && !queue.contains(nextDep))
          queue.addFirst(nextDep);
    }

    return dependencies;
  }

  public Synthesizer getSynthesizer() {
    return syn;
  }

  // ********************************************
  // Methods for checking compilation consistency
  // ********************************************

  protected abstract boolean isConsistentExtend(Mode mode);

  public Integer getInterfaceHash() {
    return this.interfaceHash;
  }

  public void setInterfaceHash(Integer hash) {
    this.interfaceHash = hash;
  }

  protected boolean isConsistentWithSourceArtifacts(Map<RelativePath, Integer> editedSourceFiles, Mode mode) {
    if (sourceArtifacts.isEmpty())
      return false;

    boolean hasEdits = editedSourceFiles != null;
    for (Entry<RelativePath, Integer> e : sourceArtifacts.entrySet()) {
      Integer stamp = hasEdits ? editedSourceFiles.get(e.getKey()) : null;
      if (stamp != null && !stamp.equals(e.getValue())) {
        return false;
      } else if (stamp == null && (!FileCommands.exists(e.getKey()) || e.getValue() != stamper.stampOf(e.getKey()))) {
        return false;
      }
    }

    return true;
  }

  public boolean isConsistentShallow(Map<RelativePath, Integer> editedSourceFiles, Mode mode) {
    if (hasPersistentVersionChanged())
      return false;

    if (!isConsistentWithSourceArtifacts(editedSourceFiles, mode))
      return false;

    for (Entry<Path, Integer> e : generatedFiles.entrySet())
      if (stamper.stampOf(e.getKey()) != e.getValue())
        return false;

    for (Entry<? extends Path, Integer> e : externalFileDependencies.entrySet())
      if (stamper.stampOf(e.getKey()) != e.getValue())
        return false;

    if (!isConsistentExtend(mode))
      return false;

    return true;
  }

  public boolean isConsistentToDependencyInterfaces() {
    if (!this.isConsistentToInterfaceMap(this.moduleDependencies)) {
      return false;
    }
    return this.isConsistentToInterfaceMap(this.circularModuleDependencies);
  }

  private boolean isConsistentToInterfaceMap(Map<CompilationUnit, Integer> unitMap) {
    for (Entry<CompilationUnit, Integer> deps : unitMap.entrySet()) {
      // Get interface (use Integer because may be null)
      Integer interfaceHash = deps.getKey().getInterfaceHash();
      // A null interface is always inconsistent
      if (deps.getValue() == null) {
        return false;
      }
      // Compare current interface value to stored one
      if (!deps.getValue().equals(interfaceHash)) {
        return false;
      }
    }
    return true;
  }

  public boolean isConsistent(final Map<RelativePath, Integer> editedSourceFiles, Mode mode) {
    ModuleVisitor<Boolean> isConsistentVisitor = new ModuleVisitor<Boolean>() {
      @Override
      public Boolean visit(CompilationUnit mod, Mode mode) {
        return mod.isConsistentShallow(editedSourceFiles, mode);
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
    };
    return visit(isConsistentVisitor, mode);
  }

  // *************************************
  // Methods for visiting the module graph
  // *************************************

  public static interface ModuleVisitor<T> {
    public T visit(CompilationUnit mod, Mode mode);

    public T combine(T t1, T t2);

    public T init();

    public boolean cancel(T t);
  }

  private Pair<Map<CompilationUnit, Integer>, Map<CompilationUnit, Mode>> computeRanks(Mode thisMode) {
    LinkedList<CompilationUnit> queue = new LinkedList<>();
    Map<CompilationUnit, Integer> ranks = new HashMap<>();

    Map<CompilationUnit, Mode> modes = new HashMap<>();

    queue.add(this);
    ranks.put(this, 0);

    if (thisMode != null)
      modes.put(this, thisMode);

    while (!queue.isEmpty()) {
      CompilationUnit mod = queue.remove();
      int rank = ranks.get(mod);

      Mode mode = modes.get(mod);
      Set<CompilationUnit> deps = new HashSet<>();
      deps.addAll(mod.getModuleDependencies());
      deps.addAll(mod.getCircularModuleDependencies());
      for (CompilationUnit dep : deps) {
        // if (dep.editedCompilationUnit != null &&
        // ranks.containsKey(dep.editedCompilationUnit))
        // dep = dep.editedCompilationUnit;

        Integer depRank = ranks.get(dep);
        if (depRank != null) {
          ranks.put(dep, Math.min(depRank, rank));
          ranks.put(mod, Math.min(depRank, rank));
        } else {
          depRank = rank - 1;
          ranks.put(dep, depRank);

          if (mode != null)
            modes.put(dep, mode.getModeForRequiredModules());
          if (!queue.contains(dep))
            queue.addFirst(dep);
        }
      }
    }

    return Pair.create(ranks, modes);
  }
  
  private Set<Set<CompilationUnit>> calculateConnectedComponents(Set<CompilationUnit> units) {
    Map<CompilationUnit, Set<CompilationUnit>> components = new HashMap<>();
    Map<CompilationUnit, CompilationUnit> representants = new HashMap<>();
    Queue<CompilationUnit> unitsToVisit = new LinkedList<>(units);
    for (CompilationUnit unit : units) {
      components.put(unit, new HashSet<>(Collections.singleton(unit)));
      representants.put(unit, unit);
    }
    
    while (!unitsToVisit.isEmpty()) {
      CompilationUnit unit = unitsToVisit.poll();
      CompilationUnit unitRep = representants.get(unit);
      Set<CompilationUnit> unitComp = components.get(unitRep);
      for (CompilationUnit dep : unit.getCircularAndNonCircularModuleDependencies()) {
        CompilationUnit depRep = representants.get(dep);
        if (depRep == null) {
        	// dep is not a member of units
        	continue;
        }
        if (depRep != unitRep) {
          Set<CompilationUnit> depComp = components.get(depRep);
          unitComp.addAll(depComp);
          for (CompilationUnit u : depComp) {
            representants.put(u, unitRep);
          }
          components.remove(depRep);
        }
      }
    }
    
    Set<Set<CompilationUnit>> componentsSet = new HashSet<>(components.values());
    assert validateConnectedComponents(componentsSet, units) : "Connected components wrong";
    return componentsSet;
  }
  
  private boolean validateConnectedComponents(Set<Set<CompilationUnit>> connectComps, Set<CompilationUnit> allUnits) {
	  List<Set<CompilationUnit>> components = new ArrayList<>(connectComps);
	  
	  Set<CompilationUnit> allUnitsCopy = new HashSet<>(allUnits);
	  
	  for (Set<CompilationUnit> component : components) {
		  allUnitsCopy.removeAll(component);
		  boolean connected = component.size() <=1;
		  for (CompilationUnit u : component) {
			  if (!allUnits.contains(u)) {
				  System.err.println("Contains unit which is not in allUnits");
				  return false;
			  }
			  for (CompilationUnit u2 : component) {
				  if (u == u2) continue;
				  if (u.dependsOnTransitivlyUsing(u2, component)) {
					  connected = true;
					  break;
				  }
			  }
		  }
		  if (!connected) {
			  System.err.println("Component is not connected");
			  return false;
		  }
	  }
	  
	  if (allUnitsCopy.size() > 0) {
		  System.err.println("Partition does not cover all units");
		  return false;
	  }
	  
	  for (int i = 0 ; i < components.size() -1; i++) {
		  for (int j = i +1 ; j < components.size(); j++) {
			 Set<CompilationUnit> comp1 = components.get(i);
			 Set<CompilationUnit> comp2 = components.get(j);
			 for (CompilationUnit u1 : comp1) {
				 for (CompilationUnit u2 : comp2) {
					 if (u1 == u2 || u1.dependsOnTransitivlyUsing(u2, allUnits)) {
						 System.err.println("Connections between components");
						 return false;
					 }
				 }
			 }
		  }
	  }
	  
	  return true;
  }
  
  private boolean dependsOnTransitivlyUsing(CompilationUnit other, Set<CompilationUnit> availableUnits) {
	  Queue<CompilationUnit> queue = new LinkedList<CompilationUnit>();
	  Set<CompilationUnit> seenUnits = new HashSet<>();
	  queue.add(this);
	  seenUnits.add(this);
	  while(!queue.isEmpty()) {
		  CompilationUnit unit = queue.poll();
		  if (unit == other)
			  return true;
		  for (CompilationUnit dep : unit.getCircularAndNonCircularModuleDependencies()) {
			  if (availableUnits.contains(dep) && ! seenUnits.contains(dep)) {
				  seenUnits.add(dep);
				  queue.add(dep);
			  }
		  }
	  }
	  return false;
  }
  
  private List<CompilationUnit> sortTopolocical(Set<CompilationUnit> connectedUnits) {
	  List<CompilationUnit> sorting = new ArrayList<>(connectedUnits);
	  Set<CompilationUnit> rootUnits = new HashSet<>(connectedUnits);
	  for (CompilationUnit u : connectedUnits) {
		  rootUnits.removeAll(u.getModuleDependencies());
	  }
	  assert rootUnits.size() > 0 : "Given Unit sets contains a cycle";
	  
	  final Map<CompilationUnit, Integer> rootDistance = new HashMap<>();
	  for (CompilationUnit u : rootUnits) {
		  rootDistance.put(u, 0);
	  }
	  
	  Queue<CompilationUnit> queue = new LinkedList<>(rootUnits);
	  while (!queue.isEmpty()) {
		  CompilationUnit unit = queue.poll();
		  int distance = rootDistance.get(unit);
		  for (CompilationUnit dep : unit.getModuleDependencies()) {
			  Integer depDistance = rootDistance.get(dep);
			  if (depDistance == null) {
				  rootDistance.put(dep, distance + 1);
			  } else {
				  rootDistance.put(dep, Math.max(depDistance, distance +1));
			  }
			  queue.add(dep);
		  }
	  }
	  
	  Collections.sort(sorting, new Comparator<CompilationUnit>() {

		@Override
		public int compare(CompilationUnit o1, CompilationUnit o2) {
			return Integer.compare(rootDistance.get(o1), rootDistance.get(o2));
		}
	});
	  assert validateTopolocialSorting(sorting) : "Topolocial sorting is not valid";
	  return sorting;
	   
  }
  
  private boolean validateTopolocialSorting(List<CompilationUnit> units) {
	  for (int i = units.size() - 1; i >= 1; i-- ) {
		  for (int j = i - 1; j >= 0; j--) {
			  if (units.get(i).dependsOnTransitivelyNoncircularly(units.get(j))) {
				  return false;
			  }
		  }
	  }
	  return true;
  }
  
  
  private boolean validateVisitOrder(CompilationUnit before, CompilationUnit after) {
    return (!after.dependsOnTransitively(before)) || (!after.dependsOnTransitivelyNoncircularly(before));
  }
  
  private boolean validateVisitOrder(List<CompilationUnit> units) {
    for (int i = 0; i < units.size() -1; i++) {
      for (int j = i+1; j < units.size(); j++) {
        if (!validateVisitOrder(units.get(i), units.get(j))) {
        	System.out.println(i + " " + j);
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Visits the module graph starting from this module, satisfying the following
   * properties: - every module transitively imported from `this` module is
   * visited exactly once - if a module M1 is visited before a module M2, then
   * (i) M1 is not transitively imported from M2 or (ii) M1 and M2 transitively
   * have a circular dependency and M1 transitively imports M2 using
   * `moduleDependencies` only.
   */
  public <T> T visit(ModuleVisitor<T> visitor) {
    return visit(visitor, null);
  }
  
  public <T> T visit(ModuleVisitor<T> visitor, Mode thisMode) {
    return visit(visitor, thisMode, false);
  }

  public <T> T visit(ModuleVisitor<T> visitor, Mode thisMode, boolean reverse) {
    Pair<Map<CompilationUnit, Integer>, Map<CompilationUnit, Mode>> p = computeRanks(thisMode);
    final Map<CompilationUnit, Integer> ranks = p.a;
    Map<CompilationUnit, Mode> modes = p.b;
    

   
    // Group the dependencies by rank
    // This defines the outer sorting
    Map<Integer, Set<CompilationUnit>> rankGroups = new HashMap<>();
    int minRank = 0;
    for (CompilationUnit unit : ranks.keySet()) {
      int rank = ranks.get(unit);
      minRank = Math.min(rank, minRank);
      if (rankGroups.get(rank) == null) {
        rankGroups.put(rank, new HashSet<CompilationUnit>());
      }
      rankGroups.get(rank).add(unit);
    }
    
    // Now sort the compilation units in the same rank group
    List<List<CompilationUnit>> sortedUnits = new ArrayList<>(rankGroups.size());
    for (int i = 0; i >= minRank; i --) {
      Set<CompilationUnit> group = rankGroups.get(i);
      if (group == null) continue;
      // Calculate the connected components
      // The order of the connected components does not matter
      Set<Set<CompilationUnit>> components = this.calculateConnectedComponents(group);
    //  System.out.println("Rank Group: " + group);
     // System.out.println("Components: " + components );
      for (Set<CompilationUnit> component : components) {
        // In a component sort the units by direct module dependency
	    List<CompilationUnit> sortedComponent = this.sortTopolocical(component);
	    assert validateVisitOrder(sortedComponent) : "Sorted connected component validates visit contract";
       // System.out.println("Sorted: " + sortedComponent);
	    if (reverse) {
	      Collections.reverse(sortedComponent);
	    }
        sortedUnits.add(sortedComponent);
       
      }
    }
    if (reverse) {
      Collections.reverse(sortedUnits);
    }
   
 //   System.out.println("Calculated list: " + sortedUnits);

    // Now iterate over the calculated list
    T result = visitor.init();
    for (CompilationUnit mod : new AppendingIterable<>(sortedUnits)) {
      T newResult = visitor.visit(mod, modes.get(mod));
      result = visitor.combine(result, newResult);
      if (visitor.cancel(result))
        break;
    }

    return result;
  }

  // *************************
  // Methods for serialization
  // *************************

  @Override
  @SuppressWarnings("unchecked")
  protected void readEntity(ObjectInputStream in) throws IOException, ClassNotFoundException {
    targetDir = (Path) in.readObject();
    sourceArtifacts = (Map<RelativePath, Integer>) in.readObject();
    generatedFiles = (Map<Path, Integer>) in.readObject();
    externalFileDependencies = (Map<Path, Integer>) in.readObject();
    this.interfaceHash = (Integer) in.readObject();

    int moduleDepencyCount = in.readInt();
    moduleDependencies = new HashMap<>(moduleDepencyCount);
    for (int i = 0; i < moduleDepencyCount; i++) {
      String clName = (String) in.readObject();
      Class<? extends CompilationUnit> cl = (Class<? extends CompilationUnit>) getClass().getClassLoader().loadClass(clName);
      Path path = (Path) in.readObject();
      CompilationUnit mod = PersistableEntity.read(cl, stamper, path);
      Integer interfaceHash = (Integer) in.readObject();
      if (mod == null)
        throw new IOException("Required module cannot be read: " + path);
      moduleDependencies.put(mod, interfaceHash);
    }

    int circularModuleDependencyCount = in.readInt();
    circularModuleDependencies = new HashMap<>(circularModuleDependencyCount);
    for (int i = 0; i < circularModuleDependencyCount; i++) {
      String clName = (String) in.readObject();
      Class<? extends CompilationUnit> cl = (Class<? extends CompilationUnit>) getClass().getClassLoader().loadClass(clName);
      Path path = (Path) in.readObject();
      CompilationUnit mod = PersistableEntity.read(cl, stamper, path);
      Integer interfaceHash = (Integer) in.readObject();
      if (mod == null)
        throw new IOException("Required module cannot be read: " + path);
      circularModuleDependencies.put(mod, interfaceHash);
    }

    boolean hasSyn = in.readBoolean();
    if (hasSyn) {
      Set<CompilationUnit> modules = new HashSet<CompilationUnit>();
      int modulesCount = in.readInt();
      for (int i = 0; i < modulesCount; i++) {
        String clName = (String) in.readObject();
        Class<? extends CompilationUnit> cl = (Class<? extends CompilationUnit>) getClass().getClassLoader().loadClass(clName);
        Path path = (Path) in.readObject();
        CompilationUnit mod = PersistableEntity.read(cl, stamper, path);
        if (mod == null)
          throw new IOException("Required module cannot be read: " + path);
        modules.add(mod);
      }
      Map<Path, Integer> files = (Map<Path, Integer>) in.readObject();
      syn = new Synthesizer(modules, files);
    }
  }

  @Override
  protected void writeEntity(ObjectOutputStream out) throws IOException {
    out.writeObject(targetDir);
    out.writeObject(sourceArtifacts = Collections.unmodifiableMap(sourceArtifacts));
    out.writeObject(generatedFiles = Collections.unmodifiableMap(generatedFiles));
    out.writeObject(externalFileDependencies = Collections.unmodifiableMap(externalFileDependencies));
    out.writeObject(this.interfaceHash);

    out.writeInt(moduleDependencies.size());
    for (Entry<CompilationUnit, Integer> entry : moduleDependencies.entrySet()) {
      CompilationUnit mod = entry.getKey();
      assert mod.isPersisted() : "Required compilation units must be persisted.";
      out.writeObject(mod.getClass().getCanonicalName());
      out.writeObject(mod.persistentPath);
      out.writeObject(entry.getValue());
    }

    out.writeInt(circularModuleDependencies.size());
    for (Entry<CompilationUnit, Integer> entry : circularModuleDependencies.entrySet()) {
      CompilationUnit mod = entry.getKey();
      out.writeObject(mod.getClass().getCanonicalName());
      out.writeObject(mod.persistentPath);
      out.writeObject(entry.getValue());
    }

    out.writeBoolean(syn != null);
    if (syn != null) {
      out.writeInt(syn.generatorModules.size());
      for (CompilationUnit mod : syn.generatorModules) {
        out.writeObject(mod.getClass().getCanonicalName());
        out.writeObject(mod.persistentPath);
      }

      out.writeObject(syn.files);
    }
  }
}
