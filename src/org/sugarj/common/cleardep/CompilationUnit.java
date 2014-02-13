package org.sugarj.common.cleardep;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.sugarj.common.FileCommands;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;

/**
 * Dependency management for modules.
 * 
 * @author Sebastian Erdweg
 */
abstract public class CompilationUnit extends PersistableEntity {
  
  public CompilationUnit() { /* for deserialization only */ }
  public CompilationUnit(Stamper stamper) {
    super(stamper);
  }

  private Map<RelativePath, Integer> sourceArtifacts = new HashMap<>();
  
  private Map<CompilationUnit, Integer> moduleDependencies = new HashMap<>();
  private Set<CompilationUnit> circularModuleDependencies = new HashSet<>();
  
  private Map<RelativePath, Integer> externalFileDependencies = new HashMap<>();
  
  private Map<Path, Integer> generatedFiles = new HashMap<>();
  
  /**
   * Transitive closure (over module dependencies) of required and generated files.
   */
  transient private Map<Path, Integer> transitivelyAffectedFiles = new HashMap<>();

  
  // *******************************
  // Methods for adding dependencies
  // *******************************
  
  public void addSourceArtifact(RelativePath file) { addSourceArtifact(file, stamper.stampOf(file)); }
  public void addSourceArtifact(RelativePath file, int stampOfFile) {
    sourceArtifacts.put(file, stampOfFile);
  }
  
  public void addExternalFileDependency(RelativePath file) { addExternalFileDependency(file, stamper.stampOf(file)); }
  public void addExternalFileDependency(RelativePath file, int stampOfFile) {
    externalFileDependencies.put(file, stampOfFile);
    transitivelyAffectedFiles.put(file, stampOfFile);
  }
  
  public void addGeneratedFile(Path file) { addGeneratedFile(file, stamper.stampOf(file)); }
  public void addGeneratedFile(Path file, int stampOfFile) {
    generatedFiles.put(file, stampOfFile);
    transitivelyAffectedFiles.put(file, stampOfFile);
  }
  
  public void addCircularModuleDependency(CompilationUnit mod) {
    circularModuleDependencies.add(mod);
  }
  
  public void addModuleDependency(CompilationUnit mod) throws IOException {
    moduleDependencies.put(mod, mod.stamp());
    transitivelyAffectedFiles.putAll(mod.getTransitivelyAffectedFileStamps());
  }
  
  
  // *********************************
  // Methods for querying dependencies
  // *********************************

  public boolean dependsOn(CompilationUnit other) {
    return moduleDependencies.containsKey(other) || circularModuleDependencies.contains(other);    
  }

  public boolean dependsOnTransitively(CompilationUnit other) {
    if (dependsOn(other))
      return true;
    for (CompilationUnit mod : moduleDependencies.keySet())
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
    return circularModuleDependencies;
  }
  
  public Set<RelativePath> getExternalFileDependencies() {
    return externalFileDependencies.keySet();
  }
  
  public Set<Path> getGeneratedFiles() {
    return generatedFiles.keySet();
  }

  public Set<Path> getTransitivelyAffectedFiles() {
    return getTransitivelyAffectedFileStamps().keySet();
  }
  
  private Map<Path, Integer> getTransitivelyAffectedFileStamps() {
    if (transitivelyAffectedFiles == null) {
      final Map<Path, Integer> deps = new HashMap<>();
      
      ModuleVisitor<Void> collectAffectedFileStampsVisitor = new ModuleVisitor<Void>() {
        @Override public Void visit(CompilationUnit mod) { 
          deps.putAll(generatedFiles); 
          deps.putAll(externalFileDependencies);
          return null;
        }
        @Override public Void combine(Void v1, Void v2) { return null; }
        @Override public Void init() { return null; }
      };
      
      visit(collectAffectedFileStampsVisitor);
      
      synchronized(this) { transitivelyAffectedFiles = deps; }
    }

    return transitivelyAffectedFiles;
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
      
      for (CompilationUnit nextDep: res.moduleDependencies.keySet())
        if (!visited.contains(nextDep) && !queue.contains(nextDep))
          queue.addFirst(nextDep);
      for (CompilationUnit nextDep : res.circularModuleDependencies)
        if (!visited.contains(nextDep) && !queue.contains(nextDep))
          queue.addFirst(nextDep);
    }
    
