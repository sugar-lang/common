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
import org.sugarj.common.cleardep.mode.DoCompileMode;
import org.sugarj.common.cleardep.mode.Mode;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;
import org.sugarj.util.Pair;

/**
 * Dependency management for modules. <br>
 * <br>
 * For each module there are two <i>CompilationUnit</i>s. One for automatic
 * compilations (generates only temporary files) and one for "real" compilation
 * that may use already generated files (if valid)
 * 
 * @author Sebastian Erdweg
 */
abstract public class CompilationUnit extends PersistableEntity {
  
  public static final long serialVersionUID = -5713504273621720673L;

  public CompilationUnit() { /* for deserialization only */ }

  // Exactly one of `compiledCompilationUnit` and `editedCompilationUnit` is not null.
  protected CompilationUnit compiledCompilationUnit;
  protected CompilationUnit editedCompilationUnit;
  
  protected Synthesizer syn;
  
  /**
   * if this == compiledCompilationUnit -> temporary directory <br>
   * if this == editedCompilationUnit   -> i.e. /bin folder
   */
  protected Path targetDir;
  
  /**
   * source files
   */
  protected Map<RelativePath, Integer> sourceArtifacts;
  
  protected Set<CompilationUnit> moduleDependencies;
  protected Set<CompilationUnit> circularModuleDependencies;
  
  protected Map<Path, Integer> externalFileDependencies;
  
  protected Map<Path, Integer> generatedFiles;

  // **************************
  // Methods for initialization
  // **************************

  /**
   * 
   * @param cl
   * @param stamper
   * @param compileDep
   *          path to the persisted compiledCompilationUnit (.dep file)
   * @param compileTarget
   *          i.e.: /bin folder
   * @param editedDep
   *          path to the persisted editedCompilationUnit (.dep file)
   * @param editedTarget
   *          i.e.: some temporary folder
   * @param sourceFiles
   * @param editedSourceFiles
   *          is empty or null, except source has been edited
   * @param mode
   * 
   * @param syn
   *          == null, except for generated modules (which are a product of a
   *          transformation on another module)
   * @return <br>
   *         <ul>
   *         <li>edited mode -> editedCompilationUnit
   *         <li>compiled mode -> compiledCompilationUnit
   *         </ul>
   * @throws IOException
   */
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
 
