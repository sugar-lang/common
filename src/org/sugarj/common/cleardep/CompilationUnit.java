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

  protected CompilationUnit editedCompilationUnit;
  
  protected Map<RelativePath, Integer> sourceArtifacts;
  protected Map<CompilationUnit, Integer> moduleDependencies;
  protected Set<CompilationUnit> circularModuleDependencies;  
  protected Map<RelativePath, Integer> externalFileDependencies;
  protected Map<Path, Integer> generatedFiles;
  
  @Override
  protected void init() {
    sourceArtifacts = new HashMap<>();
    moduleDependencies = new HashMap<>();
    circularModuleDependencies = new HashSet<>();
    externalFileDependencies = new HashMap<>();
    generatedFiles = new HashMap<>();
  }
  
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
  }
  
  public void addGeneratedFile(Path file) { addGeneratedFile(file, stamper.stampOf(file)); }
  public void addGeneratedFile(Path file, int stampOfFile) {
    generatedFiles.put(file, stampOfFile);
  }
  
  public void addCircularModuleDependency(CompilationUnit mod) {
    circularModuleDependencies.add(mod);
  }
  
  public void addModuleDependency(CompilationUnit mod) throws IOException {
    moduleDependencies.put(mod, mod.stamp());
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
    boolean hasEdits = conistencyCheckEditedSourceArtifacts != null;
    for (Entry<RelativePath, Integer> e : sourceArtifacts.entrySet()) {
      Integer stamp = hasEdits ? conistencyCheckEditedSourceArtifacts.get(e.getKey()) : null;
      if (stamp != null && stamp != e.getValue())
        return false;
      else if (!FileCommands.exists(e.getKey()) || e.getValue() != stamper.stampOf(e.getKey()))
        return false;
    }

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
    @Override public boolean cancel(Boolean t) { return !t; }
  }; 

  public boolean isConsistent() {
    return visit(isConsistentVisitor);
  }
  
  private Map<? extends Path, Integer> conistencyCheckEditedSourceArtifacts;
  public boolean isConsistent(final Map<? extends Path, Integer> editedSourceArtifacts) {
    if (editedSourceArtifacts.isEmpty())
      return isConsistent();
    
    final ModuleVisitor<Boolean> isConsistentEditedVisitor = new ModuleVisitor<Boolean>() {
      @Override public Boolean visit(CompilationUnit mod) {
        if (mod.editedCompilationUnit == null || Collections.disjoint(mod.sourceArtifacts.keySet(), editedSourceArtifacts.keySet()))
          return mod.isConsistentShallow();
        
        if (editedCompilationUnit == null)
          return false;
        
        synchronized (mod) {
          mod.conistencyCheckEditedSourceArtifacts = editedSourceArtifacts;
          try {
            return mod.editedCompilationUnit.isConsistentShallow();
          } finally {
            mod.conistencyCheckEditedSourceArtifacts = null;
          }
        } 
      }
      @Override public Boolean combine(Boolean t1, Boolean t2) { return t1 && t2; }
      @Override public Boolean init() { return true; }
      @Override public boolean cancel(Boolean t) { return !t; }
    }; 
    
    return visit(isConsistentEditedVisitor);
  }
  
  
  // *************************************
  // Methods for visiting the module graph
  // *************************************

  public static interface ModuleVisitor<T> {
    public T visit(CompilationUnit mod);
    public T combine(T t1, T t2);
    public T init();
    public boolean cancel(T t);
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
        if (rDep != null) {
          ranks.put(dep, Math.min(rDep, rMod));
          ranks.put(mod, Math.min(rDep, rMod));
        }
        else {
          rDep = rMod - 1;
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
   *  - every module transitively imported from `this` module is visited exactly once
   *  - if a module M1 is visited before a module M2,
   *    then M1 is not transitively imported from M2 or M1 and M2 are mutually dependent.   
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
    sourceArtifacts = (Map<RelativePath, Integer>) in.readObject();
    generatedFiles = (Map<Path, Integer>) in.readObject();
    externalFileDependencies = (Map<RelativePath, Integer>) in.readObject();
    
    int moduleDepencyCount = in.readInt();
    moduleDependencies = new HashMap<>(moduleDepencyCount);
    for (int i = 0; i < moduleDepencyCount; i++) {
      String clName = (String) in.readObject();
      Class<? extends CompilationUnit> cl = (Class<? extends CompilationUnit>) getClass().getClassLoader().loadClass(clName);
      Path path = (Path) in.readObject();
      int stamp = in.readInt();
      CompilationUnit mod = PersistableEntity.read(cl, stamper, path);
      moduleDependencies.put(mod, stamp);
    }
    
    int circularModuleDependencyCount = in.readInt();
    circularModuleDependencies = new HashSet<>(circularModuleDependencyCount);
    for (int i = 0; i < circularModuleDependencyCount; i++) {
      String clName = (String) in.readObject();
      Class<? extends CompilationUnit> cl = (Class<? extends CompilationUnit>) getClass().getClassLoader().loadClass(clName);
      Path path = (Path) in.readObject();
      CompilationUnit mod = PersistableEntity.read(cl, stamper, path);
      circularModuleDependencies.add(mod);
    }
  }

  @Override
  protected void writeEntity(ObjectOutputStream out) throws IOException {
    out.writeObject(sourceArtifacts = Collections.unmodifiableMap(sourceArtifacts));
    out.writeObject(generatedFiles = Collections.unmodifiableMap(generatedFiles));
    out.writeObject(externalFileDependencies = Collections.unmodifiableMap(externalFileDependencies));

    out.writeInt(moduleDependencies.size());
    for (Entry<CompilationUnit, Integer> e : moduleDependencies.entrySet()) {
      assert e.getKey().isPersisted() : "Required compilation units must be persisted.";
      out.writeObject(e.getKey().getClass().getCanonicalName());
      out.writeObject(e.getKey().persistentPath);
      out.writeInt(e.getValue());
    }
    
    out.writeInt(circularModuleDependencies.size());
    for (CompilationUnit mod : circularModuleDependencies) {
//      assert mod.isPersisted() : "Circularly required compilation units must be persisted.";
      out.writeObject(mod.getClass().getCanonicalName());
      out.writeObject(mod.persistentPath);
    }
  }
}