    return dependencies;
  }

  
  // ********************************************
  // Methods for checking compilation consistency
  // ********************************************

  protected abstract boolean isConsistentExtend();
  
  protected boolean isConsistentWithSourceArtifacts() {
    for (Entry<RelativePath, Integer> e : sourceArtifacts.entrySet())
      if (editedSourceArtifacts != null && editedSourceArtifacts.containsKey(e.getKey()) && !editedSourceArtifacts.get(e.getKey()).equals(e.getValue()))
        return false;
      else if (!FileCommands.exists(e.getKey()) || e.getValue() != stamper.stampOf(e.getKey()))
        return false;
    return true;
  }

  public boolean isConsistentShallow() {
    if (hasPersistentVersionChanged())
      return false;
    
    if (!isConsistentWithSourceArtifacts())
      return false;
    
    for (Entry<Path, Integer> e : generatedFiles.entrySet())
      if (stamper.stampOf(e.getKey()) != e.getValue())
        return false;
    
    for (Entry<? extends Path, Integer> e : externalFileDependencies.entrySet())
      if (stamper.stampOf(e.getKey()) != e.getValue())
        return false;

    for (Entry<CompilationUnit, Integer> e : moduleDependencies.entrySet())
      if (e.getKey().stamp() != e.getValue())
        return false;
    
    if (!isConsistentExtend())
      return false;

    return true;
  }

  private final ModuleVisitor<Boolean> isConsistentVisitor = new ModuleVisitor<Boolean>() {
    @Override public Boolean visit(CompilationUnit mod) { return mod.isConsistentShallow(); }
    @Override public Boolean combine(Boolean t1, Boolean t2) { return t1 && t2; }
    @Override public Boolean init() { return true; }
  }; 

  public boolean isConsistent() {
    return visit(isConsistentVisitor);
  }
  
  private Map<RelativePath, Integer> editedSourceArtifacts = null;
  public synchronized boolean isConsistent(Map<RelativePath, Integer> editedSourceArtifacts) {
    this.editedSourceArtifacts = editedSourceArtifacts;
    try {
      return visit(isConsistentVisitor);
    } finally {
      this.editedSourceArtifacts = null;
    }
  }
  
  
  // *************************************
  // Methods for visiting the module graph
  // *************************************

  public static interface ModuleVisitor<T> {
    public T visit(CompilationUnit mod);
    public T combine(T t1, T t2);
    public T init();
  }

  private Map<CompilationUnit, Integer> computeRanks() {
    LinkedList<CompilationUnit> queue = new LinkedList<>();
    Map<CompilationUnit, Integer> ranks = new HashMap<>();
    
    queue.add(this);
    ranks.put(this, 0);
    
    while (!queue.isEmpty()) {
      CompilationUnit mod = queue.remove();
      int rMod = ranks.get(mod);
      
      Set<CompilationUnit> deps = new HashSet<>();
      deps.addAll(mod.getModuleDependencies());
      deps.addAll(mod.getCircularModuleDependencies());
      for (CompilationUnit dep : deps) {
        Integer rDep = ranks.get(dep);
        if (rDep != null)
          ranks.put(dep, Math.min(rDep, rMod));
        else {
          rDep = rMod + 1;
          ranks.put(dep,  rDep);
          if (!queue.contains(dep))
            queue.addFirst(dep);
        }
      }
    }
    
    return ranks;
  }
  
  /**
   * Visits the module graph starting from this module, satisfying the following properties:
   *  - every module reachable from `this` module is visited exactly once
   *  - if a module M1 is visited before a module M2,
   *    then M1 is not reachable from M2 or M1 and M2 are mutually reachable.   
   */
  public <T> T visit(ModuleVisitor<T> visitor) {
    final Map<CompilationUnit, Integer> ranks = computeRanks();
    
    Comparator<CompilationUnit> comparator = new Comparator<CompilationUnit>() {
      public int compare(CompilationUnit m1, CompilationUnit m2) { return ranks.get(m1).compareTo(ranks.get(m2)); }
    }; 
    
    CompilationUnit[] mods = ranks.keySet().toArray(new CompilationUnit[ranks.size()]);
    Arrays.sort(mods, comparator);
    
    T result = visitor.init();
    for (CompilationUnit mod : mods) {
      T newResult = visitor.visit(mod);
      result = visitor.combine(result, newResult);
    }
    
    return result;
  }
  
  
  // *************************
  // Methods for serialization
  // *************************

  @Override
  @SuppressWarnings("unchecked")
  protected void readEntity(ObjectInputStream in) throws IOException, ClassNotFoundException {
    sourceArtifacts = (Map<RelativePath, Integer>) in.readObject();
    moduleDependencies = (Map<CompilationUnit, Integer>) in.readObject();
    circularModuleDependencies = (Set<CompilationUnit>) in.readObject();
    generatedFiles = (Map<Path, Integer>) in.readObject();
    externalFileDependencies = (Map<RelativePath, Integer>) in.readObject();
  }

  @Override
  protected void writeEntity(ObjectOutputStream out) throws IOException {
    out.writeObject(sourceArtifacts = Collections.unmodifiableMap(sourceArtifacts));
    out.writeObject(moduleDependencies = Collections.unmodifiableMap(moduleDependencies));
    out.writeObject(circularModuleDependencies = Collections.unmodifiableSet(circularModuleDependencies));
    out.writeObject(generatedFiles = Collections.unmodifiableMap(generatedFiles));
    out.writeObject(externalFileDependencies = Collections.unmodifiableMap(externalFileDependencies));
  }
}
