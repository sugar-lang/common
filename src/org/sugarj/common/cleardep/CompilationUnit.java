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
import org.sugarj.util.Pair;

/**
 * Dependency management for modules.
 * 
 * @author Sebastian Erdweg
 */
abstract public class CompilationUnit extends PersistableEntity {
  
  public static final long serialVersionUID = -5713504273621720673L;

  public CompilationUnit() { /* for deserialization only */ }

  // At most one of `compiledCompilationUnit` and `editedCompilationUnit` is not null.
  protected CompilationUnit compiledCompilationUnit;
  protected CompilationUnit editedCompilationUnit;
  
  protected Path targetDir;
  protected Map<RelativePath, Integer> sourceArtifacts;
  protected Set<CompilationUnit> moduleDependencies;
  protected Set<CompilationUnit> circularModuleDependencies;  
  protected Map<Path, Integer> externalFileDependencies;
  protected Map<Path, Integer> generatedFiles;

  // **************************
  // Methods for initialization
  // **************************

  @SuppressWarnings("unchecked")
  final protected static <E extends CompilationUnit> E create(Class<E> cl, Stamper stamper, Path compileDep, Path compileTarget, Path editedDep, Path editedTarget, Set<RelativePath> sourceFiles, Mode mode) throws IOException {
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
    
    E e = mode.doCompile ? compileE : editedE;
    e.init();
    
    for (RelativePath sourceFile : sourceFiles) {
      Integer editedStamp = mode.editedSourceFiles == null ? null : mode.editedSourceFiles.get(sourceFile);
      if (editedStamp != null)
        e.addSourceArtifact(sourceFile, editedStamp);
      else
        e.addSourceArtifact(sourceFile);
    }
    
    return e;
  }
 
  @SuppressWarnings("unchecked")
  final protected static <E extends CompilationUnit> Pair<E, Boolean> read(Class<E> cl, Stamper stamper, Path compileDep, Path editedDep, Mode mode) throws IOException {
//    ModuleVisitor<Boolean> hasEditedSourceFilesVisitor = new ModuleVisitor<Boolean>() {
//      @Override public Boolean visit(CompilationUnit mod) { return !Collections.disjoint(mod.sourceArtifacts.keySet(), editedSourceFiles.keySet()); }
//      @Override public Boolean combine(Boolean t1, Boolean t2) { return t1 || t2; }
//      @Override public Boolean init() { return false; }
//      @Override public boolean cancel(Boolean t) { return t == true; }
//    };
    
    E compileE = PersistableEntity.read(cl, stamper, compileDep);
    if (compileE != null && compileE.isConsistent(mode))
      // valid compile is good for compilation and parsing
      return Pair.create(compileE, true);
    
    E editedE;
    if (compileE != null && compileE.editedCompilationUnit != null)
      editedE = (E) compileE.editedCompilationUnit;
    else
      editedE = PersistableEntity.read(cl, stamper, editedDep);
    
    // valid edit is good for compilation after lifting
    if (mode.doCompile && editedE != null && editedE.isConsistent(mode)) {
      editedE.liftEditedToCompiled(); 
      return Pair.create((E) editedE.compiledCompilationUnit, true);
    }
    
    // valid edit is good for parsing
    if (!mode.doCompile && editedE != null && editedE.isConsistent(mode))
      return Pair.create(editedE, true);
    
    return Pair.create(mode.doCompile ? compileE : editedE, false);
  }
  
  protected void copyContentTo(CompilationUnit compiled) {
    compiled.sourceArtifacts.putAll(sourceArtifacts);
    
    for (CompilationUnit dep : moduleDependencies)
      if (dep.compiledCompilationUnit == null)
        compiled.addModuleDependency(dep);
      else
        compiled.addModuleDependency(dep.compiledCompilationUnit);
    
    for (CompilationUnit dep : circularModuleDependencies)
      if (dep.compiledCompilationUnit == null)
        compiled.addCircularModuleDependency(dep);
      else
        compiled.addCircularModuleDependency(dep.compiledCompilationUnit);
    
    for (Path p : externalFileDependencies.keySet())
      compiled.addExternalFileDependency(FileCommands.tryCopyFile(targetDir, compiled.targetDir, p));
    
    for (Path p : generatedFiles.keySet())
      compiled.addGeneratedFile(FileCommands.tryCopyFile(targetDir, compiled.targetDir, p));
  }
  