  /**
   * a) mode == DoCompileMode (true) checks all files in the compileTarget
   * folder (i.e. /bin) and all other dependent modules for consistency
   * 
   * @param cl
   * @param stamper
   * @param compileDep
   *          path to the persisted compiledCompilationUnit (.dep file)
   * @param editedDep
   *          path to the persisted editedCompilationUnit (.dep file)
   * @param editedSourceFiles
   *          is empty / null, except source has been edited
   * @param mode
   * @return
   * @throws IOException
   */
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
  
/**
 * Copies content of a (consistent) <i>editedCompilationUnit</i> to a <i>compiledCompilationUnit</i>
 * @param compiled
 */
  protected void copyContentTo(CompilationUnit compiled) {
    compiled.sourceArtifacts.putAll(sourceArtifacts);
    
    // TODO: copying synthesizer?

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
  
  /**
   * Copies contents of all dependent (consistent) <i>editedCompilationUnit</i>s to <i>compiledCompilationUnit</i>s
   * @throws IOException
   */
  protected void liftEditedToCompiled() throws IOException {
    ModuleVisitor<Void> liftVisitor = new ModuleVisitor<Void>() {
      @Override public Void visit(CompilationUnit mod, Mode mode) {
        if (mod.compiledCompilationUnit == null)
          return null;
//          throw new IllegalStateException("compiledCompilationUnit of " + mod + " must not be null");
        
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
  
  public void addExternalFileDependencyLate(Path file) { addExternalFileDependencyLate(file, stamper.stampOf(file)); }
  public void addExternalFileDependencyLate(Path file, int stampOfFile) {
    try {
      externalFileDependencies.put(file, stampOfFile);
    } catch (UnsupportedOperationException e) {
      externalFileDependencies = new HashMap<>(externalFileDependencies);
      externalFileDependencies.put(file, stampOfFile);
    }
  }
  
  public void addGeneratedFile(Path file) { addGeneratedFile(file, stamper.stampOf(file)); }
  public void addGeneratedFile(Path file, int stampOfFile) {
    generatedFiles.put(file, stampOfFile);
  }
  
  /**
   * Simply use addModuleDependency(...) instead
   */
  @Deprecated
  public void addCircularModuleDependency(CompilationUnit mod) {
    circularModuleDependencies.add(mod);
  }
  
  /**
   * @return true if mod was added to moduleDependencies <br>
   *         false if mod was added to circularModuleDependencies
   */
  public boolean addModuleDependency(final CompilationUnit mod) {
    
    if (mod.dependsOnTransitively(this) || this.dependsOnTransitively(mod)) {

      circularModuleDependencies.add(mod);
      return false;
    }

    moduleDependencies.add(mod);
    return true;

  }
  
  
  // *********************************
  // Methods for querying dependencies
  // *********************************

  /**
   * is edited ...
   * @return
   */
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

  /**
   * Depends on directly
   * @param other
   * @return
   */
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
  
  public Synthesizer getSynthesizer() {
    return syn;
  }

  
  // ********************************************
  // Methods for checking compilation consistency
  // ********************************************

  protected abstract boolean isConsistentExtend(Mode mode);
  
  /**
   * @param editedSourceFiles
   * @param mode
   *          TODO: unused?
   * @return
   */
  protected boolean isConsistentWithSourceArtifacts(Map<RelativePath, Integer> editedSourceFiles, Mode mode) {
    if (sourceArtifacts.isEmpty())
      return false;
    
    boolean hasEdits = editedSourceFiles != null;
    for (Entry<RelativePath, Integer> e : sourceArtifacts.entrySet()) {
      Integer stamp = hasEdits ? editedSourceFiles.get(e.getKey()) : null;
      if (stamp != null && !stamp.equals(e.getValue()))
        return false;
      else if (stamp == null && (!FileCommands.exists(e.getKey()) || e.getValue() != stamper.stampOf(e.getKey())))
        return false;
    }

    return true;
  }

  /**
   * Checks consistency only for this module's ...
   * <ul>
   * <li>source files
   * <li>generated files
   * <li>external file dependencies
   * </ul>
   * 
   * @param editedSourceFiles
   * @param mode
   *          (compiled / edited / ... )
   * @return
   */
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

  /**
   * Checks consistency of this module including its module dependencies <br>
   * 
   * @param editedSourceFiles
   * @param mode
   *          (compiled / edited / ... )
   * @return
   */
  public boolean isConsistent(final Map<RelativePath, Integer> editedSourceFiles, Mode mode) {
    ModuleVisitor<Boolean> isConsistentVisitor = new ModuleVisitor<Boolean>() {
      @Override public Boolean visit(CompilationUnit mod, Mode mode) { return mod.isConsistentShallow(editedSourceFiles, mode); }
      @Override public Boolean combine(Boolean t1, Boolean t2) { return t1 && t2; }
      @Override public Boolean init() { return true; }
      @Override public boolean cancel(Boolean t) { return !t; }
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
//        if (dep.editedCompilationUnit != null && ranks.containsKey(dep.editedCompilationUnit))
//          dep = dep.editedCompilationUnit;
        
        Integer depRank = ranks.get(dep);
        if (depRank != null) {
          ranks.put(dep, Math.min(depRank, rank));
          ranks.put(mod, Math.min(depRank, rank));
        }
        else {
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
  
  
  /**
   * Visits the module graph starting from this module, satisfying the following properties:
   *  - every module transitively imported from `this` module is visited exactly once
   *  - if a module M1 is visited before a module M2,
   *    then (i) M1 is not transitively imported from M2 or 
   *         (ii) M1 and M2 transitively have a circular dependency and
   *              M1 transitively imports M2 using `moduleDependencies` only.   
   */
  public <T> T visit(ModuleVisitor<T> visitor) { return visit(visitor, null); }
  public <T> T visit(ModuleVisitor<T> visitor, Mode thisMode) {
    Pair<Map<CompilationUnit, Integer>, Map<CompilationUnit, Mode>> p = computeRanks(thisMode);
    final Map<CompilationUnit, Integer> ranks = p.a;
    Map<CompilationUnit, Mode> modes = p.b;
    
    Comparator<CompilationUnit> comparator = new Comparator<CompilationUnit>() {
      public int compare(CompilationUnit m1, CompilationUnit m2) {
        if (m1 == m2)
          return 0;
        
        int r1 = ranks.get(m1);
        int r2 = ranks.get(m2);
        int c = Integer.compare(r1, r2);
        if (c != 0)
          return c;
        
        if (m1.dependsOnTransitivelyNoncircularly(m2))
          // m2 before m1
          return 1;

        if (m2.dependsOnTransitivelyNoncircularly(m1))
          // m1 before m2
          return -1;
        
        // m1 and m2 are incomparable;
        return 0;
      }
    }; 
    
    CompilationUnit[] mods = ranks.keySet().toArray(new CompilationUnit[ranks.size()]);
    Arrays.sort(mods, comparator);
    
    T result = visitor.init();
    for (CompilationUnit mod : mods) {
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
    
    int moduleDepencyCount = in.readInt();
    moduleDependencies = new HashSet<>(moduleDepencyCount);
    for (int i = 0; i < moduleDepencyCount; i++) {
      String clName = (String) in.readObject();
      Class<? extends CompilationUnit> cl = (Class<? extends CompilationUnit>) getClass().getClassLoader().loadClass(clName);
      Path path = (Path) in.readObject();
      CompilationUnit mod = PersistableEntity.read(cl, stamper, path);
      if (mod == null)
        throw new IOException("Required module cannot be read: " + path);
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
        throw new IOException("Required module cannot be read: " + path);
      circularModuleDependencies.add(mod);
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
    
    out.writeBoolean(syn != null);
    if (syn != null) {
      out.writeInt(syn.modules.size());
      for (CompilationUnit mod : syn.modules) {
        out.writeObject(mod.getClass().getCanonicalName());
        out.writeObject(mod.persistentPath);
      }
      
      out.writeObject(syn.files);
    }
  }
}