  protected void liftEditedToCompiled() throws IOException {
    ModuleVisitor<Void> liftVisitor = new ModuleVisitor<Void>() {
      @Override public Void visit(CompilationUnit mod) {
        if (mod.compiledCompilationUnit == null)
          throw new IllegalStateException("compiledCompilationUnit of " + mod + " must not be null");
        
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
      @Override public Void combine(Void t1, Void t2) { return null; }
      @Override public Void init() { return null; }
      @Override public boolean cancel(Void t) { return false; }
    };
    
    if (editedCompilationUnit != null)
      editedCompilationUnit.visit(liftVisitor);
    else
      this.visit(liftVisitor);
  }

  @Override
  protected void init() {
    sourceArtifacts = new HashMap<>();
    moduleDependencies = new HashSet<>();
    circularModuleDependencies = new HashSet<>();
    externalFileDependencies = new HashMap<>();
    generatedFiles = new HashMap<>();
  }
  
  // *******************************
  // Methods for adding dependencies
  // *******************************
  
  protected void addSourceArtifact(RelativePath file) { addSourceArtifact(file, stamper.stampOf(file)); }
  protected void addSourceArtifact(RelativePath file, int stampOfFile) {
    sourceArtifacts.put(file, stampOfFile);
  }

  public void addExternalFileDependency(Path file) { addExternalFileDependency(file, stamper.stampOf(file)); }
  public void addExternalFileDependency(Path file, int stampOfFile) {
    externalFileDependencies.put(file, stampOfFile);
  }
  
  public void addGeneratedFile(Path file) { addGeneratedFile(file, stamper.stampOf(file)); }
  public void addGeneratedFile(Path file, int stampOfFile) {
    generatedFiles.put(file, stampOfFile);
  }
  
  public void addCircularModuleDependency(CompilationUnit mod) {
    circularModuleDependencies.add(mod);
  }
  
  public void addModuleDependency(CompilationUnit mod) {
    moduleDependencies.add(mod);
  }
  
  
  // *********************************
  // Methods for querying dependencies
  // *********************************

  public boolean isParsedCompilationUnit() {
    return compiledCompilationUnit != null;
  }
  
  public boolean dependsOnNoncircularly(CompilationUnit other) {
    return moduleDependencies.contains(other);    
  }

  public boolean dependsOnTransitivelyNoncircularly(CompilationUnit other) {
    if (dependsOnNoncircularly(other))
      return true;
    for (CompilationUnit mod : moduleDependencies)
      if (mod.dependsOnTransitivelyNoncircularly(other))
        return true;
    return false;
  }

  public boolean dependsOn(CompilationUnit other) {
    return moduleDependencies.contains(other) || circularModuleDependencies.contains(other);    
  }

  public boolean dependsOnTransitively(CompilationUnit other) {
    if (dependsOn(other))
      return true;
    for (CompilationUnit mod : moduleDependencies)
      if (mod.dependsOnTransitively(other))
        return true;
    return false;
  }

  public Set<RelativePath> getSourceArtifacts() {
    return sourceArtifacts.keySet();
  }
  
  public Set<CompilationUnit> getModuleDependencies() {
    return moduleDependencies;
  }
  
  public Set<CompilationUnit> getCircularModuleDependencies() {
    return circularModuleDependencies;
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
      
      for (CompilationUnit nextDep: res.moduleDependencies)
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

  protected abstract boolean isConsistentExtend(Mode mode);
  
  protected boolean isConsistentWithSourceArtifacts(Mode mode) {
    if (sourceArtifacts.isEmpty())
      return false;
    
    boolean hasEdits = mode.editedSourceFiles != null;
    for (Entry<RelativePath, Integer> e : sourceArtifacts.entrySet()) {
      Integer stamp = hasEdits ? mode.editedSourceFiles.get(e.getKey()) : null;
      if (stamp != null && !stamp.equals(e.getValue()))
        return false;
      else if (stamp == null && (!FileCommands.exists(e.getKey()) || e.getValue() != stamper.stampOf(e.getKey())))
        return false;
    }

    return true;
  }

  public boolean isConsistentShallow(Mode mode) {
    if (hasPersistentVersionChanged())
      return false;
    
    if (!isConsistentWithSourceArtifacts(mode))
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

  public boolean isConsistent(final Mode mode) {
    ModuleVisitor<Boolean> isConsistentVisitor = new ModuleVisitor<Boolean>() {
      @Override public Boolean visit(CompilationUnit mod) { return mod.isConsistentShallow(mode); }
      @Override public Boolean combine(Boolean t1, Boolean t2) { return t1 && t2; }
      @Override public Boolean init() { return true; }
      @Override public boolean cancel(Boolean t) { return !t; }
    }; 
    return visit(isConsistentVisitor);
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
//        if (dep.editedCompilationUnit != null && ranks.containsKey(dep.editedCompilationUnit))
//          dep = dep.editedCompilationUnit;
        
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
   *    then (i) M1 is not transitively imported from M2 or 
   *         (ii) M1 and M2 transitively have a circular dependency and
   *              M1 transitively imports M2 using `moduleDependencies` only.   
   */
  public <T> T visit(ModuleVisitor<T> visitor) {
    final Map<CompilationUnit, Integer> ranks = computeRanks();
    
    Comparator<CompilationUnit> comparator = new Comparator<CompilationUnit>() {
      public int compare(CompilationUnit m1, CompilationUnit m2) { 
        int r1 = ranks.get(m1);
        int r2 = ranks.get(m2);
        int c = Integer.compare(r1, r2);
        if (c != 0)
          return c;
        if (m1.dependsOnTransitivelyNoncircularly(m2))
          // m2 before m1
          return 1;
        // m2.dependsOnTransitivelyNoncircularly(m1) || m1 and m2 are incomparable;
        // m1 before m2
        return -1;
      }
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
    targetDir = (Path) in.readObject();
    sourceArtifacts = (Map<RelativePath, Integer>) in.readObject();
    generatedFiles = (Map<Path, Integer>) in.readObject();
    externalFileDependencies = (Map<Path, Integer>) in.readObject();
    
    int moduleDepencyCount = in.readInt();
    moduleDependencies = new HashSet<>(moduleDepencyCount);
    for (int i = 0; i < moduleDepencyCount; i++) {
      String clName = (String) in.readObject();
      Class<? extends CompilationUnit> cl = (Class<? extends CompilationUnit>) getClass().getClassLoader().loadClass(clName);
      Path path = (Path) in.readObject();
      CompilationUnit mod = PersistableEntity.read(cl, stamper, path);
      moduleDependencies.add(mod);
    }
    
    int circularModuleDependencyCount = in.readInt();
    circularModuleDependencies = new HashSet<>(circularModuleDependencyCount);
    for (int i = 0; i < circularModuleDependencyCount; i++) {
      String clName = (String) in.readObject();
      Class<? extends CompilationUnit> cl = (Class<? extends CompilationUnit>) getClass().getClassLoader().loadClass(clName);
      Path path = (Path) in.readObject();
      CompilationUnit mod = PersistableEntity.read(cl, stamper, path);
      if (mod == null)
        throw new IllegalStateException("Required module cannot be read: " + path);
      circularModuleDependencies.add(mod);
    }
  }

  @Override
  protected void writeEntity(ObjectOutputStream out) throws IOException {
    out.writeObject(targetDir);
    out.writeObject(sourceArtifacts = Collections.unmodifiableMap(sourceArtifacts));
    out.writeObject(generatedFiles = Collections.unmodifiableMap(generatedFiles));
    out.writeObject(externalFileDependencies = Collections.unmodifiableMap(externalFileDependencies));

    out.writeInt(moduleDependencies.size());
    for (CompilationUnit mod : moduleDependencies) {
      assert mod.isPersisted() : "Required compilation units must be persisted.";
      out.writeObject(mod.getClass().getCanonicalName());
      out.writeObject(mod.persistentPath);
    }
    
    out.writeInt(circularModuleDependencies.size());
    for (CompilationUnit mod : circularModuleDependencies) {
      out.writeObject(mod.getClass().getCanonicalName());
      out.writeObject(mod.persistentPath);
    }
  }
}
